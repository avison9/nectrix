package pairing

import (
	"context"
	"log/slog"

	domain "github.com/avison9/nectrix/go-domain"
)

// StatusReporter is Core App's existing connection-status endpoint contract
// — the exact one TICKET-101 built (and fixed a real missing-caller bug in;
// see apps/broker-adapters/internal/reconcile.StatusReporter), reused here
// unmodified.
type StatusReporter interface {
	ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error
}

// StatusHandler implements eabridge.SessionEventHandler by reporting a real
// EA session's arrival/departure straight to Core App — this is what
// actually flips a broker_accounts row from PENDING to CONNECTED (RegisterPairing,
// this package's own Loop, deliberately does NOT do this — see pairing.go's
// package doc).
type StatusHandler struct {
	reporter StatusReporter
	logger   *slog.Logger
}

func NewStatusHandler(reporter StatusReporter, logger *slog.Logger) *StatusHandler {
	if logger == nil {
		logger = slog.Default()
	}
	return &StatusHandler{reporter: reporter, logger: logger}
}

func (h *StatusHandler) OnSessionEstablished(ctx context.Context, brokerAccountID string, platform domain.BrokerType) {
	if err := h.reporter.ReportConnectionStatus(ctx, brokerAccountID, "CONNECTED", ""); err != nil {
		h.logger.Error("pairing: report connection status (CONNECTED) failed", "brokerAccountId", brokerAccountID, "error", err)
	}
}

func (h *StatusHandler) OnSessionLost(ctx context.Context, brokerAccountID string) {
	if err := h.reporter.ReportConnectionStatus(ctx, brokerAccountID, "DISCONNECTED", "EA session closed"); err != nil {
		h.logger.Error("pairing: report connection status (DISCONNECTED) failed", "brokerAccountId", brokerAccountID, "error", err)
	}
}
