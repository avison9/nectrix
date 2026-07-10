package pairing

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"

	domain "github.com/avison9/nectrix/go-domain"
)

type fakeLister struct {
	mu       sync.Mutex
	accounts []AccountRef
}

func (f *fakeLister) ListMtBrokerAccounts(ctx context.Context) ([]AccountRef, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]AccountRef, len(f.accounts))
	copy(out, f.accounts)
	return out, nil
}

func (f *fakeLister) setAccounts(accounts []AccountRef) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.accounts = accounts
}

type fakeCredentialFetcher struct {
	mu        sync.Mutex
	byAccount map[string]MtCredentials
	fetches   int
}

func newFakeCredentialFetcher() *fakeCredentialFetcher {
	return &fakeCredentialFetcher{byAccount: make(map[string]MtCredentials)}
}

func (f *fakeCredentialFetcher) FetchMtCredentials(ctx context.Context, brokerAccountID string) (MtCredentials, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fetches++
	creds, ok := f.byAccount[brokerAccountID]
	if !ok {
		return MtCredentials{}, fmt.Errorf("no credentials for %s", brokerAccountID)
	}
	return creds, nil
}

func (f *fakeCredentialFetcher) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.fetches
}

type fakeRegistrar struct {
	mu         sync.Mutex
	registered map[string]eabridge.PairingInfo
}

func newFakeRegistrar() *fakeRegistrar {
	return &fakeRegistrar{registered: make(map[string]eabridge.PairingInfo)}
}

func (f *fakeRegistrar) RegisterPairing(token string, info eabridge.PairingInfo) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.registered[token] = info
}

func (f *fakeRegistrar) UnregisterPairing(token string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.registered, token)
}

func (f *fakeRegistrar) has(token string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	_, ok := f.registered[token]
	return ok
}

func (f *fakeRegistrar) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.registered)
}

func TestReconcileOnce_RegistersNewAccounts(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{
		{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"},
		{ID: "acct-2", Status: "PENDING", BrokerType: "MT4"},
	})
	creds := newFakeCredentialFetcher()
	creds.byAccount["acct-1"] = MtCredentials{Login: "111", Server: "S1", PairingToken: "tok-1"}
	creds.byAccount["acct-2"] = MtCredentials{Login: "222", Server: "S2", PairingToken: "tok-2"}
	registrar := newFakeRegistrar()

	loop := New(lister, creds, registrar, time.Hour, nil)
	loop.reconcileOnce(context.Background())

	if !registrar.has("tok-1") || !registrar.has("tok-2") {
		t.Fatalf("expected both pairing tokens registered, got %+v", registrar.registered)
	}
	info := registrar.registered["tok-1"]
	if info.BrokerAccountID != "acct-1" || info.ExpectedLogin != "111" || info.ExpectedServer != "S1" || info.Platform != domain.BrokerTypeMT5 {
		t.Fatalf("unexpected pairing info for tok-1: %+v", info)
	}
}

func TestReconcileOnce_DoesNotRefetchAlreadyRegisteredAccounts(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"}})
	creds := newFakeCredentialFetcher()
	creds.byAccount["acct-1"] = MtCredentials{Login: "111", Server: "S1", PairingToken: "tok-1"}
	registrar := newFakeRegistrar()

	loop := New(lister, creds, registrar, time.Hour, nil)
	loop.reconcileOnce(context.Background())
	loop.reconcileOnce(context.Background())
	loop.reconcileOnce(context.Background())

	if got := creds.fetchCount(); got != 1 {
		t.Fatalf("expected exactly 1 credential fetch across 3 reconcile cycles, got %d", got)
	}
}

func TestReconcileOnce_UnregistersRemovedAccounts(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"}})
	creds := newFakeCredentialFetcher()
	creds.byAccount["acct-1"] = MtCredentials{Login: "111", Server: "S1", PairingToken: "tok-1"}
	registrar := newFakeRegistrar()

	loop := New(lister, creds, registrar, time.Hour, nil)
	loop.reconcileOnce(context.Background())
	if !registrar.has("tok-1") {
		t.Fatalf("expected tok-1 registered after first reconcile")
	}

	lister.setAccounts(nil) // account unlinked / no longer listed by core-app
	loop.reconcileOnce(context.Background())

	if registrar.has("tok-1") {
		t.Fatalf("expected tok-1 to be unregistered once no longer listed")
	}
	if got := registrar.count(); got != 0 {
		t.Fatalf("expected 0 registered tokens, got %d", got)
	}
}

func TestReconcileOnce_CredentialFetchFailureDoesNotBlockOtherAccounts(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{
		{ID: "acct-broken", Status: "PENDING", BrokerType: "MT5"},
		{ID: "acct-ok", Status: "PENDING", BrokerType: "MT4"},
	})
	creds := newFakeCredentialFetcher()
	// Deliberately no entry for acct-broken.
	creds.byAccount["acct-ok"] = MtCredentials{Login: "1", Server: "S", PairingToken: "tok-ok"}
	registrar := newFakeRegistrar()

	loop := New(lister, creds, registrar, time.Hour, nil)
	loop.reconcileOnce(context.Background())

	if !registrar.has("tok-ok") {
		t.Fatalf("expected acct-ok's token to be registered despite acct-broken's failure")
	}
	if got := registrar.count(); got != 1 {
		t.Fatalf("expected exactly 1 registered token, got %d: %+v", got, registrar.registered)
	}
}

func TestRun_StopsOnContextCancellation(t *testing.T) {
	lister := &fakeLister{}
	registrar := newFakeRegistrar()
	loop := New(lister, newFakeCredentialFetcher(), registrar, 10*time.Millisecond, nil)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		loop.Run(ctx)
		close(done)
	}()

	time.Sleep(30 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("Run did not return after context cancellation")
	}
}

// --- StatusHandler -----------------------------------------------------------

type fakeStatusReporter struct {
	mu       sync.Mutex
	reported []reportedStatus
	failNext bool
}

type reportedStatus struct {
	brokerAccountID, status, detail string
}

func (f *fakeStatusReporter) ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failNext {
		f.failNext = false
		return fmt.Errorf("simulated failure")
	}
	f.reported = append(f.reported, reportedStatus{brokerAccountID, status, detail})
	return nil
}

func (f *fakeStatusReporter) statusesFor(id string) []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []string
	for _, r := range f.reported {
		if r.brokerAccountID == id {
			out = append(out, r.status)
		}
	}
	return out
}

func TestStatusHandler_ReportsConnectedAndDisconnected(t *testing.T) {
	reporter := &fakeStatusReporter{}
	handler := NewStatusHandler(reporter, nil)

	handler.OnSessionEstablished(context.Background(), "acct-1", domain.BrokerTypeMT5)
	handler.OnSessionLost(context.Background(), "acct-1")

	statuses := reporter.statusesFor("acct-1")
	if len(statuses) != 2 || statuses[0] != "CONNECTED" || statuses[1] != "DISCONNECTED" {
		t.Fatalf("unexpected statuses: %v", statuses)
	}
}

func TestStatusHandler_ReportFailureDoesNotPanic(t *testing.T) {
	reporter := &fakeStatusReporter{failNext: true}
	handler := NewStatusHandler(reporter, nil)

	// Must not panic — a reporting failure is best-effort/logged only,
	// mirroring reconcile.Loop's own reportStatus contract.
	handler.OnSessionEstablished(context.Background(), "acct-1", domain.BrokerTypeMT4)

	if statuses := reporter.statusesFor("acct-1"); len(statuses) != 0 {
		t.Fatalf("expected no successful report recorded, got %v", statuses)
	}
}
