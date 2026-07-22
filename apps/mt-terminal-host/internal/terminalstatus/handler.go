// Package terminalstatus exposes GET /internal/terminals/status — TICKET-123's read-only
// pod-health endpoint, called by Core App (the reverse direction from every other internal route
// in this codebase: every other /internal/** caller anywhere in this monorepo has a Go service
// calling INTO core-app, not the other way around — this is the first time core-app is the HTTP
// client). Shared-secret-protected the same way apps/broker-adapters' own internalapi package
// already establishes (X-Internal-Service-Token, constant-time compare) — duplicated rather than
// extracted into a shared package, same "no shared infra module, each service owns its own copy"
// precedent that package's own comment already set when mt5-bridge-gateway independently
// duplicated this exact pattern.
package terminalstatus

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/avison9/nectrix/mt-terminal-host/internal/k8sprovision"
)

// PodStatusLister is the one method this package needs from *k8sprovision.Provisioner — a narrow
// interface so tests can substitute a fake instead of a real Kubernetes clientset.
type PodStatusLister interface {
	ListTerminalPodStatuses(ctx context.Context) ([]k8sprovision.TerminalPodStatus, error)
}

// terminalStatusWire is TerminalPodStatus's own wire shape — camelCase JSON, LastTransitionTime as
// RFC3339 (never a raw time.Time, whose default JSON encoding a Java client shouldn't have to
// special-case).
type terminalStatusWire struct {
	BrokerAccountID    string `json:"brokerAccountId"`
	PodName            string `json:"podName"`
	Phase              string `json:"phase"`
	Ready              bool   `json:"ready"`
	RestartCount       int32  `json:"restartCount"`
	WaitingReason      string `json:"waitingReason,omitempty"`
	LastTransitionTime string `json:"lastTransitionTime"`
}

type statusResponse struct {
	Terminals []terminalStatusWire `json:"terminals"`
}

// NewMux builds the internal mux for this one route. sharedSecret must be non-empty in any real
// deployment (env/secrets wiring sets INTERNAL_SERVICE_TOKEN) — an empty sharedSecret rejects
// every request rather than silently accepting an unauthenticated one, same contract
// broker-adapters' internalapi.NewMux already documents.
func NewMux(lister PodStatusLister, sharedSecret string, logger *slog.Logger) http.Handler {
	if logger == nil {
		logger = slog.Default()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /internal/terminals/status", func(w http.ResponseWriter, r *http.Request) {
		handleStatus(w, r, lister, logger)
	})
	return requireSharedSecret(sharedSecret, mux)
}

func requireSharedSecret(sharedSecret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		presented := r.Header.Get("X-Internal-Service-Token")
		if sharedSecret == "" || subtle.ConstantTimeCompare([]byte(sharedSecret), []byte(presented)) != 1 {
			http.Error(w, "missing or invalid internal service token", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func handleStatus(w http.ResponseWriter, r *http.Request, lister PodStatusLister, logger *slog.Logger) {
	statuses, err := lister.ListTerminalPodStatuses(r.Context())
	if err != nil {
		logger.Error("terminalstatus: list pod statuses", "error", err)
		http.Error(w, "failed to list terminal pod statuses", http.StatusInternalServerError)
		return
	}

	wire := make([]terminalStatusWire, len(statuses))
	for i, s := range statuses {
		wire[i] = terminalStatusWire{
			BrokerAccountID:    s.BrokerAccountID,
			PodName:            s.PodName,
			Phase:              s.Phase,
			Ready:              s.Ready,
			RestartCount:       s.RestartCount,
			WaitingReason:      s.WaitingReason,
			LastTransitionTime: s.LastTransitionTime.UTC().Format(time.RFC3339),
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(statusResponse{Terminals: wire})
}
