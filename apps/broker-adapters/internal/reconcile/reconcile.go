// Package reconcile is the poll loop that keeps this service's live
// connection set in sync with Core App's view of which broker_accounts
// should be connected. Go never touches Postgres directly (this ticket's own
// credential-handoff decision — Core App is the single source of truth on
// broker_accounts), so this loop's only inputs are two Core-App-owned
// internal endpoints (task #119, not yet built; this package is written and
// fully tested against the documented contract, ready the moment those
// endpoints exist):
//   - GET /internal/broker-accounts?status=&brokerType= — lightweight
//     listing (id + status only), polled every interval.
//   - GET /internal/broker-accounts/credentials/{id} — decrypted
//     {accessToken, refreshToken, ctidTraderAccountId}, fetched only once
//     per NEWLY-discovered account (not every poll) — both to avoid
//     needlessly re-transmitting decrypted secrets on every cycle, and
//     because an already-connected account doesn't need them again.
//
// Every connected account subscribes to trade events, not just "master"
// accounts — this package has no notion of copy relationships (that's Copy
// Engine's job, once it consumes trade-signals from Kafka); publishing a
// real event for every connected account and letting downstream consumers
// filter by relevance keeps broker-adapters itself relationship-agnostic,
// matching "cross-service side effects flow through Kafka" instead of
// broker-adapters needing to know Core App's copy_relationships schema.
package reconcile

import (
	"context"
	"log/slog"
	"sync"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
)

// BrokerAccountRef is one row from the lightweight listing endpoint.
type BrokerAccountRef struct {
	ID     string
	Status string // observability only — the listing call itself already filters by status
}

// BrokerAccountCredentials is the decrypted secret bundle for one account,
// from the per-account credentials endpoint.
type BrokerAccountCredentials struct {
	AccessToken         string
	RefreshToken        string
	CtidTraderAccountID int64
	IsLive              bool
}

// BrokerAccountLister is the Core-App-owned internal listing endpoint
// contract this loop polls.
type BrokerAccountLister interface {
	ListBrokerAccounts(ctx context.Context) ([]BrokerAccountRef, error)
}

// CredentialFetcher is the Core-App-owned per-account credentials endpoint
// contract, called only for newly-discovered accounts.
type CredentialFetcher interface {
	FetchCredentials(ctx context.Context, brokerAccountID string) (BrokerAccountCredentials, error)
}

// StatusReporter is the Core-App-owned connection-status endpoint contract —
// the other half of task #119's third internal endpoint. Discovered missing
// by hand during this ticket's real live-verification pass: a real account
// connected successfully but its broker_accounts row stayed PENDING forever,
// because nothing ever called this. Best-effort: a reporting failure is
// logged, never treated as a reason to tear down an otherwise-healthy
// connection.
type StatusReporter interface {
	ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error
}

// SymbolMappingReporter is the Core-App-owned internal endpoint contract for
// persisting auto-suggested symbol_mappings (TICKET-103) — a POST of
// whatever real SymbolSpecs domain.SuggestSymbolMappings managed to resolve
// against the broker's own symbol catalog.
type SymbolMappingReporter interface {
	SuggestSymbolMappings(ctx context.Context, brokerAccountID string, specs []domain.SymbolSpec) error
}

// Adapter is the subset of domain.BrokerAdapter this loop needs — narrowed
// so tests can substitute a fake without implementing every BrokerAdapter
// method.
type Adapter interface {
	Connect(ctx context.Context, credentials domain.BrokerCredentials) (domain.ConnectionHandle, error)
	Disconnect(ctx context.Context, handle domain.ConnectionHandle) error
	StreamTradeEvents(ctx context.Context, handle domain.ConnectionHandle, onEvent func(context.Context, domain.NormalizedTradeEvent) error) (domain.Subscription, error)
}

type connectedAccount struct {
	handle domain.ConnectionHandle
	sub    domain.Subscription
}

// Loop owns the live connected-account set, keyed by broker_accounts.id.
type Loop struct {
	lister          BrokerAccountLister
	credentials     CredentialFetcher
	statusReporter  StatusReporter
	symbolResolver  domain.SymbolResolver
	mappingReporter SymbolMappingReporter
	adapter         Adapter
	onEvent         func(context.Context, domain.NormalizedTradeEvent) error
	interval        time.Duration
	logger          *slog.Logger

	mu        sync.Mutex
	connected map[string]connectedAccount
}

func New(lister BrokerAccountLister, credentials CredentialFetcher, statusReporter StatusReporter, symbolResolver domain.SymbolResolver, mappingReporter SymbolMappingReporter, adapter Adapter, onEvent func(context.Context, domain.NormalizedTradeEvent) error, interval time.Duration, logger *slog.Logger) *Loop {
	if logger == nil {
		logger = slog.Default()
	}
	return &Loop{
		lister:          lister,
		credentials:     credentials,
		statusReporter:  statusReporter,
		symbolResolver:  symbolResolver,
		mappingReporter: mappingReporter,
		adapter:         adapter,
		onEvent:         onEvent,
		interval:        interval,
		logger:          logger,
		connected:       make(map[string]connectedAccount),
	}
}

// Run polls every interval until ctx is cancelled, reconciling once
// immediately on entry rather than waiting for the first tick — a fresh
// process shouldn't sit with zero connections for a full interval before
// discovering the accounts it should already be serving.
func (l *Loop) Run(ctx context.Context) {
	l.reconcileOnce(ctx)
	ticker := time.NewTicker(l.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			l.disconnectAll(context.Background())
			return
		case <-ticker.C:
			l.reconcileOnce(ctx)
		}
	}
}

func (l *Loop) reconcileOnce(ctx context.Context) {
	accounts, err := l.lister.ListBrokerAccounts(ctx)
	if err != nil {
		l.logger.Error("reconcile: list broker accounts failed", "error", err)
		return
	}

	desired := make(map[string]bool, len(accounts))
	for _, acc := range accounts {
		desired[acc.ID] = true
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	for id, conn := range l.connected {
		if !desired[id] {
			l.disconnectLocked(ctx, id, conn)
		}
	}

	for id := range desired {
		if _, ok := l.connected[id]; ok {
			continue
		}
		l.connectLocked(ctx, id)
	}
}

func (l *Loop) connectLocked(ctx context.Context, id string) {
	creds, err := l.credentials.FetchCredentials(ctx, id)
	if err != nil {
		l.logger.Error("reconcile: fetch credentials failed", "brokerAccountId", id, "error", err)
		return
	}

	handle, err := l.adapter.Connect(ctx, domain.BrokerCredentials{
		BrokerType:          domain.BrokerTypeCTrader,
		AccountID:           id,
		AccessToken:         creds.AccessToken,
		RefreshToken:        creds.RefreshToken,
		CtidTraderAccountID: creds.CtidTraderAccountID,
		IsLive:              creds.IsLive,
	})
	if err != nil {
		l.logger.Error("reconcile: connect failed", "brokerAccountId", id, "error", err)
		l.reportStatus(ctx, id, "REAUTH_REQUIRED", err.Error())
		return
	}

	sub, err := l.adapter.StreamTradeEvents(ctx, handle, l.onEvent)
	if err != nil {
		l.logger.Error("reconcile: stream trade events failed", "brokerAccountId", id, "error", err)
		_ = l.adapter.Disconnect(ctx, handle)
		l.reportStatus(ctx, id, "REAUTH_REQUIRED", err.Error())
		return
	}

	l.connected[id] = connectedAccount{handle: handle, sub: sub}
	l.logger.Info("reconcile: connected broker account", "brokerAccountId", id)
	// TICKET-103: populate symbol_mappings suggestions BEFORE reporting
	// CONNECTED (nectrix_plan/docs/07-auth-onboarding-broker-linking.md
	// §7.5's intended fetch-SymbolSpec-before-CONNECTED ordering), so a
	// user never sees "CONNECTED" with no suggestions to review yet.
	l.suggestSymbolMappingsLocked(ctx, id)
	l.reportStatus(ctx, id, "CONNECTED", "")
}

const symbolSuggestionConcurrency = 8

// suggestSymbolMappingsLocked is best-effort like reportStatus -- never
// blocks or reverses a connection that already succeeded, and a broker
// with no live connection to actually probe (symbolResolver unset, e.g. in
// older test wiring) is silently skipped.
func (l *Loop) suggestSymbolMappingsLocked(ctx context.Context, id string) {
	if l.symbolResolver == nil || l.mappingReporter == nil {
		return
	}
	specs := domain.SuggestSymbolMappings(ctx, l.symbolResolver, symbolSuggestionConcurrency)
	if len(specs) == 0 {
		return
	}
	if err := l.mappingReporter.SuggestSymbolMappings(ctx, id, specs); err != nil {
		l.logger.Error("reconcile: suggest symbol mappings failed", "brokerAccountId", id, "error", err)
	}
}

func (l *Loop) disconnectLocked(ctx context.Context, id string, conn connectedAccount) {
	_ = conn.sub.Close()
	_ = l.adapter.Disconnect(ctx, conn.handle)
	delete(l.connected, id)
	l.logger.Info("reconcile: disconnected broker account (no longer desired)", "brokerAccountId", id)
	l.reportStatus(ctx, id, "DISCONNECTED", "no longer listed by core-app")
}

// reportStatus is best-effort — a failure to report never tears down (or
// blocks establishing) an otherwise-real connection/disconnection; it's just
// logged so the discrepancy is visible.
func (l *Loop) reportStatus(ctx context.Context, id, status, detail string) {
	if err := l.statusReporter.ReportConnectionStatus(ctx, id, status, detail); err != nil {
		l.logger.Error("reconcile: report connection status failed", "brokerAccountId", id, "status", status, "error", err)
	}
}

func (l *Loop) disconnectAll(ctx context.Context) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for id, conn := range l.connected {
		l.disconnectLocked(ctx, id, conn)
	}
}

// HandleFor returns the live, connected domain.ConnectionHandle for
// brokerAccountID, if this loop currently has one -- the only
// broker_accounts.id -> ConnectionHandle registry this service has. This is
// the "once it consumes trade-signals from Kafka" moment this package's own
// doc comment anticipated: TICKET-106's internal PlaceOrder/GetAccountSnapshot
// HTTP routes use this to resolve a remote caller's broker_account_id into
// the handle domain.BrokerAdapter's own methods require.
func (l *Loop) HandleFor(brokerAccountID string) (domain.ConnectionHandle, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	conn, ok := l.connected[brokerAccountID]
	return conn.handle, ok
}
