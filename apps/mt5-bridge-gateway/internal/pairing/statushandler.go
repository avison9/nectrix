package pairing

import (
	"context"
	"log/slog"
	"sync"

	domain "github.com/avison9/nectrix/go-domain"
)

// StatusReporter is Core App's existing connection-status endpoint contract
// — the exact one TICKET-101 built (and fixed a real missing-caller bug in;
// see apps/broker-adapters/internal/reconcile.StatusReporter), reused here
// unmodified.
type StatusReporter interface {
	ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error
}

// SymbolMappingReporter is Core App's internal endpoint contract for
// persisting auto-suggested symbol_mappings (TICKET-103) — the same
// contract apps/broker-adapters/internal/reconcile.SymbolMappingReporter
// declares, duplicated here rather than shared across module boundaries
// (this package has no dependency on broker-adapters, nor should it).
type SymbolMappingReporter interface {
	SuggestSymbolMappings(ctx context.Context, brokerAccountID string, specs []domain.SymbolSpec) error
}

// StatusHandler implements eabridge.SessionEventHandler by reporting a real
// EA session's arrival/departure straight to Core App — this is what
// actually flips a broker_accounts row from PENDING to CONNECTED (RegisterPairing,
// this package's own Loop, deliberately does NOT do this — see pairing.go's
// package doc).
type StatusHandler struct {
	reporter        StatusReporter
	mappingReporter SymbolMappingReporter
	logger          *slog.Logger

	mu        sync.RWMutex
	resolvers map[domain.BrokerType]domain.SymbolResolver
}

func NewStatusHandler(reporter StatusReporter, mappingReporter SymbolMappingReporter, logger *slog.Logger) *StatusHandler {
	if logger == nil {
		logger = slog.Default()
	}
	return &StatusHandler{reporter: reporter, mappingReporter: mappingReporter, logger: logger}
}

// SetSymbolResolvers wires the per-platform resolvers (TICKET-103) — called
// once from main() after both the eabridge.Server and the MT5/MT4 adapters
// are constructed, breaking what would otherwise be a circular construction
// dependency: the adapters need eaServer to exist first (via
// mtadapter.NewMT5(eaServer)), but eaServer's own StatusHandler needs the
// adapters to resolve symbols against. Must be called before eaServer's
// handler is registered on the mux, i.e. before any real EA traffic can
// arrive — the mutex is defensive (protects against a theoretical race, not
// because concurrent traffic is expected at wiring time), not a substitute
// for that ordering.
func (h *StatusHandler) SetSymbolResolvers(resolvers map[domain.BrokerType]domain.SymbolResolver) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.resolvers = resolvers
}

func (h *StatusHandler) OnSessionEstablished(ctx context.Context, brokerAccountID string, platform domain.BrokerType) {
	// TICKET-103: populate symbol_mappings suggestions BEFORE reporting
	// CONNECTED (nectrix_plan/docs/07-auth-onboarding-broker-linking.md
	// §7.5's intended fetch-SymbolSpec-before-CONNECTED ordering), so a
	// user never sees "CONNECTED" with no suggestions to review yet.
	// Requires eabridge/server.go's readLoop-before-OnSessionEstablished
	// fix (this same ticket) — RequestSymbolSpec blocks on a response only
	// a live readLoop can deliver.
	h.mu.RLock()
	resolver, ok := h.resolvers[platform]
	h.mu.RUnlock()
	if ok && h.mappingReporter != nil {
		specs := domain.SuggestSymbolMappings(ctx, resolver, symbolSuggestionConcurrency)
		if len(specs) > 0 {
			if err := h.mappingReporter.SuggestSymbolMappings(ctx, brokerAccountID, specs); err != nil {
				h.logger.Error("pairing: suggest symbol mappings failed", "brokerAccountId", brokerAccountID, "error", err)
			}
		}
	}

	if err := h.reporter.ReportConnectionStatus(ctx, brokerAccountID, "CONNECTED", ""); err != nil {
		h.logger.Error("pairing: report connection status (CONNECTED) failed", "brokerAccountId", brokerAccountID, "error", err)
	}
}

func (h *StatusHandler) OnSessionLost(ctx context.Context, brokerAccountID string) {
	if err := h.reporter.ReportConnectionStatus(ctx, brokerAccountID, "DISCONNECTED", "EA session closed"); err != nil {
		h.logger.Error("pairing: report connection status (DISCONNECTED) failed", "brokerAccountId", brokerAccountID, "error", err)
	}
}

const symbolSuggestionConcurrency = 8
