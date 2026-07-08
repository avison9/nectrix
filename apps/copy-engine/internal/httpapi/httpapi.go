// Package httpapi builds copy-engine's HTTP surface as a standalone
// constructor so main.go and integration tests share the exact same code
// path — tests wrap the returned mux in httptest.NewServer and exercise the
// real HTTP endpoint end to end, rather than calling pipeline functions
// directly in-process.
package httpapi

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
)

type healthResponse struct {
	Service string `json:"service"`
	Status  string `json:"status"`
}

// NewMux builds /healthz plus TICKET-009's "manual/HTTP-triggerable inject
// event test endpoint": POST /test/inject-trade-event. adapter is declared
// as domain.BrokerAdapter (the pipeline's own dependency) but type-asserted
// to stubadapter.Injectable internally — only test/wiring code that knows
// it's talking to a stub reaches that affordance, real BrokerAdapter
// implementations (Phase 1) have no equivalent hook.
func NewMux(serviceName string, adapter domain.BrokerAdapter, masterHandle domain.ConnectionHandle) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})

	mux.HandleFunc("POST /test/inject-trade-event", func(w http.ResponseWriter, r *http.Request) {
		injectable, ok := adapter.(stubadapter.Injectable)
		if !ok {
			http.Error(w, "adapter does not support test event injection", http.StatusNotImplemented)
			return
		}

		body, err := io.ReadAll(r.Body)
		defer func() { _ = r.Body.Close() }()
		if err != nil {
			http.Error(w, "reading body: "+err.Error(), http.StatusBadRequest)
			return
		}
		var params stubadapter.InjectEventParams
		if len(body) > 0 {
			if err := json.Unmarshal(body, &params); err != nil {
				http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
				return
			}
		}

		// Synchronous: returns only once the whole pipeline call (DB insert +
		// Kafka publish) has completed, so a caller can immediately assert
		// against Postgres/Kafka once this returns 200 (AC2/AC3).
		if err := injectable.InjectEvent(r.Context(), masterHandle, params); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	return mux
}
