// Package reconcile is the poll → reconcile loop that keeps a Kubernetes namespace's set of
// Nectrix-hosted MT5/MT4 terminal Deployments in sync with Core App's view of which broker_accounts
// need one — the terminal-provisioning counterpart of apps/mt5-bridge-gateway's internal/pairing.
//
// Unlike that package (which reconciles an in-memory pairing-token map, since the gateway's own
// process restarting loses nothing durable — a real EA just reconnects and re-hellos), this loop
// reconciles against the Kubernetes API itself: "actual" is read fresh from the cluster every
// cycle (Deployments/Secrets labeled nectrix.io/broker-account-id), never tracked in an in-memory
// map. Kubernetes is the durable source of truth here, so a provisioner restart never drifts from
// real cluster state.
package reconcile

import "context"

// AccountRef is one row from Core App's existing broker-accounts listing endpoint (the same
// unmodified endpoint apps/mt5-bridge-gateway's internal/pairing already polls), plus the
// brokerType this loop already knows it queried for.
type AccountRef struct {
	ID         string
	Status     string
	BrokerType string // "MT5" or "MT4"
}

// TerminalCredentials is the decrypted secret bundle for one account, from the new
// mt-terminal-credentials endpoint — the ONE place in this whole system a real plaintext broker
// password exists outside Core App's own JVM.
type TerminalCredentials struct {
	Login        string
	Password     string
	Server       string
	PairingToken string
}

// Lister is Core App's existing broker-accounts listing endpoint contract.
type Lister interface {
	ListMtBrokerAccounts(ctx context.Context) ([]AccountRef, error)
}

// CredentialFetcher is the new mt-terminal-credentials endpoint contract, called only for
// newly-discovered accounts that don't already have a running terminal — a terminal's credentials
// don't change while it stays PENDING/CONNECTED, so there's no need to refetch (and re-log) a real
// password every poll cycle.
type CredentialFetcher interface {
	FetchTerminalCredentials(ctx context.Context, brokerAccountID string) (TerminalCredentials, error)
}

// Provisioner is the subset of internal/k8sprovision.Provisioner this loop drives.
type Provisioner interface {
	// EnsureTerminal is idempotent — safe to call every cycle for an already-provisioned account
	// (server-side apply), not just once at discovery.
	EnsureTerminal(ctx context.Context, account AccountRef, creds TerminalCredentials) error
	TeardownTerminal(ctx context.Context, accountID string) error
	// ListProvisionedAccountIDs reads the real, current set of broker_accounts.id values that
	// have a live Deployment in the cluster right now — Kubernetes itself is "actual," not an
	// in-memory map (see package doc).
	ListProvisionedAccountIDs(ctx context.Context) ([]string, error)
}
