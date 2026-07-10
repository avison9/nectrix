package reconcile

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"
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
	byAccount map[string]TerminalCredentials
	fetches   int
}

func newFakeCredentialFetcher() *fakeCredentialFetcher {
	return &fakeCredentialFetcher{byAccount: make(map[string]TerminalCredentials)}
}

func (f *fakeCredentialFetcher) FetchTerminalCredentials(ctx context.Context, brokerAccountID string) (TerminalCredentials, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fetches++
	creds, ok := f.byAccount[brokerAccountID]
	if !ok {
		return TerminalCredentials{}, fmt.Errorf("no credentials for %s", brokerAccountID)
	}
	return creds, nil
}

func (f *fakeCredentialFetcher) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.fetches
}

type fakeProvisioner struct {
	mu          sync.Mutex
	provisioned map[string]AccountRef
	ensureCalls int
}

func newFakeProvisioner() *fakeProvisioner {
	return &fakeProvisioner{provisioned: make(map[string]AccountRef)}
}

func (f *fakeProvisioner) EnsureTerminal(ctx context.Context, account AccountRef, creds TerminalCredentials) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.ensureCalls++
	f.provisioned[account.ID] = account
	return nil
}

func (f *fakeProvisioner) TeardownTerminal(ctx context.Context, accountID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.provisioned, accountID)
	return nil
}

func (f *fakeProvisioner) ListProvisionedAccountIDs(ctx context.Context) ([]string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	ids := make([]string, 0, len(f.provisioned))
	for id := range f.provisioned {
		ids = append(ids, id)
	}
	return ids, nil
}

func (f *fakeProvisioner) has(id string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	_, ok := f.provisioned[id]
	return ok
}

func (f *fakeProvisioner) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.provisioned)
}

func TestReconcileOnce_ProvisionsNewAccounts(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{
		{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"},
		{ID: "acct-2", Status: "PENDING", BrokerType: "MT4"},
	})
	creds := newFakeCredentialFetcher()
	creds.byAccount["acct-1"] = TerminalCredentials{Login: "111", Password: "p1", Server: "S1", PairingToken: "tok-1"}
	creds.byAccount["acct-2"] = TerminalCredentials{Login: "222", Password: "p2", Server: "S2", PairingToken: "tok-2"}
	provisioner := newFakeProvisioner()

	loop := New(lister, creds, provisioner, time.Hour, nil)
	loop.reconcileOnce(context.Background())

	if !provisioner.has("acct-1") || !provisioner.has("acct-2") {
		t.Fatalf("expected both accounts provisioned, got %+v", provisioner.provisioned)
	}
}

func TestReconcileOnce_DoesNotRefetchCredentialsForAlreadyProvisionedAccounts(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"}})
	creds := newFakeCredentialFetcher()
	creds.byAccount["acct-1"] = TerminalCredentials{Login: "111", Password: "p1", Server: "S1", PairingToken: "tok-1"}
	provisioner := newFakeProvisioner()

	loop := New(lister, creds, provisioner, time.Hour, nil)
	loop.reconcileOnce(context.Background())
	loop.reconcileOnce(context.Background())
	loop.reconcileOnce(context.Background())

	if got := creds.fetchCount(); got != 1 {
		t.Fatalf("expected exactly 1 credential fetch across 3 reconcile cycles, got %d", got)
	}
	if got := provisioner.ensureCalls; got != 1 {
		t.Fatalf("expected exactly 1 EnsureTerminal call (not re-provisioning an already-provisioned account), got %d", got)
	}
}

func TestReconcileOnce_TearsDownAccountsNoLongerListed(t *testing.T) {
	lister := &fakeLister{}
	lister.setAccounts([]AccountRef{{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"}})
	creds := newFakeCredentialFetcher()
	creds.byAccount["acct-1"] = TerminalCredentials{Login: "1", Password: "p", Server: "S", PairingToken: "t"}
	provisioner := newFakeProvisioner()

	loop := New(lister, creds, provisioner, time.Hour, nil)
	loop.reconcileOnce(context.Background())
	if !provisioner.has("acct-1") {
		t.Fatalf("expected acct-1 provisioned after first reconcile")
	}

	lister.setAccounts(nil) // account unlinked / no longer listed by core-app
	loop.reconcileOnce(context.Background())

	if provisioner.has("acct-1") {
		t.Fatalf("expected acct-1 to be torn down once no longer listed")
	}
	if got := provisioner.count(); got != 0 {
		t.Fatalf("expected 0 provisioned accounts, got %d", got)
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
	creds.byAccount["acct-ok"] = TerminalCredentials{Login: "1", Password: "p", Server: "S", PairingToken: "t"}
	provisioner := newFakeProvisioner()

	loop := New(lister, creds, provisioner, time.Hour, nil)
	loop.reconcileOnce(context.Background())

	if !provisioner.has("acct-ok") {
		t.Fatalf("expected acct-ok to be provisioned despite acct-broken's failure")
	}
	if provisioner.has("acct-broken") {
		t.Fatalf("acct-broken should not be provisioned when its credential fetch failed")
	}
	if got := provisioner.count(); got != 1 {
		t.Fatalf("expected exactly 1 provisioned account, got %d: %+v", got, provisioner.provisioned)
	}
}

func TestRun_StopsOnContextCancellation(t *testing.T) {
	lister := &fakeLister{}
	provisioner := newFakeProvisioner()
	loop := New(lister, newFakeCredentialFetcher(), provisioner, 10*time.Millisecond, nil)

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
