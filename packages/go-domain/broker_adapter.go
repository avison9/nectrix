package domain

import "context"

// BrokerCredentials carries whatever a BrokerAdapter needs to establish a
// connection for one broker_accounts row. Fields below AccountID are grouped
// per broker family — a given BrokerAdapter implementation only reads the
// fields its own BrokerType actually needs; the others stay zero-valued.
//
// TICKET-101 (cTrader): AccessToken/RefreshToken/CtidTraderAccountID are
// fetched, decrypted, at connect-time from apps/core-app's internal
// credentials endpoint (Go never decrypts these itself — see
// docs/17-security-architecture.md's single-encryption-authority design,
// TICKET-011's EnvelopeEncryptionService).
//
// TICKET-102 (MT5/MT4, EA-bridge strategy): Login/Server are the terminal's
// own trade-account login and server name, used to cross-check against what
// a connecting EA session reports (defense against a leaked pairing token
// being attached to the wrong account). APIToken carries the per-account
// pairing token — an opaque secret the user pastes into the EA's own input
// parameters at attach-time, NOT an OAuth-style bearer token; Connect()
// doesn't dial anywhere for these adapters, it registers what to match
// against when a real EA session arrives (see apps/mt5-bridge-gateway's
// internal/eabridge package).
type BrokerCredentials struct {
	BrokerType BrokerType
	AccountID  string // broker_accounts.id this connection represents
	Login      string
	Server     string
	APIToken   string

	// cTrader Open API OAuth 2.0 (docs/07-auth-onboarding-broker-linking.md §7.6).
	AccessToken         string
	RefreshToken        string
	CtidTraderAccountID int64
	// IsLive selects which cTrader host to dial — demo and live are
	// entirely separate connections/hosts, never mixed (confirmed via
	// cTrader's own docs). false for every account this ticket's ACs
	// exercise (demo-only).
	IsLive bool
}

// ConnectionHandle is the opaque handle returned by Connect and threaded
// through every subsequent call — the Go equivalent of the TS interface's
// ConnectionHandle (docs/04-architecture-overview.md §4.3).
type ConnectionHandle struct {
	ID         string
	BrokerType BrokerType
	AccountID  string
}

// ConnectionHealth is the result of a HealthCheck call.
type ConnectionHealth struct {
	Connected   bool
	LastEventAt *string // ISO-8601, nullable
	Detail      string
}

// SLTPChange describes a stop-loss/take-profit modification request.
type SLTPChange struct {
	SLPrice *float64
	TPPrice *float64
}

// Subscription represents an active StreamTradeEvents subscription. Close
// stops further delivery to the registered callback.
type Subscription interface {
	Close() error
}

// BrokerAdapter is the multi-broker abstraction the Copy Engine depends on —
// it never speaks cTrader/MT5 protocol directly, only this interface
// (docs/04-architecture-overview.md §4.3, method-for-method).
//
// Go adaptation of the conceptual TypeScript interface: every method threads
// context.Context and returns error (TS used Promise rejection); the
// StreamTradeEvents callback also returns error (TS's onEvent is
// void-returning) so pipeline processing failures have an idiomatic Go path
// back to the caller for logging/backpressure decisions, matching the
// Handler[T] shape already used in packages/event-contracts/go/eventconsumer.
type BrokerAdapter interface {
	BrokerType() BrokerType

	// Connection lifecycle
	Connect(ctx context.Context, credentials BrokerCredentials) (ConnectionHandle, error)
	Disconnect(ctx context.Context, handle ConnectionHandle) error
	HealthCheck(ctx context.Context, handle ConnectionHandle) (ConnectionHealth, error)

	// Read
	GetAccountSnapshot(ctx context.Context, handle ConnectionHandle) (AccountSnapshot, error)
	GetOpenPositions(ctx context.Context, handle ConnectionHandle) ([]NormalizedPosition, error)
	StreamTradeEvents(ctx context.Context, handle ConnectionHandle, onEvent func(context.Context, NormalizedTradeEvent) error) (Subscription, error)

	// Write (only for accounts enrolled as follower targets)
	PlaceOrder(ctx context.Context, handle ConnectionHandle, order NormalizedOrderRequest) (NormalizedOrderResult, error)
	ModifyPosition(ctx context.Context, handle ConnectionHandle, positionID string, changes SLTPChange) (NormalizedOrderResult, error)
	ClosePosition(ctx context.Context, handle ConnectionHandle, positionID string, volume *float64) (NormalizedOrderResult, error)

	// Metadata
	ResolveSymbol(ctx context.Context, brokerSymbol string) (NormalizedSymbol, error)
	GetSymbolSpecification(ctx context.Context, symbol NormalizedSymbol) (SymbolSpec, error)
}
