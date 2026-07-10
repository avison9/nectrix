package eabridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/gorilla/websocket"
)

// fakeEA is a real WebSocket client speaking the exact protocol a real
// MQL5/MQL4 Expert Advisor would — dialing an httptest-backed real Server
// over a real loopback TCP connection, no mocked-away transport. This
// mirrors TICKET-101's own discipline of proving protocol logic against a
// realistic peer (there it was a net.Pipe()-backed fake cTrader server;
// here it's a real WS client against a real WS server, since that's the
// side we actually own and can fully exercise ourselves — only a real
// MT5/MT4 terminal's own half is out of reach in this environment, per the
// two constraints recorded in this ticket's plan).
type fakeEA struct {
	t    *testing.T
	conn *websocket.Conn

	mu       sync.Mutex
	handlers map[string]func(json.RawMessage) any // request type -> responder
}

func newFakeEA(t *testing.T, wsURL string) *fakeEA {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	ea := &fakeEA{t: t, conn: conn, handlers: make(map[string]func(json.RawMessage) any)}
	t.Cleanup(func() { _ = conn.Close() })
	return ea
}

func (ea *fakeEA) sendHello(h helloMessage) {
	ea.t.Helper()
	h.Type = msgTypeHello
	if err := ea.conn.WriteJSON(h); err != nil {
		ea.t.Fatalf("send hello: %v", err)
	}
}

func (ea *fakeEA) readHelloAck() helloAckMessage {
	ea.t.Helper()
	var ack helloAckMessage
	if err := ea.conn.ReadJSON(&ack); err != nil {
		ea.t.Fatalf("read hello_ack: %v", err)
	}
	return ack
}

// on registers a responder for one request type, invoked from serve()'s
// read loop; the returned value is marshaled and sent straight back.
func (ea *fakeEA) on(reqType string, fn func(json.RawMessage) any) {
	ea.mu.Lock()
	defer ea.mu.Unlock()
	ea.handlers[reqType] = fn
}

// serve runs the fake EA's read loop until ctx is done or the connection
// closes — the real-EA-side counterpart of Session.readLoop, dispatching
// inbound *_request messages to registered handlers and replying.
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
			var env envelope
			if err := json.Unmarshal(data, &env); err != nil {
				continue
			}
			if env.Type == msgTypePing {
				_ = ea.conn.WriteJSON(pongMessage{Type: msgTypePong, RequestID: env.RequestID})
				continue
			}
			ea.mu.Lock()
			fn, ok := ea.handlers[env.Type]
			ea.mu.Unlock()
			if !ok {
				continue
			}
			resp := fn(json.RawMessage(data))
			if resp == nil {
				continue
			}
			if err := ea.conn.WriteJSON(resp); err != nil {
				return
			}
		}
	}()
}

func (ea *fakeEA) pushTradeEvent(msg tradeEventMessage) {
	ea.t.Helper()
	msg.Type = msgTypeTradeEvent
	if err := ea.conn.WriteJSON(msg); err != nil {
		ea.t.Fatalf("push trade event: %v", err)
	}
}

// --- test infrastructure -----------------------------------------------

type fakeEventHandler struct {
	mu          sync.Mutex
	established []string
	lost        []string
}

func (h *fakeEventHandler) OnSessionEstablished(ctx context.Context, brokerAccountID string, platform domain.BrokerType) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.established = append(h.established, brokerAccountID)
}

func (h *fakeEventHandler) OnSessionLost(ctx context.Context, brokerAccountID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lost = append(h.lost, brokerAccountID)
}

func (h *fakeEventHandler) establishedFor(id string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, e := range h.established {
		if e == id {
			return true
		}
	}
	return false
}

func (h *fakeEventHandler) lostFor(id string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, e := range h.lost {
		if e == id {
			return true
		}
	}
	return false
}

func wsURL(ts *httptest.Server) string {
	return "ws" + strings.TrimPrefix(ts.URL, "http") + "/ea/ws"
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

// --- handshake tests ------------------------------------------------------

func TestHandshake_AcceptsValidPairingAndReportsEstablished(t *testing.T) {
	events := &fakeEventHandler{}
	srv := NewServer(nil, events, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT5})

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "12345", Server: "Pepperstone-Demo"})
	ack := ea.readHelloAck()

	if !ack.Accepted {
		t.Fatalf("expected accepted=true, got reason %q", ack.Reason)
	}
	if ack.BrokerAccountID != "acct-1" {
		t.Fatalf("expected brokerAccountId=acct-1, got %q", ack.BrokerAccountID)
	}

	waitFor(t, func() bool {
		_, ok := srv.Session("acct-1")
		return ok
	})
	waitFor(t, func() bool { return events.establishedFor("acct-1") })
}

func TestHandshake_RejectsUnknownToken(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "no-such-token", Platform: "MT5", Login: "1", Server: "X"})
	ack := ea.readHelloAck()

	if ack.Accepted {
		t.Fatalf("expected rejection for unknown token")
	}
}

func TestHandshake_RejectsLoginServerMismatch(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	// A token copy-pasted for the wrong account: right token, wrong login —
	// exactly the defense-in-depth scenario the plan calls out.
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "99999", Server: "Pepperstone-Demo"})
	ack := ea.readHelloAck()

	if ack.Accepted {
		t.Fatalf("expected rejection for login mismatch")
	}
}

func TestHandshake_RejectsPlatformMismatch(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT4", Login: "12345", Server: "Pepperstone-Demo"})
	ack := ea.readHelloAck()

	if ack.Accepted {
		t.Fatalf("expected rejection for platform mismatch")
	}
}

func TestSession_Superseded(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea1 := newFakeEA(t, wsURL(ts))
	ea1.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "12345", Server: "Pepperstone-Demo"})
	ea1.readHelloAck()
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	firstSession, _ := srv.Session("acct-1")

	ea2 := newFakeEA(t, wsURL(ts))
	ea2.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "12345", Server: "Pepperstone-Demo"})
	ack2 := ea2.readHelloAck()
	if !ack2.Accepted {
		t.Fatalf("expected second connection to be accepted (supersedes first)")
	}

	waitFor(t, func() bool {
		sess, ok := srv.Session("acct-1")
		return ok && sess != firstSession
	})
}

// --- request/response round trips ------------------------------------------

func TestSession_RequestSnapshot(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "12345", Server: "Pepperstone-Demo"})
	ea.readHelloAck()

	marginLevel := 542.1
	ea.on(msgTypeSnapshotRequest, func(raw json.RawMessage) any {
		var req snapshotRequestMessage
		_ = json.Unmarshal(raw, &req)
		return snapshotResultMessage{
			Type: msgTypeSnapshotResult, RequestID: req.RequestID,
			Currency: "USD", Balance: 10000, Equity: 10250.5,
			UsedMargin: 200, FreeMargin: 10050.5, MarginLevelPct: &marginLevel,
			AsOf: "2026-07-10T12:00:00Z",
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	snap, err := sess.RequestSnapshot(ctx)
	if err != nil {
		t.Fatalf("RequestSnapshot: %v", err)
	}
	if snap.Balance != 10000 || snap.Equity != 10250.5 || snap.Currency != "USD" {
		t.Fatalf("unexpected snapshot: %+v", snap)
	}
	if snap.MarginLevelPct == nil || *snap.MarginLevelPct != 542.1 {
		t.Fatalf("unexpected margin level: %+v", snap.MarginLevelPct)
	}
}

func TestSession_RequestPositions(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT4})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT4", Login: "1", Server: "S"})
	ea.readHelloAck()

	sl := 1.0950
	ea.on(msgTypePositionsRequest, func(raw json.RawMessage) any {
		var req positionsRequestMessage
		_ = json.Unmarshal(raw, &req)
		return positionsResultMessage{
			Type: msgTypePositionsResult, RequestID: req.RequestID,
			Positions: []wirePosition{
				{
					BrokerPositionID: "555", CanonicalSymbol: "EURUSD", AssetClass: "FX",
					Direction: "BUY", VolumeLots: 0.5, OpenPrice: 1.1000,
					CurrentSLPrice: &sl, OpenedAt: "2026-07-10T11:00:00Z",
				},
			},
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	positions, err := sess.RequestPositions(ctx)
	if err != nil {
		t.Fatalf("RequestPositions: %v", err)
	}
	if len(positions) != 1 || positions[0].BrokerPositionID != "555" || positions[0].Direction != domain.TradeDirectionBuy {
		t.Fatalf("unexpected positions: %+v", positions)
	}
	if positions[0].CurrentSLPrice == nil || *positions[0].CurrentSLPrice != 1.0950 {
		t.Fatalf("unexpected SL price: %+v", positions[0].CurrentSLPrice)
	}
}

func TestSession_RequestSymbolSpec(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()

	ea.on(msgTypeSymbolSpecReq, func(raw json.RawMessage) any {
		var req symbolSpecRequestMessage
		_ = json.Unmarshal(raw, &req)
		if req.BrokerSymbolName != "EURUSD.a" {
			return symbolSpecResultMessage{Type: msgTypeSymbolSpecRes, RequestID: req.RequestID, Error: "unknown symbol"}
		}
		return symbolSpecResultMessage{
			Type: msgTypeSymbolSpecRes, RequestID: req.RequestID,
			BrokerSymbolName: "EURUSD.a", ContractSize: 100000, LotStep: 0.01,
			MinLot: 0.01, MaxLot: 100, PipSize: 0.0001, Digits: 5, MarginCurrency: "USD",
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	spec, err := sess.RequestSymbolSpec(ctx, "EURUSD.a")
	if err != nil {
		t.Fatalf("RequestSymbolSpec: %v", err)
	}
	if spec.ContractSize != 100000 || spec.Digits != 5 || spec.MarginCurrency != "USD" {
		t.Fatalf("unexpected spec: %+v", spec)
	}

	if _, err := sess.RequestSymbolSpec(ctx, "NOPE"); err == nil {
		t.Fatalf("expected error for unknown symbol")
	}
}

func TestSession_SendOrderCommand(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()

	filled := 1.1002
	ea.on(msgTypeOrderCommand, func(raw json.RawMessage) any {
		var req orderCommandMessage
		_ = json.Unmarshal(raw, &req)
		if req.Action == OrderActionPlace && req.CanonicalSymbol == "EURUSD" {
			return orderResultMessage{Type: msgTypeOrderResult, RequestID: req.RequestID, Success: true, BrokerPositionID: "9001", FilledPrice: &filled}
		}
		return orderResultMessage{Type: msgTypeOrderResult, RequestID: req.RequestID, Success: false, RejectReason: "unsupported in test"}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ea.serve(ctx)

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	result, err := sess.SendOrderCommand(ctx, OrderCommand{
		Action: OrderActionPlace, Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		Direction: domain.TradeDirectionBuy, VolumeLots: 0.1, MaxSlippagePips: 2, ClientOrderTag: "master-pos-1",
	})
	if err != nil {
		t.Fatalf("SendOrderCommand: %v", err)
	}
	if !result.Success || result.BrokerPositionID != "9001" || result.FilledPrice == nil || *result.FilledPrice != 1.1002 {
		t.Fatalf("unexpected order result: %+v", result)
	}

	// A rejected order is a normal round trip, not a Go error.
	rejected, err := sess.SendOrderCommand(ctx, OrderCommand{Action: OrderActionModify, Symbol: domain.NormalizedSymbol{CanonicalCode: "GBPUSD"}})
	if err != nil {
		t.Fatalf("SendOrderCommand (rejection path): %v", err)
	}
	if rejected.Success || rejected.RejectReason == "" {
		t.Fatalf("expected an unsuccessful result with a reject reason, got %+v", rejected)
	}
}

// --- trade event delivery ---------------------------------------------------

func TestTradeEvent_DeliveredToServerLevelCallbackAndExtraSubscribers(t *testing.T) {
	var serverGot []domain.NormalizedTradeEvent
	var serverMu sync.Mutex
	onTradeEvent := func(ctx context.Context, e domain.NormalizedTradeEvent) error {
		serverMu.Lock()
		defer serverMu.Unlock()
		serverGot = append(serverGot, e)
		return nil
	}

	srv := NewServer(onTradeEvent, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	var extraGot []domain.NormalizedTradeEvent
	var extraMu sync.Mutex
	sub := sess.Subscribe(func(ctx context.Context, e domain.NormalizedTradeEvent) error {
		extraMu.Lock()
		defer extraMu.Unlock()
		extraGot = append(extraGot, e)
		return nil
	})
	defer func() { _ = sub.Close() }()

	ea.pushTradeEvent(tradeEventMessage{
		EventID: "evt-1", EventType: string(domain.TradeEventPositionOpened),
		Position:        wirePosition{BrokerPositionID: "1", CanonicalSymbol: "EURUSD", AssetClass: "FX", Direction: "BUY", VolumeLots: 1, OpenPrice: 1.1, OpenedAt: "2026-07-10T12:00:00Z"},
		ServerTimestamp: "2026-07-10T12:00:00Z",
	})

	waitFor(t, func() bool {
		serverMu.Lock()
		defer serverMu.Unlock()
		return len(serverGot) == 1
	})
	waitFor(t, func() bool {
		extraMu.Lock()
		defer extraMu.Unlock()
		return len(extraGot) == 1
	})

	serverMu.Lock()
	if serverGot[0].EventID != "evt-1" || serverGot[0].MasterBrokerAccountID != "acct-1" {
		t.Fatalf("unexpected server-level event: %+v", serverGot[0])
	}
	serverMu.Unlock()

	// After Close(), the extra subscriber stops receiving, but the
	// server-level (Kafka-publish) subscriber keeps going.
	_ = sub.Close()
	ea.pushTradeEvent(tradeEventMessage{
		EventID: "evt-2", EventType: string(domain.TradeEventPositionClosed),
		Position:        wirePosition{BrokerPositionID: "1", CanonicalSymbol: "EURUSD", AssetClass: "FX", Direction: "BUY", VolumeLots: 1, OpenPrice: 1.1, OpenedAt: "2026-07-10T12:00:00Z"},
		ServerTimestamp: "2026-07-10T12:01:00Z",
	})
	waitFor(t, func() bool {
		serverMu.Lock()
		defer serverMu.Unlock()
		return len(serverGot) == 2
	})
	time.Sleep(50 * time.Millisecond)
	extraMu.Lock()
	defer extraMu.Unlock()
	if len(extraGot) != 1 {
		t.Fatalf("expected unsubscribed callback to stop receiving events, got %d", len(extraGot))
	}
}

// --- session lifecycle -------------------------------------------------------

func TestServer_SessionLostCallbackOnDisconnect(t *testing.T) {
	events := &fakeEventHandler{}
	srv := NewServer(nil, events, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	_ = ea.conn.Close()

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return !ok })
	waitFor(t, func() bool { return events.lostFor("acct-1") })
}

func TestSession_PendingCallsFailWhenConnectionDrops(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()
	// Deliberately register no handler for snapshot_request, so the call
	// hangs until the connection drops out from under it.

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	errCh := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, err := sess.RequestSnapshot(ctx)
		errCh <- err
	}()

	time.Sleep(50 * time.Millisecond)
	_ = ea.conn.Close()

	select {
	case err := <-errCh:
		if err == nil {
			t.Fatalf("expected an error once the connection dropped")
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("RequestSnapshot did not return after the connection dropped")
	}
}

// --- concurrency -------------------------------------------------------------

func TestSession_ConcurrentRequestsAreCorrectlyCorrelated(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()

	ea.on(msgTypeSymbolSpecReq, func(raw json.RawMessage) any {
		var req symbolSpecRequestMessage
		_ = json.Unmarshal(raw, &req)
		// Echo the requested symbol back in ContractSize (as its length) so
		// each concurrent caller can verify it got ITS OWN response, not
		// another goroutine's.
		return symbolSpecResultMessage{
			Type: msgTypeSymbolSpecRes, RequestID: req.RequestID,
			BrokerSymbolName: req.BrokerSymbolName, ContractSize: float64(len(req.BrokerSymbolName)),
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ea.serve(ctx)

	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })
	sess, _ := srv.Session("acct-1")

	const n = 20
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			symbol := fmt.Sprintf("SYM%02d", i)
			spec, err := sess.RequestSymbolSpec(ctx, symbol)
			if err != nil {
				errs <- err
				return
			}
			if spec.BrokerSymbolName != symbol || spec.ContractSize != float64(len(symbol)) {
				errs <- fmt.Errorf("mismatched response for %s: got %+v", symbol, spec)
			}
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
}

// --- TICKET-103: readLoop-before-OnSessionEstablished ordering -------------

// syncSymbolResolveEventHandler's OnSessionEstablished immediately issues a
// SYNCHRONOUS RequestSymbolSpec call against the just-established session —
// exactly what a real SymbolMappingReporter-wiring OnSessionEstablished
// implementation does (apps/mt5-bridge-gateway/internal/pairing.StatusHandler).
// Real, live-verified deadlock hazard this proves fixed: RequestSymbolSpec
// blocks on session.call()'s <-ch until readLoop's resolvePending delivers a
// response — if readLoop hasn't started yet (the pre-fix ordering), no
// reader ever exists to deliver it, and this hangs forever.
type syncSymbolResolveEventHandler struct {
	server *Server

	mu      sync.Mutex
	result  SymbolSpecResult
	callErr error
}

func (h *syncSymbolResolveEventHandler) OnSessionEstablished(ctx context.Context, brokerAccountID string, platform domain.BrokerType) {
	sess, ok := h.server.Session(brokerAccountID)
	if !ok {
		h.mu.Lock()
		h.callErr = fmt.Errorf("no live session for %s", brokerAccountID)
		h.mu.Unlock()
		return
	}
	// Bounded, independent of the real request context, so a regression
	// (the deadlock this test guards against) fails this test within a few
	// seconds instead of hanging the whole suite indefinitely.
	callCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	result, err := sess.RequestSymbolSpec(callCtx, "EURUSD")
	h.mu.Lock()
	h.result, h.callErr = result, err
	h.mu.Unlock()
}

func (h *syncSymbolResolveEventHandler) OnSessionLost(ctx context.Context, brokerAccountID string) {}

func (h *syncSymbolResolveEventHandler) snapshot() (SymbolSpecResult, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.result, h.callErr
}

func TestOnSessionEstablished_SynchronousRequestSymbolSpecDoesNotDeadlock(t *testing.T) {
	events := &syncSymbolResolveEventHandler{}
	srv := NewServer(nil, events, nil)
	events.server = srv
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "1", ExpectedServer: "S", Platform: domain.BrokerTypeMT5})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	ea := newFakeEA(t, wsURL(ts))
	// sendHello/readHelloAck FIRST (a single direct synchronous read of
	// exactly the hello_ack frame), matching every other test in this
	// file — only THEN register the responder and start serve()'s
	// background read loop. Calling serve() before readHelloAck would put
	// two goroutines concurrently reading the same *websocket.Conn (serve's
	// background loop and readHelloAck's direct read), which is unsafe/racy
	// with gorilla/websocket. The server may still issue
	// symbol_spec_request immediately after accepting the handshake (from
	// within OnSessionEstablished, before this test code even reaches
	// readHelloAck) — that's fine, it simply waits in the socket's receive
	// buffer until serve()'s read loop starts, no data is lost.
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT5", Login: "1", Server: "S"})
	ea.readHelloAck()

	ea.on(msgTypeSymbolSpecReq, func(raw json.RawMessage) any {
		var req symbolSpecRequestMessage
		_ = json.Unmarshal(raw, &req)
		return symbolSpecResultMessage{
			Type: msgTypeSymbolSpecRes, RequestID: req.RequestID,
			BrokerSymbolName: "EURUSD", ContractSize: 100000,
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ea.serve(ctx)

	waitFor(t, func() bool {
		result, err := events.snapshot()
		return err != nil || result.BrokerSymbolName != ""
	})

	result, err := events.snapshot()
	if err != nil {
		t.Fatalf("synchronous RequestSymbolSpec from OnSessionEstablished failed (deadlock or real error): %v", err)
	}
	if result.BrokerSymbolName != "EURUSD" || result.ContractSize != 100000 {
		t.Fatalf("unexpected result: %+v", result)
	}
}
