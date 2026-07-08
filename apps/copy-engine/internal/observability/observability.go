// Package observability is TICKET-010's tracing/metrics/logging setup for
// copy-engine, centralized here so every other package (httpapi, pipeline,
// stubadapter) shares one TracerProvider, one metrics registry, and one
// redacting logger rather than each reinventing it.
package observability

import (
	"context"
	"log/slog"
	"os"
	"strings"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/resource"
	"go.opentelemetry.io/otel/trace"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

const tracerName = "copy-engine"

// Prometheus metrics — shared across internal/httpapi (HTTP request
// counting) and internal/stubadapter (stub-pipeline throughput, the
// baseline Grafana dashboard's third panel).
var (
	HTTPRequestsTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "copy_engine_http_requests_total",
		Help: "Total HTTP requests handled, by path/method/status.",
	}, []string{"path", "method", "status"})

	HTTPRequestDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name: "copy_engine_http_request_duration_seconds",
		Help: "HTTP request duration in seconds, by path/method.",
	}, []string{"path", "method"})

	StubEventsInjectedTotal = promauto.NewCounter(prometheus.CounterOpts{
		Name: "copy_engine_stub_events_injected_total",
		Help: "Total synthetic trade events injected via the stub adapter's test endpoint.",
	})
)

// redactedKeys mirrors apps/core-app's logback-spring.xml masking list —
// same allow-listed sensitive field names, same reasoning
// (docs/17-security-architecture.md — no plaintext logging of broker
// credentials/PII), applied at the logging-library level via slog's
// ReplaceAttr rather than left to per-call-site discipline.
var redactedKeys = map[string]bool{
	"password": true, "secret": true, "token": true, "credential": true,
	"credentials": true, "ciphertext": true, "apitoken": true, "client_secret": true,
}

func redact(_ []string, a slog.Attr) slog.Attr {
	if redactedKeys[strings.ToLower(a.Key)] {
		a.Value = slog.StringValue("****")
	}
	return a
}

// Logger is the shared, redacting, JSON structured logger.
var Logger = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{ReplaceAttr: redact}))

// Init sets up the OTel TracerProvider (OTLP/HTTP exporter to otlpEndpoint,
// e.g. Tempo) as the global tracer provider, and returns a shutdown func to
// flush/close it on process exit. otlpEndpoint being unreachable never
// blocks startup — export failures are logged by the SDK, not fatal.
func Init(ctx context.Context, serviceName, otlpEndpoint string) (func(context.Context) error, error) {
	exporter, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(otlpEndpoint+"/v1/traces"))
	if err != nil {
		return nil, err
	}

	res, err := resource.New(ctx, resource.WithAttributes(attribute.String("service.name", serviceName)))
	if err != nil {
		return nil, err
	}

	tp := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exporter), sdktrace.WithResource(res))
	otel.SetTracerProvider(tp)
	return tp.Shutdown, nil
}

// Tracer returns the shared tracer every instrumented package starts spans
// from.
func Tracer() trace.Tracer {
	return otel.Tracer(tracerName)
}

// LogWithTrace logs msg via Logger, adding trace_id/span_id attributes from
// ctx's active span if there is one. Go has no MDC-equivalent automatic
// injection (unlike core-app's OTel javaagent, which does this for Java
// automatically) — this is the explicit substitute, called at each site
// that wants trace-correlated logs.
func LogWithTrace(ctx context.Context, msg string, args ...any) {
	sc := trace.SpanContextFromContext(ctx)
	if sc.IsValid() {
		args = append(args, "trace_id", sc.TraceID().String(), "span_id", sc.SpanID().String())
	}
	Logger.InfoContext(ctx, msg, args...)
}
