// Package ctrader is the real domain.BrokerAdapter implementation for
// cTrader — TICKET-101. It knows nothing about HTTP/Kafka/Postgres; it only
// ever speaks the cTrader Open API protocol (via internal/ctraderapi) and
// this platform's normalized domain types (packages/go-domain). Credential
// lookup, connection-status reporting, and event publishing are all the
// caller's responsibility, threaded in through this package's own small
// interfaces — matching domain.BrokerAdapter's own design (it never touches
// Postgres/Kafka directly either).
package ctrader

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/avison9/nectrix/broker-adapters/internal/ctraderapi"
	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

// reconnectBackoff — exponential, capped — matches the ticket's "persistent
// Protobuf/TLS connection with reconnect/backoff" AC. A cTrader session
// naturally drops (server restarts, network blips); the streaming
// subscription must survive that transparently.
var reconnectBackoff = []time.Duration{
	1 * time.Second, 2 * time.Second, 5 * time.Second, 10 * time.Second, 30 * time.Second,
}

// connection is the live state for one Connect()-ed broker_accounts row.
type connection struct {
	handle     domain.ConnectionHandle
	credential domain.BrokerCredentials

	mu       sync.Mutex
	client   *ctraderapi.Client
	healthy  bool
	lastSeen time.Time

	onEventMu sync.Mutex
	onEvent   func(context.Context, domain.NormalizedTradeEvent) error

	// spotMu/latestSpot cache the most recent bid/ask per symbolId this
	// connection is subscribed to — fed by handleEvent's spot-event branch,
	// read by unrealizedPnL. Passive: never blocks waiting for a tick, a
	// symbol with no cached quote yet just contributes 0 unrealized P&L
	// until its first tick arrives (see unrealizedPnL's doc-comment).
	spotMu     sync.RWMutex
	latestSpot map[int64]spotPrice

	cancel context.CancelFunc
	done   chan struct{}
}

type spotPrice struct {
	bid, ask float64
}

// CTraderAdapter implements domain.BrokerAdapter. One instance is shared
// across every connected broker_accounts row — ApplicationAuth (the
// platform's own registered cTrader app) is the same for all of them; only
// AccountAuth differs per row.
type CTraderAdapter struct {
	appClientID     string
	appClientSecret string
	demoHost        string
	liveHost        string
	logger          *slog.Logger

	// dial defaults to ctraderapi.Dial (a real TLS dial) — overridable via
	// WithDialFunc so tests can substitute an in-memory net.Pipe()-backed
	// fake cTrader server without any TLS listener.
	dial func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error)

	mu          sync.RWMutex
	connections map[string]*connection // handle.ID -> connection

	symbols *symbolCache
}

// Option customizes New — used by tests to point at a fake server instead
// of the real demo.ctraderapi.com/live.ctraderapi.com hosts.
type Option func(*CTraderAdapter)

// WithHosts overrides the demo/live connection targets (tests only — real
// callers get the real hosts by default).
func WithHosts(demoHost, liveHost string) Option {
	return func(a *CTraderAdapter) {
		a.demoHost = demoHost
		a.liveHost = liveHost
	}
}

// WithLogger overrides the default slog logger.
func WithLogger(logger *slog.Logger) Option {
	return func(a *CTraderAdapter) { a.logger = logger }
}

// WithDialFunc overrides how Connect establishes the underlying
// ctraderapi.Client — tests only, real callers get ctraderapi.Dial.
func WithDialFunc(dial func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error)) Option {
	return func(a *CTraderAdapter) { a.dial = dial }
}

// New builds a CTraderAdapter authenticating as the platform's own
// registered cTrader Open API application (CTRADER_CLIENT_ID/SECRET — see
// docs/07-auth-onboarding-broker-linking.md §7.6). clientID/clientSecret are
// never per-broker-account; they identify this platform's app to cTrader,
// shared across every linked account.
func New(clientID, clientSecret string, opts ...Option) *CTraderAdapter {
	a := &CTraderAdapter{
		appClientID:     clientID,
		appClientSecret: clientSecret,
		demoHost:        ctraderapi.DemoHost,
		liveHost:        ctraderapi.LiveHost,
		logger:          slog.Default(),
		dial:            ctraderapi.Dial,
		connections:     make(map[string]*connection),
		symbols:         newSymbolCache(),
	}
	for _, opt := range opts {
		opt(a)
	}
	return a
}

// Compile-time proof this really implements the interface — same
// convention as apps/copy-engine/internal/stubadapter.
var _ domain.BrokerAdapter = (*CTraderAdapter)(nil)

func (a *CTraderAdapter) BrokerType() domain.BrokerType {
	return domain.BrokerTypeCTrader
}

// Connect dials the correct host (demo/live, per credentials.IsLive),
// performs the real ApplicationAuth → AccountAuth handshake, and starts a
// background reconnect-on-drop loop that transparently re-establishes and
// re-authenticates the session — the caller's ConnectionHandle stays valid
// across a real reconnect; StreamTradeEvents' subscription survives it too.
func (a *CTraderAdapter) Connect(ctx context.Context, credentials domain.BrokerCredentials) (domain.ConnectionHandle, error) {
	client, err := a.dialAndAuth(ctx, credentials)
	if err != nil {
		return domain.ConnectionHandle{}, err
	}

	handle := domain.ConnectionHandle{
		ID:         uuid.NewString(),
		BrokerType: domain.BrokerTypeCTrader,
		AccountID:  credentials.AccountID,
	}

	connCtx, cancel := context.WithCancel(context.Background())
	conn := &connection{
		handle:     handle,
		credential: credentials,
		client:     client,
		healthy:    true,
		lastSeen:   time.Now(),
		latestSpot: make(map[int64]spotPrice),
		cancel:     cancel,
		done:       make(chan struct{}),
	}

	a.mu.Lock()
	a.connections[handle.ID] = conn
	a.mu.Unlock()

	go a.drainEvents(connCtx, conn)
	go a.reconnectLoop(connCtx, conn)

	// Both best-effort — a failure here doesn't fail Connect itself (the
	// account connection is real and usable regardless). Symbol list: see
	// symbolCache's doc-comment for why it's adapter-wide, not
	// per-connection. Spot subscription: GetAccountSnapshot's Equity is 0
	// unrealized-P&L (Balance only) for any position whose symbol hasn't
	// ticked yet — subscribing to already-open positions' symbols right
	// away gives real numbers as fast as possible after Connect.
	if err := a.populateSymbolCache(ctx, conn); err != nil {
		a.logger.Warn("ctrader: initial symbol list fetch failed", "accountId", credentials.AccountID, "error", err)
	}
	if err := a.subscribeOpenPositionSpots(ctx, conn); err != nil {
		a.logger.Warn("ctrader: initial spot subscription failed", "accountId", credentials.AccountID, "error", err)
	}

	return handle, nil
}

// subscribeOpenPositionSpots subscribes to live spot prices for every
// symbol the account currently has an open position in — see Connect's
// doc-comment for why.
func (a *CTraderAdapter) subscribeOpenPositionSpots(ctx context.Context, conn *connection) error {
	req := &openapi.ProtoOAReconcileReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	resp := &openapi.ProtoOAReconcileRes{}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_REQ), req, resp); err != nil {
		return fmt.Errorf("ctrader: reconcile for spot subscription: %w", err)
	}
	symbolIDs := make([]int64, 0, len(resp.GetPosition()))
	seen := make(map[int64]bool)
	for _, p := range resp.GetPosition() {
		id := p.GetTradeData().GetSymbolId()
		if !seen[id] {
			seen[id] = true
			symbolIDs = append(symbolIDs, id)
		}
	}
	if len(symbolIDs) == 0 {
		return nil
	}
	return a.subscribeSpots(ctx, conn, symbolIDs)
}

func (a *CTraderAdapter) subscribeSpots(ctx context.Context, conn *connection, symbolIDs []int64) error {
	sub := &openapi.ProtoOASubscribeSpotsReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID, SymbolId: symbolIDs}
	subResp := &openapi.ProtoOASubscribeSpotsRes{}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_SUBSCRIBE_SPOTS_REQ), sub, subResp); err != nil {
		return fmt.Errorf("ctrader: subscribe spots: %w", err)
	}
	return nil
}

func (a *CTraderAdapter) populateSymbolCache(ctx context.Context, conn *connection) error {
	req := &openapi.ProtoOASymbolsListReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	resp := &openapi.ProtoOASymbolsListRes{}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOLS_LIST_REQ), req, resp); err != nil {
		return fmt.Errorf("ctrader: list symbols: %w", err)
	}
	a.symbols.put(resp.GetSymbol())
	return nil
}

func (a *CTraderAdapter) dialAndAuth(ctx context.Context, credentials domain.BrokerCredentials) (*ctraderapi.Client, error) {
	host := a.demoHost
	if credentials.IsLive {
		host = a.liveHost
	}

	client, err := a.dial(ctx, host, a.logger)
	if err != nil {
		return nil, fmt.Errorf("ctrader: connect: %w", err)
	}
	if err := client.ApplicationAuth(ctx, a.appClientID, a.appClientSecret); err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("ctrader: application auth: %w", err)
	}
	if err := client.AccountAuth(ctx, credentials.CtidTraderAccountID, credentials.AccessToken); err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("ctrader: account auth: %w", err)
	}
	return client, nil
}

func (a *CTraderAdapter) Disconnect(ctx context.Context, handle domain.ConnectionHandle) error {
	a.mu.Lock()
	conn, ok := a.connections[handle.ID]
	if ok {
		delete(a.connections, handle.ID)
	}
	a.mu.Unlock()
	if !ok {
		return nil // already gone — Disconnect is idempotent
	}
	conn.cancel()
	<-conn.done
	conn.mu.Lock()
	defer conn.mu.Unlock()
	return conn.client.Close()
}

func (a *CTraderAdapter) HealthCheck(ctx context.Context, handle domain.ConnectionHandle) (domain.ConnectionHealth, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return domain.ConnectionHealth{}, err
	}
	conn.mu.Lock()
	defer conn.mu.Unlock()
	health := domain.ConnectionHealth{Connected: conn.healthy}
	if !conn.lastSeen.IsZero() {
		s := conn.lastSeen.Format(time.RFC3339)
		health.LastEventAt = &s
	}
	if !conn.healthy {
		health.Detail = "reauth required or connection lost — see reconnect loop logs"
	}
	return health, nil
}

func (a *CTraderAdapter) lookup(handle domain.ConnectionHandle) (*connection, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	conn, ok := a.connections[handle.ID]
	if !ok {
		return nil, fmt.Errorf("ctrader: unknown connection handle %s", handle.ID)
	}
	return conn, nil
}

// reconnectLoop watches for the underlying ctraderapi.Client's Done() signal
// (closed the moment its connection dies, for any reason) and re-dials with
// exponential backoff, re-running the full ApplicationAuth → AccountAuth
// handshake each time. A REAUTH_REQUIRED-shaped failure (bad/expired token)
// is NOT retried here — it's surfaced via conn.healthy=false for the
// caller's health-check/reporting loop to act on (Core App refreshes the
// token or flips connection_status).
func (a *CTraderAdapter) reconnectLoop(ctx context.Context, conn *connection) {
	defer close(conn.done)
	attempt := 0
	for {
		conn.mu.Lock()
		doneCh := conn.client.Done()
		conn.mu.Unlock()

		select {
		case <-ctx.Done():
			return
		case <-doneCh:
		}
		if ctx.Err() != nil {
			return
		}

		conn.mu.Lock()
		conn.healthy = false
		conn.mu.Unlock()

		delay := reconnectBackoff[min(attempt, len(reconnectBackoff)-1)]
		attempt++
		a.logger.Warn("ctrader: connection lost, reconnecting", "accountId", conn.credential.AccountID, "delay", delay)

		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return
		}

		client, err := a.dialAndAuth(ctx, conn.credential)
		if err != nil {
			a.logger.Error("ctrader: reconnect attempt failed", "accountId", conn.credential.AccountID, "error", err)
			continue
		}

		conn.mu.Lock()
		conn.client = client
		conn.healthy = true
		conn.lastSeen = time.Now()
		conn.mu.Unlock()
		attempt = 0

		go a.drainEvents(ctx, conn)
	}
}
