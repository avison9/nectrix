package reconcile_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/broker-adapters/internal/reconcile"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

type fakeLister struct {
	mu       sync.Mutex
	accounts []reconcile.BrokerAccountRef
	err      error
	calls    int
}

func (f *fakeLister) ListBrokerAccounts(ctx context.Context) ([]reconcile.BrokerAccountRef, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.err != nil {
		return nil, f.err
	}
	out := make([]reconcile.BrokerAccountRef, len(f.accounts))
	copy(out, f.accounts)
	return out, nil
}

func (f *fakeLister) setAccounts(accounts []reconcile.BrokerAccountRef) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.accounts = accounts
}

func (f *fakeLister) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func (f *fakeLister) setErr(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.err = err
}

type fakeCredentialFetcher struct {
	mu    sync.Mutex
	err   map[string]error
	calls map[string]int
}

func newFakeCredentialFetcher() *fakeCredentialFetcher {
	return &fakeCredentialFetcher{err: make(map[string]error), calls: make(map[string]int)}
}

func (f *fakeCredentialFetcher) FetchCredentials(ctx context.Context, brokerAccountID string) (reconcile.BrokerAccountCredentials, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls[brokerAccountID]++
	if err, ok := f.err[brokerAccountID]; ok {
		return reconcile.BrokerAccountCredentials{}, err
	}
	return reconcile.BrokerAccountCredentials{AccessToken: "tok-" + brokerAccountID, CtidTraderAccountID: 42}, nil
}

func (f *fakeCredentialFetcher) callCountFor(id string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls[id]
}

func (f *fakeCredentialFetcher) setErr(id string, err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.err[id] = err
}

type fakeStatusReporter struct {
	mu       sync.Mutex
	reported []reportedStatus
}

type reportedStatus struct {
	id, status, detail string
}

func newFakeStatusReporter() *fakeStatusReporter {
	return &fakeStatusReporter{}
}

func (f *fakeStatusReporter) ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.reported = append(f.reported, reportedStatus{id: brokerAccountID, status: status, detail: detail})
	return nil
}

func (f *fakeStatusReporter) statusesFor(id string) []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []string
	for _, r := range f.reported {
		if r.id == id {
			out = append(out, r.status)
		}
	}
	return out
}

type fakeSubscription struct {
	closed bool
}

func (s *fakeSubscription) Close() error { s.closed = true; return nil }

type fakeAdapter struct {
	mu              sync.Mutex
	connectedIDs    map[string]bool
	connectErr      map[string]error
	streamErr       map[string]error
	disconnectCalls int
	streamCalls     int
	lastOnEvent     func(context.Context, domain.NormalizedTradeEvent) error
}

func newFakeAdapter() *fakeAdapter {
	return &fakeAdapter{connectedIDs: make(map[string]bool)}
}

func (a *fakeAdapter) Connect(ctx context.Context, credentials domain.BrokerCredentials) (domain.ConnectionHandle, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if err, ok := a.connectErr[credentials.AccountID]; ok {
		return domain.ConnectionHandle{}, err
	}
	a.connectedIDs[credentials.AccountID] = true
	return domain.ConnectionHandle{ID: uuid.NewString(), BrokerType: domain.BrokerTypeCTrader, AccountID: credentials.AccountID}, nil
}

func (a *fakeAdapter) Disconnect(ctx context.Context, handle domain.ConnectionHandle) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.disconnectCalls++
	delete(a.connectedIDs, handle.AccountID)
	return nil
}

func (a *fakeAdapter) StreamTradeEvents(ctx context.Context, handle domain.ConnectionHandle, onEvent func(context.Context, domain.NormalizedTradeEvent) error) (domain.Subscription, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.streamCalls++
	a.lastOnEvent = onEvent
	if err, ok := a.streamErr[handle.AccountID]; ok {
		return nil, err
	}
	return &fakeSubscription{}, nil
}

func (a *fakeAdapter) connectedCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.connectedIDs)
}

func (a *fakeAdapter) isConnected(id string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.connectedIDs[id]
}

func (a *fakeAdapter) streamCallCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.streamCalls
}

func (a *fakeAdapter) getLastOnEvent() func(context.Context, domain.NormalizedTradeEvent) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.lastOnEvent
}

func noopOnEvent(context.Context, domain.NormalizedTradeEvent) error { return nil }

// TestReconcileOnce_ReportsConnectionStatus is a regression test for a real bug caught by hand
// during this ticket's live-verification pass: a real account connected successfully against a
// live cTrader demo server, but its broker_accounts row stayed PENDING forever because nothing
// ever called the connection-status-reporting endpoint. This proves the loop now reports
// CONNECTED on a real successful connect, REAUTH_REQUIRED on a failed connect, and DISCONNECTED
// when an account is no longer listed.
func TestReconcileOnce_ReportsConnectionStatus(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-good"}, {ID: "acc-bad"}}}
	adapter := newFakeAdapter()
	adapter.connectErr = map[string]error{"acc-bad": errors.New("bad token")}
	reporter := newFakeStatusReporter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), reporter, nil, nil, adapter, noopOnEvent, 20*time.Millisecond, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.isConnected("acc-good") })
	waitFor(t, func() bool { return len(reporter.statusesFor("acc-bad")) > 0 })

	if got := reporter.statusesFor("acc-good"); len(got) == 0 || got[0] != "CONNECTED" {
		t.Fatalf("statusesFor(acc-good) = %v, want first entry CONNECTED", got)
	}
	if got := reporter.statusesFor("acc-bad"); len(got) == 0 || got[0] != "REAUTH_REQUIRED" {
		t.Fatalf("statusesFor(acc-bad) = %v, want first entry REAUTH_REQUIRED", got)
	}

	lister.setAccounts(nil)
	waitFor(t, func() bool { return adapter.connectedCount() == 0 })
	waitFor(t, func() bool {
		statuses := reporter.statusesFor("acc-good")
		return len(statuses) > 0 && statuses[len(statuses)-1] == "DISCONNECTED"
	})
}

func TestReconcileOnce_PassesTheLoopsOwnOnEventToStreamTradeEvents(t *testing.T) {
	var calls int
	onEvent := func(ctx context.Context, event domain.NormalizedTradeEvent) error {
		calls++
		return nil
	}
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, onEvent, time.Hour, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.streamCallCount() == 1 })

	onEventFn := adapter.getLastOnEvent()
	if onEventFn == nil {
		t.Fatal("StreamTradeEvents was called with a nil onEvent callback")
	}
	if err := onEventFn(context.Background(), domain.NormalizedTradeEvent{}); err != nil {
		t.Fatalf("invoking the forwarded callback: %v", err)
	}
	if calls != 1 {
		t.Fatalf("the loop's own onEvent was called %d times, want 1 — StreamTradeEvents must be given the caller's real callback, not a stand-in", calls)
	}
}

func TestReconcileOnce_ConnectsAndStreamsNewlyListedAccounts(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{
		{ID: "acc-1"},
		{ID: "acc-2"},
	}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, time.Hour, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.connectedCount() == 2 })

	if got := adapter.streamCallCount(); got != 2 {
		t.Fatalf("streamCalls = %d, want 2", got)
	}
}

func TestRun_DisconnectsAccountsNoLongerListed(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}, {ID: "acc-2"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, 20*time.Millisecond, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.connectedCount() == 2 })

	lister.setAccounts([]reconcile.BrokerAccountRef{{ID: "acc-1"}})
	waitFor(t, func() bool { return adapter.connectedCount() == 1 })

	if !adapter.isConnected("acc-1") {
		t.Fatal("acc-1 should remain connected")
	}
}

func TestRun_DoesNotReconnectAlreadyConnectedAccounts(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}}}
	adapter := newFakeAdapter()
	fetcher := newFakeCredentialFetcher()
	loop := reconcile.New(lister, fetcher, newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, 10*time.Millisecond, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)

	// Let several reconcile passes happen.
	waitFor(t, func() bool { return lister.callCount() >= 3 })

	if got := adapter.streamCallCount(); got != 1 {
		t.Fatalf("streamCalls = %d over %d reconcile passes, want exactly 1 (must not reconnect an already-connected account)", got, lister.callCount())
	}
	if got := fetcher.callCountFor("acc-1"); got != 1 {
		t.Fatalf("FetchCredentials called %d times for an already-connected account, want exactly 1 (must not re-fetch secrets every poll)", got)
	}
}

func TestRun_ContextCancellationDisconnectsEverything(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}, {ID: "acc-2"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, time.Hour, nil)

	ctx, cancel := context.WithCancel(context.Background())
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.connectedCount() == 2 })

	cancel()
	waitFor(t, func() bool { return adapter.connectedCount() == 0 })
}

func TestReconcileOnce_ListerErrorLeavesExistingConnectionsAlone(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, 10*time.Millisecond, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.connectedCount() == 1 })

	lister.setErr(errors.New("core-app internal endpoint unreachable"))

	time.Sleep(50 * time.Millisecond)
	if adapter.connectedCount() != 1 {
		t.Fatalf("connectedCount = %d, want 1 (a lister error must not tear down existing connections)", adapter.connectedCount())
	}
}

func TestReconcileOnce_CredentialFetchFailureSkipsThatAccountButNotOthers(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-bad"}, {ID: "acc-good"}}}
	adapter := newFakeAdapter()
	fetcher := newFakeCredentialFetcher()
	fetcher.setErr("acc-bad", errors.New("core app: decrypt failed"))
	loop := reconcile.New(lister, fetcher, newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, time.Hour, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.isConnected("acc-good") })

	time.Sleep(20 * time.Millisecond)
	if adapter.isConnected("acc-bad") {
		t.Fatal("acc-bad should never have been connected — its credential fetch failed")
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("condition not met within 2s")
}

// orderedEventLog records, in real call order, which of two TICKET-103
// hooks fired first — the actual proof mechanism for the
// nectrix_plan/docs/07-auth-onboarding-broker-linking.md §7.5 ordering
// requirement (populate symbol_mappings suggestions BEFORE reporting
// CONNECTED), not just "both eventually happened".
type orderedEventLog struct {
	mu     sync.Mutex
	events []string
}

func (l *orderedEventLog) record(event string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.events = append(l.events, event)
}

func (l *orderedEventLog) snapshot() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]string, len(l.events))
	copy(out, l.events)
	return out
}

// fakeSymbolResolver resolves exactly one canonical code (EURUSD, via its
// bare-code candidate) so SuggestSymbolMappings has something real to
// report — every other catalog entry is silently unresolved, matching a
// real broker that doesn't offer everything in the curated catalog.
type fakeSymbolResolver struct{}

func (fakeSymbolResolver) ResolveSymbol(ctx context.Context, brokerSymbol string) (domain.NormalizedSymbol, error) {
	if brokerSymbol != "EURUSD" {
		return domain.NormalizedSymbol{}, errors.New("fakeSymbolResolver: unknown symbol")
	}
	return domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX}, nil
}

func (fakeSymbolResolver) GetSymbolSpecification(ctx context.Context, symbol domain.NormalizedSymbol) (domain.SymbolSpec, error) {
	return domain.SymbolSpec{Symbol: symbol, BrokerSymbolName: "EURUSD"}, nil
}

type loggingMappingReporter struct{ log *orderedEventLog }

func (r loggingMappingReporter) SuggestSymbolMappings(ctx context.Context, brokerAccountID string, specs []domain.SymbolSpec) error {
	r.log.record("suggest")
	return nil
}

type loggingStatusReporter struct{ log *orderedEventLog }

func (r loggingStatusReporter) ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error {
	if status == "CONNECTED" {
		r.log.record("connected")
	}
	return nil
}

// TestConnectLocked_SuggestsSymbolMappingsBeforeReportingConnected proves
// TICKET-103's real ordering requirement, not just that both calls
// eventually happen: nectrix_plan/docs/07-auth-onboarding-broker-linking.md
// §7.5's flow diagram populates symbol_mappings suggestions BEFORE marking
// a broker_accounts row CONNECTED, so a user never sees "CONNECTED" with no
// suggestions yet to review.
func TestConnectLocked_SuggestsSymbolMappingsBeforeReportingConnected(t *testing.T) {
	log := &orderedEventLog{}
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(
		lister, newFakeCredentialFetcher(), loggingStatusReporter{log: log},
		fakeSymbolResolver{}, loggingMappingReporter{log: log},
		adapter, noopOnEvent, time.Hour, nil,
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.isConnected("acc-1") })
	waitFor(t, func() bool { return len(log.snapshot()) >= 2 })

	events := log.snapshot()
	if len(events) != 2 || events[0] != "suggest" || events[1] != "connected" {
		t.Fatalf("event order = %v, want [suggest connected]", events)
	}
}

// TICKET-106: HandleFor is the only broker_accounts.id -> ConnectionHandle
// registry this service has -- the new internal PlaceOrder/GetAccountSnapshot
// HTTP routes depend on it to resolve a remote caller's account ID.
func TestHandleFor_ReturnsConnectedHandle(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, time.Hour, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.isConnected("acc-1") })

	handle, ok := loop.HandleFor("acc-1")
	if !ok {
		t.Fatal("HandleFor(\"acc-1\") returned ok=false, want a connected handle")
	}
	if handle.AccountID != "acc-1" || handle.BrokerType != domain.BrokerTypeCTrader {
		t.Fatalf("HandleFor(\"acc-1\") = %+v, want AccountID=acc-1 BrokerType=CTRADER", handle)
	}
}

func TestHandleFor_UnknownAccount_ReturnsFalse(t *testing.T) {
	lister := &fakeLister{}
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, newFakeAdapter(), noopOnEvent, time.Hour, nil)

	if _, ok := loop.HandleFor("never-connected"); ok {
		t.Fatal("HandleFor for an unknown account returned ok=true, want false")
	}
}

func TestHandleFor_DisconnectedAccount_ReturnsFalse(t *testing.T) {
	lister := &fakeLister{accounts: []reconcile.BrokerAccountRef{{ID: "acc-1"}}}
	adapter := newFakeAdapter()
	loop := reconcile.New(lister, newFakeCredentialFetcher(), newFakeStatusReporter(), nil, nil, adapter, noopOnEvent, 20*time.Millisecond, nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	waitFor(t, func() bool { return adapter.isConnected("acc-1") })

	lister.setAccounts(nil)
	waitFor(t, func() bool { return adapter.connectedCount() == 0 })

	if _, ok := loop.HandleFor("acc-1"); ok {
		t.Fatal("HandleFor for a since-disconnected account returned ok=true, want false")
	}
}
