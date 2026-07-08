// Package httpapi builds copy-engine's HTTP surface as a standalone
// constructor so main.go and integration tests share the exact same code
// path — tests wrap the returned handler in httptest.NewServer and exercise
// the real HTTP endpoint end to end, rather than calling pipeline functions
// directly in-process.
package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/observability"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type healthResponse struct {
	Service string `json:"service"`
	Status  string `json:"status"`
}

// NewMux builds /healthz, /metrics, plus TICKET-009's "manual/HTTP-
// triggerable inject event test endpoint": POST /test/inject-trade-event.
// adapter is declared as domain.BrokerAdapter (the pipeline's own
// dependency) but type-asserted to stubadapter.Injectable internally —
// only test/wiring code that knows it's talking to a stub reaches that
// affordance, real BrokerAdapter implementations (Phase 1) have no
// equivalent hook.
//
// TICKET-010: every request is wrapped in an OTel span (otelhttp) and
// recorded in the copy_engine_http_requests_total/_duration_seconds
// metrics — this is what makes AC1's "metrics visible in Grafana" and
// AC4's deliberately-triggered alert (which fires on a 5xx rate on this
// same metric) real rather than aspirational.
func NewMux(serviceName string, adapter domain.BrokerAdapter, masterHandle domain.ConnectionHandle) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		// TICKET-010 AC3 — a deliberately-logged fake "secret" field, proving
		// observability.redact (slog's ReplaceAttr) masks allow-listed
		// sensitive field names at the logging-library level rather than
		// relying on every call site to remember not to log this kind of value.
		observability.LogWithTrace(r.Context(), "healthz endpoint hit", "secret", "fake-secret-should-be-redacted")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})

	mux.Handle("/metrics", promhttp.Handler())

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

		// AC2 — lets a trace be found in Tempo by the injected
		// brokerPositionId, without needing to already know its trace ID.
		trace.SpanFromContext(r.Context()).SetAttributes(
			attribute.String("nectrix.broker_position_id", params.BrokerPositionID),
		)

		// Synchronous: returns only once the whole pipeline call (DB insert +
		// Kafka publish) has completed, so a caller can immediately assert
		// against Postgres/Kafka once this returns 200 (AC2/AC3).
		if err := injectable.InjectEvent(r.Context(), masterHandle, params); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	return otelhttp.NewHandler(withMetrics(mux), serviceName)
}

// withMetrics records copy_engine_http_requests_total/_duration_seconds for
// every request — the two metrics the baseline Grafana dashboard's
// "request latency"/"error rate" panels and AC4's alert rule read.
func withMetrics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, r)
		observability.HTTPRequestDuration.WithLabelValues(r.URL.Path, r.Method).Observe(time.Since(start).Seconds())
		observability.HTTPRequestsTotal.WithLabelValues(r.URL.Path, r.Method, strconv.Itoa(rec.status)).Inc()
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}
