package eabridge

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
)

// fakeEAHTTP is a real HTTP client speaking the exact protocol a real MQL4
// Expert Advisor's WebRequest()-based polling loop would (TICKET-121) —
// dialing an httptest-backed real Server over real loopback HTTP, no
// mocked-away transport, same discipline fakeEA (the WebSocket-side fake)
// already established.
type fakeEAHTTP struct {
	t            *testing.T
	baseURL      string
	sessionToken string

	mu       sync.Mutex
	handlers map[string]func(json.RawMessage) any
}

func newFakeEAHTTP(t *testing.T, baseURL string) *fakeEAHTTP {
	t.Helper()
	return &fakeEAHTTP{t: t, baseURL: baseURL, handlers: make(map[string]func(json.RawMessage) any)}
}

func (ea *fakeEAHTTP) post(t *testing.T, path string, body any) *http.Response {
	t.Helper()
	data, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	resp, err := http.Post(ea.baseURL+path, "application/json", bytes.NewReader(data))
	if err != nil {
		t.Fatalf("POST %s: %v", path, err)
	}
	return resp
}

func (ea *fakeEAHTTP) sendHello(h helloMessage) helloAckHTTPMessage {
	ea.t.Helper()
	h.Type = msgTypeHello
	resp := ea.post(ea.t, "/ea/hello", h)
	defer func() { _ = resp.Body.Close() }()
	var ack helloAckHTTPMessage
	if err := json.NewDecoder(resp.Body).Decode(&ack); err != nil {
		ea.t.Fatalf("decode hello ack: %v", err)
	}
	ea.sessionToken = ack.SessionToken
	return ack
}

func (ea *fakeEAHTTP) postEvent(t *testing.T, msg any) {
	t.Helper()
	data, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("marshal event message: %v", err)
	}
	resp := ea.post(t, "/ea/events", eventsRequest{SessionToken: ea.sessionToken, Message: data})
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("post event: unexpected status %d", resp.StatusCode)
	}
}

func (ea *fakeEAHTTP) on(reqType string, fn func(json.RawMessage) any) {
	ea.mu.Lock()
	defer ea.mu.Unlock()
	ea.handlers[reqType] = fn
}

// pollAndDispatchOnceNoFatal is pollAndDispatchOnce's background-goroutine-
// safe twin — errors are logged, never t.Fatalf'd (a background goroutine
// calling Fatalf on a *testing.T not owned by the current test's own
// goroutine is a real footgun, not just style; this mirrors fakeEA.serve's
// own goroutine, which also just returns/continues silently on a failed
// read or write rather than failing the test directly).
func (ea *fakeEAHTTP) pollAndDispatchOnceNoFatal() {
	data, err := json.Marshal(pollRequest{SessionToken: ea.sessionToken})
	if err != nil {
		return
	}
	resp, err := http.Post(ea.baseURL+"/ea/poll", "application/json", bytes.NewReader(data))
	if err != nil {
		return
	}
	defer func() { _ = resp.Body.Close() }()
	var res pollResponse
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return
	}
	for _, raw := range res.Messages {
		var env envelope
		if err := json.Unmarshal(raw, &env); err != nil {
			continue
		}
		var reply any
		if env.Type == msgTypePing {
			reply = pongMessage{Type: msgTypePong, RequestID: env.RequestID}
		} else {
			ea.mu.Lock()
			fn, ok := ea.handlers[env.Type]
			ea.mu.Unlock()
			if !ok {
				continue
			}
			reply = fn(raw)
			if reply == nil {
				continue
			}
		}
		replyData, err := json.Marshal(reply)
		if err != nil {
			continue
		}
		eventsData, err := json.Marshal(eventsRequest{SessionToken: ea.sessionToken, Message: replyData})
		if err != nil {
			continue
		}
		eventsResp, err := http.Post(ea.baseURL+"/ea/events", "application/json", bytes.NewReader(eventsData))
		if err != nil {
			continue
		}
		_ = eventsResp.Body.Close()
	}
}

// servePolling runs pollAndDispatchOnceNoFatal in a loop on a short
// interval, suitable for tests that need a live responder while some other
// goroutine (e.g. Session.call) is blocked waiting — mirrors fakeEA.serve's
// background-loop role, adapted for request/response polling.
func (ea *fakeEAHTTP) servePolling(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				ea.pollAndDispatchOnceNoFatal()
			}
		}
	}()
}

func TestHTTPHandshake_AcceptsValidPairingAndReportsEstablished(t *testing.T) {
	events := &fakeEventHandler{}
	srv := NewServer(nil, events, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "Pepperstone-Demo", Platform: domain.BrokerTypeMT4})

	ts := httptest.NewServer(routedHandler(srv))
	defer ts.Close()

	ea := newFakeEAHTTP(t, ts.URL)
	ack := ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT4", Login: "12345", Server: "Pepperstone-Demo"})

	if !ack.Accepted {
		t.Fatalf("expected accepted=true, got reason %q", ack.Reason)
	}
	if ack.BrokerAccountID != "acct-1" {
		t.Fatalf("expected brokerAccountId=acct-1, got %q", ack.BrokerAccountID)
	}
	if ack.SessionToken == "" {
		t.Fatalf("expected a non-empty sessionToken")
	}

	waitFor(t, func() bool {
		_, ok := srv.Session("acct-1")
		return ok
	})
	waitFor(t, func() bool { return events.establishedFor("acct-1") })
}

func TestHTTPHandshake_RejectsUnknownToken(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	ts := httptest.NewServer(routedHandler(srv))
	defer ts.Close()

	ea := newFakeEAHTTP(t, ts.URL)
	ack := ea.sendHello(helloMessage{PairingToken: "no-such-token", Platform: "MT4", Login: "1", Server: "X"})

	if ack.Accepted {
		t.Fatalf("expected rejection for unknown token")
	}
	if ack.SessionToken != "" {
		t.Fatalf("expected no sessionToken on rejection")
	}
}

func TestHTTPPoll_UnknownSessionTokenRejected(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	ts := httptest.NewServer(routedHandler(srv))
	defer ts.Close()

	ea := newFakeEAHTTP(t, ts.URL)
	ea.sessionToken = "not-a-real-token"
	resp := ea.post(t, "/ea/poll", pollRequest{SessionToken: ea.sessionToken})
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 for unknown session token, got %d", resp.StatusCode)
	}
}

func TestHTTPSession_RequestSnapshot(t *testing.T) {
	srv := NewServer(nil, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "X", Platform: domain.BrokerTypeMT4})
	ts := httptest.NewServer(routedHandler(srv))
	defer ts.Close()

	ea := newFakeEAHTTP(t, ts.URL)
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT4", Login: "12345", Server: "X"})
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	ea.on(msgTypeSnapshotRequest, func(raw json.RawMessage) any {
		var req snapshotRequestMessage
		_ = json.Unmarshal(raw, &req)
		return snapshotResultMessage{
			Type: msgTypeSnapshotResult, RequestID: req.RequestID,
			Currency: "USD", Balance: 10000, Equity: 10500, UsedMargin: 100, FreeMargin: 10400, AsOf: "2026-01-01T00:00:00Z",
		}
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ea.servePolling(ctx)

	sess, _ := srv.Session("acct-1")
	snap, err := sess.RequestSnapshot(context.Background())
	if err != nil {
		t.Fatalf("RequestSnapshot: %v", err)
	}
	if snap.Balance != 10000 || snap.Equity != 10500 {
		t.Fatalf("unexpected snapshot: %+v", snap)
	}
}

func TestHTTPTradeEvent_DeliveredToServerLevelCallback(t *testing.T) {
	received := make(chan domain.NormalizedTradeEvent, 1)
	srv := NewServer(func(ctx context.Context, ev domain.NormalizedTradeEvent) error {
		received <- ev
		return nil
	}, nil, nil)
	srv.RegisterPairing("tok-1", PairingInfo{BrokerAccountID: "acct-1", ExpectedLogin: "12345", ExpectedServer: "X", Platform: domain.BrokerTypeMT4})
	ts := httptest.NewServer(routedHandler(srv))
	defer ts.Close()

	ea := newFakeEAHTTP(t, ts.URL)
	ea.sendHello(helloMessage{PairingToken: "tok-1", Platform: "MT4", Login: "12345", Server: "X"})
	waitFor(t, func() bool { _, ok := srv.Session("acct-1"); return ok })

	ea.postEvent(t, tradeEventMessage{
		Type: msgTypeTradeEvent, EventID: "evt-1", EventType: "POSITION_OPENED",
		Position: wirePosition{
			BrokerPositionID: "123", CanonicalSymbol: "EURUSD", AssetClass: "FX", Direction: "BUY",
			VolumeLots: 1.0, OpenPrice: 1.1000, OpenedAt: "2026-01-01T00:00:00Z",
		},
		ServerTimestamp: "2026-01-01T00:00:00Z",
	})

	select {
	case ev := <-received:
		if ev.MasterBrokerAccountID != "acct-1" || ev.EventID != "evt-1" {
			t.Fatalf("unexpected event: %+v", ev)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("trade event was not delivered to the server-level callback in time")
	}
}

// routedHandler mounts all 4 EA routes on one mux — mirrors main.go's own
// wiring (Handler for WS, HelloHandler/PollHandler/EventsHandler for HTTP)
// so these tests exercise the real routing shape, not just each handler in
// isolation.
func routedHandler(srv *Server) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/ea/ws", srv.Handler())
	mux.Handle("/ea/hello", srv.HelloHandler())
	mux.Handle("/ea/poll", srv.PollHandler())
	mux.Handle("/ea/events", srv.EventsHandler())
	return mux
}
