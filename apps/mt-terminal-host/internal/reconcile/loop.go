package reconcile

import (
	"context"
	"log/slog"
	"time"
)

// Loop owns the reconciliation cadence between Core App's desired MT5/MT4 terminal set and the
// real Kubernetes Deployments/Secrets that make it true.
type Loop struct {
	lister      Lister
	credentials CredentialFetcher
	provisioner Provisioner
	interval    time.Duration
	logger      *slog.Logger
}

func New(lister Lister, credentials CredentialFetcher, provisioner Provisioner, interval time.Duration, logger *slog.Logger) *Loop {
	if logger == nil {
		logger = slog.Default()
	}
	return &Loop{
		lister:      lister,
		credentials: credentials,
		provisioner: provisioner,
		interval:    interval,
		logger:      logger,
	}
}

// Run polls every interval until ctx is cancelled, reconciling once immediately on entry — a
// freshly-started provisioner shouldn't leave accounts without a terminal for a full interval
// before discovering which ones it should already be ensuring.
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
		l.logger.Error("reconcile: list mt broker accounts failed", "error", err)
		return
	}

	desired := make(map[string]AccountRef, len(accounts))
	for _, acc := range accounts {
		desired[acc.ID] = acc
	}

	actualIDs, err := l.provisioner.ListProvisionedAccountIDs(ctx)
	if err != nil {
		l.logger.Error("reconcile: list provisioned accounts failed", "error", err)
		return
	}
	actual := make(map[string]bool, len(actualIDs))
	for _, id := range actualIDs {
		actual[id] = true
	}

	// EnsureTerminal is idempotent (server-side apply) but is only called for accounts NOT yet
	// provisioned — credentials are only fetched (and thus a real password only decrypted/logged)
	// once per account's discovery, not every cycle for an account that already has a running
	// terminal.
	for id, account := range desired {
		if actual[id] {
			continue
		}
		creds, err := l.credentials.FetchTerminalCredentials(ctx, id)
		if err != nil {
			l.logger.Error("reconcile: fetch terminal credentials failed", "brokerAccountId", id, "error", err)
			continue
		}
		if err := l.provisioner.EnsureTerminal(ctx, account, creds); err != nil {
			l.logger.Error("reconcile: ensure terminal failed", "brokerAccountId", id, "error", err)
			continue
		}
		l.logger.Info("reconcile: terminal provisioned", "brokerAccountId", id, "platform", account.BrokerType)
	}

	for id := range actual {
		if _, ok := desired[id]; ok {
			continue
		}
		if err := l.provisioner.TeardownTerminal(ctx, id); err != nil {
			l.logger.Error("reconcile: teardown terminal failed", "brokerAccountId", id, "error", err)
			continue
		}
		l.logger.Info("reconcile: terminal torn down (no longer listed by core-app)", "brokerAccountId", id)
	}
}
