// Package pairing is the MT5/MT4 counterpart of apps/broker-adapters'
// internal/reconcile — but where that package's Loop actively dials cTrader
// (Connect + StreamTradeEvents), this one never dials anywhere: MT5/MT4 EAs
// dial INTO internal/eabridge.Server themselves. This loop's only job is
// keeping the Server's pairing-token registry in sync with whatever Core App
// currently lists as PENDING/CONNECTED MT5/MT4 broker_accounts, so that when
// a real EA session presents a token, the Server already knows which
// account it belongs to (see this ticket's plan: "the pairing-token
// discovery loop... builds an in-memory pairingToken -> {brokerAccountId,
// expectedLogin, expectedServer} map").
//
// Actual CONNECTED/DISCONNECTED status reporting back to Core App is NOT
// this loop's job — that happens the moment a real EA session is
// established/lost, via eabridge.Server's SessionEventHandler (see
// statushandler.go), reusing the exact StatusReporter contract and endpoint
// TICKET-101's reconcile.Loop already built.
package pairing

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"

	domain "github.com/avison9/nectrix/go-domain"
)

// AccountRef is one row from Core App's existing listing endpoint, plus the
// brokerType this loop already knows it queried for (the endpoint's own
// response doesn't carry it — see internal/coreappclient).
type AccountRef struct {
	ID         string
	Status     string
	BrokerType string // "MT5" or "MT4"
}

// MtCredentials is the decrypted secret bundle for one MT5/MT4 account, from
// the new mt-credentials endpoint (task #132).
type MtCredentials struct {
	Login        string
	Server       string
	PairingToken string
}

// Lister is Core App's existing broker-accounts listing endpoint contract,
// called once per platform (it only ever filters by a single brokerType —
// see BrokerAccountInternalController, unmodified from TICKET-101).
type Lister interface {
	ListMtBrokerAccounts(ctx context.Context) ([]AccountRef, error)
}

// CredentialFetcher is the new mt-credentials endpoint contract, called only
// for newly-discovered accounts (a pairing token doesn't change while an
// account stays PENDING/CONNECTED, so there's no need to refetch it every
// poll cycle).
type CredentialFetcher interface {
	FetchMtCredentials(ctx context.Context, brokerAccountID string) (MtCredentials, error)
}

// Registrar is the subset of *eabridge.Server this loop drives.
type Registrar interface {
	RegisterPairing(token string, info eabridge.PairingInfo)
	UnregisterPairing(token string)
}

// Loop owns the currently-registered pairing-token set, keyed by
// broker_accounts.id so a removed/rotated account's stale token can be
// found and unregistered.
type Loop struct {
	lister      Lister
	credentials CredentialFetcher
	registrar   Registrar
	interval    time.Duration
	logger      *slog.Logger

	mu              sync.Mutex
	tokensByAccount map[string]string
}

func New(lister Lister, credentials CredentialFetcher, registrar Registrar, interval time.Duration, logger *slog.Logger) *Loop {
	if logger == nil {
		logger = slog.Default()
	}
	return &Loop{
		lister:          lister,
		credentials:     credentials,
		registrar:       registrar,
		interval:        interval,
		logger:          logger,
		tokensByAccount: make(map[string]string),
	}
}

// Run polls every interval until ctx is cancelled, reconciling once
// immediately on entry — a freshly-started gateway shouldn't sit unable to
// accept any EA connection for a full interval before discovering which
// accounts it should already be ready to pair.
func (l *Loop) Run(ctx context.Context) {
	l.reconcileOnce(ctx)
	ticker := time.NewTicker(l.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			l.reconcileOnce(ctx)
		}
	}
}

func (l *Loop) reconcileOnce(ctx context.Context) {
	accounts, err := l.lister.ListMtBrokerAccounts(ctx)
	if err != nil {
		l.logger.Error("pairing: list mt broker accounts failed", "error", err)
		return
	}

	desired := make(map[string]bool, len(accounts))

	l.mu.Lock()
	defer l.mu.Unlock()

	for _, acc := range accounts {
		desired[acc.ID] = true
		if _, alreadyRegistered := l.tokensByAccount[acc.ID]; alreadyRegistered {
			continue
		}

		creds, err := l.credentials.FetchMtCredentials(ctx, acc.ID)
		if err != nil {
			l.logger.Error("pairing: fetch mt credentials failed", "brokerAccountId", acc.ID, "error", err)
			continue
		}

		l.registrar.RegisterPairing(creds.PairingToken, eabridge.PairingInfo{
			BrokerAccountID: acc.ID,
			ExpectedLogin:   creds.Login,
			ExpectedServer:  creds.Server,
			Platform:        domain.BrokerType(acc.BrokerType),
		})
		l.tokensByAccount[acc.ID] = creds.PairingToken
		l.logger.Info("pairing: registered pairing token", "brokerAccountId", acc.ID, "platform", acc.BrokerType)
	}

	for id, token := range l.tokensByAccount {
		if !desired[id] {
			l.registrar.UnregisterPairing(token)
			delete(l.tokensByAccount, id)
			l.logger.Info("pairing: unregistered pairing token (no longer listed by core-app)", "brokerAccountId", id)
		}
	}
}
