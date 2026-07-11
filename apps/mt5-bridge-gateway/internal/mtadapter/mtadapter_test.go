package mtadapter

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"
	"github.com/gorilla/websocket"

	domain "github.com/avison9/nectrix/go-domain"
)

// fakeEA is the same minimal real-WebSocket-client harness eabridge's own
// tests use — dialing a real Server over real loopback TCP, proving the
// adapter end-to-end without a real MT5/MT4 terminal (which this
// environment can't run — see this ticket's plan for the two constraints).
type fakeEA struct {
	t    *testing.T
	conn *websocket.Conn

	mu       sync.Mutex
	handlers map[string]func(json.RawMessage) any
}

func newFakeEA(t *testing.T, ts *httptest.Server) *fakeEA {
	t.Helper()
	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ea/ws"
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	ea := &fakeEA{t: t, conn: conn, handlers: make(map[string]func(json.RawMessage) any)}
	t.Cleanup(func() { _ = conn.Close() })
	return ea
}

func (ea *fakeEA) handshake(pairingToken, platform, login, server string) {
	ea.t.Helper()
	if err := ea.conn.WriteJSON(map[string]string{
		"type": "hello", "pairingToken": pairingToken, "platform": platform, "login": login, "server": server,
	}); err != nil {
		ea.t.Fatalf("send hello: %v", err)
	}
	var ack struct {
		Accepted        bool   `json:"accepted"`
		BrokerAccountID string `json:"brokerAccountId"`
		Reason          string `json:"reason"`
	}
	if err := ea.conn.ReadJSON(&ack); err != nil {
		ea.t.Fatalf("read hello_ack: %v", err)
	}
	if !ack.Accepted {
		ea.t.Fatalf("handshake rejected: %s", ack.Reason)
	}
}

func (ea *fakeEA) on(reqType string, fn func(json.RawMessage) any) {
	ea.mu.Lock()
	defer ea.mu.Unlock()
	ea.handlers[reqType] = fn
}

func (ea *fakeEA) serve(ctx context.Context) {
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}
			_, data, err := ea.conn.ReadMessage()
			if err != nil {
				return
			}
			var env struct {
				Type      string `json:"type"`
				RequestID string `json:"requestId"`
			}
			if err := json.Unmarshal(data, &env); err != nil {
				continue
			}
			if env.Type == "ping" {
				_ = ea.conn.WriteJSON(map[string]string{"type": "pong", "requestId": env.RequestID})
				continue
			}
			ea.mu.Lock()
			fn, ok := ea.handlers[env.Type]
			ea.mu.Unlock()
			if !ok {
				continue
			}
			if resp := fn(data); resp != nil {
				if err := ea.conn.WriteJSON(resp); err != nil {
					return
				}
			}
		}
	}()
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("condition not met within timeout")
}

func newTestServer(t *testing.T) (*eabridge.Server, *httptest.Server) {
	t.Helper()
	srv := eabridge.NewServer(nil, nil, nil)
	mux := http.NewServeMux()
	mux.Handle("/ea/ws", srv.Handler())
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return srv, ts
}

func TestBrokerType(t *testing.T) {
	srv, _ := newTestServer(t)
	if got := NewMT5(srv).BrokerType(); got != domain.BrokerTypeMT5 {
		t.Fatalf("NewMT5.BrokerType() = %v, want MT5", got)
	}
	if got := NewMT4(srv).BrokerType(); got != domain.BrokerTypeMT4 {
		t.Fatalf("NewMT4.BrokerType() = %v, want MT4", got)
	}
}

func TestConnect_FailsUntilEAIsPaired(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT5(srv)
	creds := domain.BrokerCredentials{BrokerType: domain.BrokerTypeMT5, AccountID: "acct-1", Login: "12345", Server: "Pepperstone-Demo", APIToken: "tok-1"}

	if _, err := adapter.Connect(context.Background(), creds); err == nil {
		t.Fatalf("expected Connect to fail before any EA session is paired")
	}

	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT5})
	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT5", "12345", "Pepperstone-Demo")
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	handle, err := adapter.Connect(context.Background(), creds)
	if err != nil {
		t.Fatalf("Connect after pairing: %v", err)
	}
	if handle.AccountID != "acct-1" || handle.BrokerType != domain.BrokerTypeMT5 {
		t.Fatalf("unexpected handle: %+v", handle)
	}
}

func TestHealthCheck(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT4(srv)
	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT4})

	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	health, err := adapter.HealthCheck(context.Background(), handle)
	if err != nil {
		t.Fatalf("HealthCheck: %v", err)
	}
	if health.Connected {
		t.Fatalf("expected Connected=false before pairing")
	}

	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT4", "1", "S")
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	health, err = adapter.HealthCheck(ctx, handle)
	if err != nil {
		t.Fatalf("HealthCheck after pairing: %v", err)
	}
	if !health.Connected {
		t.Fatalf("expected Connected=true after pairing, detail=%q", health.Detail)
	}
}

func TestGetAccountSnapshotAndPositions(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT5(srv)
	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})

	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT5", "1", "S")
	ea.on("snapshot_request", func(raw json.RawMessage) any {
		var req struct {
			RequestID string `json:"requestId"`
		}
		_ = json.Unmarshal(raw, &req)
		return map[string]any{"type": "snapshot_result", "requestId": req.RequestID, "currency": "USD", "balance": 5000.0, "equity": 5100.0, "usedMargin": 100.0, "freeMargin": 5000.0, "asOf": "2026-07-10T12:00:00Z"}
	})
	ea.on("positions_request", func(raw json.RawMessage) any {
		var req struct {
			RequestID string `json:"requestId"`
		}
		_ = json.Unmarshal(raw, &req)
		return map[string]any{"type": "positions_result", "requestId": req.RequestID, "positions": []map[string]any{
			{"brokerPositionId": "1", "canonicalSymbol": "EURUSD", "assetClass": "FX", "direction": "BUY", "volumeLots": 0.1, "openPrice": 1.1, "openedAt": "2026-07-10T12:00:00Z"},
		}}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	snap, err := adapter.GetAccountSnapshot(ctx, handle)
	if err != nil {
		t.Fatalf("GetAccountSnapshot: %v", err)
	}
	if snap.Balance != 5000 || snap.Currency != "USD" {
		t.Fatalf("unexpected snapshot: %+v", snap)
	}

	positions, err := adapter.GetOpenPositions(ctx, handle)
	if err != nil {
		t.Fatalf("GetOpenPositions: %v", err)
	}
	if len(positions) != 1 || positions[0].Symbol.CanonicalCode != "EURUSD" {
		t.Fatalf("unexpected positions: %+v", positions)
	}
}

func TestPlaceModifyClosePosition(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT5(srv)
	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})

	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT5", "1", "S")
	ea.on("order_command", func(raw json.RawMessage) any {
		var req struct {
			RequestID string `json:"requestId"`
			Action    string `json:"action"`
		}
		_ = json.Unmarshal(raw, &req)
		switch req.Action {
		case "PLACE":
			return map[string]any{"type": "order_result", "requestId": req.RequestID, "success": true, "brokerPositionId": "42"}
		case "MODIFY":
			return map[string]any{"type": "order_result", "requestId": req.RequestID, "success": true, "brokerPositionId": "42"}
		case "CLOSE":
			return map[string]any{"type": "order_result", "requestId": req.RequestID, "success": true, "brokerPositionId": "42"}
		}
		return map[string]any{"type": "order_result", "requestId": req.RequestID, "success": false, "rejectReason": "unknown action"}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	handle := domain.ConnectionHandle{AccountID: "acct-1"}

	placeResult, err := adapter.PlaceOrder(ctx, handle, domain.NormalizedOrderRequest{
		Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX}, Direction: domain.TradeDirectionBuy, VolumeLots: 0.1,
	})
	if err != nil || !placeResult.Success || placeResult.BrokerPositionID != "42" {
		t.Fatalf("PlaceOrder: result=%+v err=%v", placeResult, err)
	}

	sl := 1.05
	modifyResult, err := adapter.ModifyPosition(ctx, handle, "42", domain.SLTPChange{SLPrice: &sl})
	if err != nil || !modifyResult.Success {
		t.Fatalf("ModifyPosition: result=%+v err=%v", modifyResult, err)
	}

	closeResult, err := adapter.ClosePosition(ctx, handle, "42", nil)
	if err != nil || !closeResult.Success {
		t.Fatalf("ClosePosition: result=%+v err=%v", closeResult, err)
	}
}

func TestStreamTradeEvents(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT5(srv)
	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})

	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT5", "1", "S")
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	var mu sync.Mutex
	var got []domain.NormalizedTradeEvent
	sub, err := adapter.StreamTradeEvents(context.Background(), domain.ConnectionHandle{AccountID: "acct-1"}, func(ctx context.Context, e domain.NormalizedTradeEvent) error {
		mu.Lock()
		defer mu.Unlock()
		got = append(got, e)
		return nil
	})
	if err != nil {
		t.Fatalf("StreamTradeEvents: %v", err)
	}
	defer func() { _ = sub.Close() }()

	if err := ea.conn.WriteJSON(map[string]any{
		"type": "trade_event", "eventId": "evt-1", "eventType": "POSITION_OPENED",
		"position":        map[string]any{"brokerPositionId": "1", "canonicalSymbol": "EURUSD", "assetClass": "FX", "direction": "BUY", "volumeLots": 1.0, "openPrice": 1.1, "openedAt": "2026-07-10T12:00:00Z"},
		"serverTimestamp": "2026-07-10T12:00:00Z",
	}); err != nil {
		t.Fatalf("push trade event: %v", err)
	}

	waitFor(t, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(got) == 1
	})
	mu.Lock()
	if got[0].EventID != "evt-1" || got[0].MasterBrokerAccountID != "acct-1" {
		t.Fatalf("unexpected event: %+v", got[0])
	}
	mu.Unlock()
}

func TestResolveSymbolAndGetSymbolSpecification(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT5(srv)
	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})

	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT5", "1", "S")
	ea.on("symbol_spec_request", func(raw json.RawMessage) any {
		var req struct {
			RequestID        string `json:"requestId"`
			BrokerSymbolName string `json:"brokerSymbolName"`
		}
		_ = json.Unmarshal(raw, &req)
		if req.BrokerSymbolName != "EURUSD.a" {
			return map[string]any{"type": "symbol_spec_result", "requestId": req.RequestID, "error": "unknown symbol"}
		}
		return map[string]any{
			"type": "symbol_spec_result", "requestId": req.RequestID, "brokerSymbolName": "EURUSD.a",
			"contractSize": 100000.0, "lotStep": 0.01, "minLot": 0.01, "maxLot": 50.0, "pipSize": 0.0001, "digits": 5.0, "marginCurrency": "USD",
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	normalized, err := adapter.ResolveSymbol(ctx, "EURUSD.a")
	if err != nil {
		t.Fatalf("ResolveSymbol: %v", err)
	}
	if normalized.CanonicalCode != "EURUSD" || normalized.AssetClass != domain.AssetClassFX {
		t.Fatalf("unexpected normalized symbol: %+v", normalized)
	}

	spec, err := adapter.GetSymbolSpecification(ctx, normalized)
	if err != nil {
		t.Fatalf("GetSymbolSpecification: %v", err)
	}
	if spec.ContractSize != 100000 || spec.BrokerSymbolName != "EURUSD.a" || spec.Digits != 5 {
		t.Fatalf("unexpected spec: %+v", spec)
	}

	// A canonical code never resolved has no cached spec — a caller bug,
	// not a legitimate gap (see GetSymbolSpecification's doc comment).
	if _, err := adapter.GetSymbolSpecification(ctx, domain.NormalizedSymbol{CanonicalCode: "GBPUSD"}); err == nil {
		t.Fatalf("expected error for unresolved canonical code")
	}
}

// TestResolveSymbol_UsesCuratedCatalogAssetClass proves ResolveSymbol now
// returns a real, catalog-backed AssetClass (TICKET-103) for an instrument
// the old 6-letter-code-only heuristic could never classify correctly (an
// index CFD like "US500" is not 6 letters, so the old heuristic guessed
// COMMODITY, not INDEX).
func TestResolveSymbol_UsesCuratedCatalogAssetClass(t *testing.T) {
	srv, ts := newTestServer(t)
	adapter := NewMT5(srv)
	srv.RegisterPairing("tok-1", eabridge.PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})

	ea := newFakeEA(t, ts)
	ea.handshake("tok-1", "MT5", "1", "S")
	ea.on("symbol_spec_request", func(raw json.RawMessage) any {
		var req struct {
			RequestID        string `json:"requestId"`
			BrokerSymbolName string `json:"brokerSymbolName"`
		}
		_ = json.Unmarshal(raw, &req)
		return map[string]any{
			"type": "symbol_spec_result", "requestId": req.RequestID, "brokerSymbolName": req.BrokerSymbolName,
			"contractSize": 1.0, "lotStep": 0.1, "minLot": 0.1, "maxLot": 20.0, "pipSize": 1.0, "digits": 2.0, "marginCurrency": "USD",
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	normalized, err := adapter.ResolveSymbol(ctx, "US500")
	if err != nil {
		t.Fatalf("ResolveSymbol: %v", err)
	}
	if normalized.AssetClass != domain.AssetClassIndex {
		t.Fatalf("AssetClass = %q, want INDEX", normalized.AssetClass)
	}
}
