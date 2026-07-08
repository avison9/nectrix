# copy-engine

The Copy Engine — a Go service that consumes normalized trade events from a master's `BrokerAdapter` and computes/dispatches proportional orders into each follower's broker account. Sharded by master-account hash; a separate deployable from `core-app` from day one, for sub-second latency and independent restart/scaling.

TICKET-001 stood up the module skeleton and a `/healthz` hello-world endpoint. TICKET-002 added connection-draining: an in-process `SIGTERM` handler logs a drain message and waits ~10s before shutting down its HTTP server, giving in-flight work a chance to finish before the process exits — see `main.go`. TICKET-009 wired up the real (if simplified) pipeline; TICKET-010 added tracing/metrics/structured logging. Real money-management/risk-guard formulas and real cTrader/MT5 adapters are still Phase 1.

## Layout

- `main.go` — composition root: builds the Postgres pool, Redis deduper, Kafka writer, the stub `BrokerAdapter`, the pipeline, and the HTTP server; owns the SIGTERM drain sequence.
- `internal/stubadapter/` — `StubBrokerAdapter`/`StubBrokerAdapterVariant`, the two `domain.BrokerAdapter` implementations (CTRADER vs MT5, genuinely different fill-slippage behavior) that stand in for real broker connectivity until Phase 1. `InjectEvent` (test-only, not part of `BrokerAdapter`) is what `/test/inject-trade-event` calls.
- `internal/pipeline/` — Normalizer → Dedup Filter → Relationship Matcher → Order Dispatcher → publish, per `docs/08-copy-trading-engine.md` §8.2/Appendix A. Each stage is a real OTel child span (TICKET-010).
- `internal/httpapi/` — the HTTP surface (`/healthz`, `/metrics`, `POST /test/inject-trade-event`), shared verbatim between `main.go` and the integration tests via `httptest.NewServer`.
- `internal/observability/` — TICKET-010's tracer/metrics/redacting-logger setup, shared by every other `internal/` package.
- `pipeline_integration_test.go` — self-seeding (fresh random UUIDs, no dependency on `make db-seed-dev`) hands-on tests for TICKET-009's AC2-4 against real Postgres/Redis/Kafka.

## Design references

- `nectrix_plan/docs/04-architecture-overview.md` §4.3 — the `BrokerAdapter` interface (`packages/go-domain/broker_adapter.go`) this service depends on and never bypasses.
- `nectrix_plan/docs/08-copy-trading-engine.md` §8.2/§8.3, `appendix-a-copy-engine-pseudocode.md` — the pipeline shape and idempotency design `internal/pipeline` implements.
- `nectrix_plan/docs/09-money-management-risk-formulas.md` — real sizing/risk logic (Phase 1; `internal/pipeline` does a straight 1:1 volume copy today).
- `nectrix_plan/docs/18-scaling-observability-dr.md` §18.2 — the tracing/metrics/logging design `internal/observability` implements.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-009-broker-adapter-interface-stub.md`, `TICKET-010-observability-stack.md` — the tickets that built the current pipeline/observability wiring.

## Dependencies

- `packages/go-domain` — shared normalized domain types, the `BrokerAdapter` interface, and the `Deduper` idempotency interface.
- `packages/redis-client/go` — the shared Redis dedup client (TICKET-008).
- `packages/event-contracts/go` — generated `NormalizedTradeEvent`/`CopiedTradeEvent` proto types.
- `jackc/pgx/v5`, `segmentio/kafka-go`, `google/uuid` — Postgres, Kafka, and ID generation (this is the first Go service in the repo to talk to Postgres directly).
- `go.opentelemetry.io/otel` + `otlptracehttp` + `contrib/instrumentation/net/http/otelhttp`, `prometheus/client_golang` — TICKET-010's tracing/metrics.

All tied together via the root `go.work`.

## The pipeline (TICKET-009)

`POST /test/inject-trade-event` (JSON body: `brokerPositionId`, `eventType`, `symbol`, `direction`, `volumeLots`, `openPrice`, `serverTimestamp` — all optional, sane defaults) drives a synthetic `NormalizedTradeEvent` through the stub adapter's `StreamTradeEvents` subscription into the real pipeline:

1. **Normalizer** — validates required fields are present (the event arrives already-normalized, per the `BrokerAdapter` interface's contract).
2. **Dedup Filter** — Redis fast-path (`SETNX`-style) + `trade_signals`' unique constraint as the durable guard, keyed on `(masterBrokerAccountId, brokerPositionId, eventType, serverTimestamp)` — **not** the event envelope's `event_id` (a different dedup mechanism `packages/event-contracts/go/eventconsumer` uses for Kafka topics).
3. **Relationship Matcher** — a real `copy_relationships` query (`status = 'ACTIVE'`), not a hardcoded fake.
4. **Order Dispatcher** — 1:1 volume copy, calls the follower's `BrokerAdapter.PlaceOrder`, persists one `copied_trades` row.
5. **Publish** — `CopiedTradeEvent` to Kafka's `copied-trades` topic, keyed by `copy_relationship_id`.

Local manual testing: `make db-seed-dev` seeds a matching master/follower/`copy_relationships` fixture at fixed UUIDs (`00000000-...-0010`/`0011`/`0050`) that `main.go` defaults to (override via `STUB_MASTER_BROKER_ACCOUNT_ID`/`STUB_FOLLOWER_BROKER_ACCOUNT_ID`), so `curl localhost:8090/test/inject-trade-event -d '{}'` works out of the box.

## Observability (TICKET-010)

`internal/observability.Init` wires the OTel `TracerProvider` (OTLP/HTTP export, `OTEL_EXPORTER_OTLP_ENDPOINT`, default `http://localhost:4318`) and a redacting `log/slog` JSON logger shared across the app. `internal/httpapi.NewMux` wraps every request in `otelhttp` (real per-request spans) and a metrics middleware (`copy_engine_http_requests_total`/`copy_engine_http_request_duration_seconds`); `internal/pipeline` adds one child span per stage, so an injected event is traceable as a single trace from HTTP ingestion through to the Kafka publish. `internal/stubadapter.InjectEvent` increments `copy_engine_stub_events_injected_total` (the baseline Grafana dashboard's throughput panel). See root `README.md`'s Observability section and `infra/observability/verify.sh` for hands-on AC verification.

## Container image

`Dockerfile` here is multi-stage (`golang:1.26.4-bookworm` build → `gcr.io/distroless/static-debian12:nonroot` runtime — no shell, since drain logic is in-process, not a `preStop` hook). **Build context must be the repo root**, not this directory, since the build stage needs `packages/go-domain`/`packages/event-contracts/go`/`packages/redis-client/go` as sibling source:

```
docker build -f apps/copy-engine/Dockerfile -t copy-engine .
```

CI builds, Trivy-scans (`CRITICAL,HIGH` gated), and pushes this to `ghcr.io/avison9/nectrix/copy-engine:<commit-sha>` on every merge to `main` — see the root README's CI/CD section. Deployed via `deploy/base/copy-engine/` (Kustomize), in the `copy-engine` namespace.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-test    # tests all Go modules, including this one
make go-lint    # golangci-lint across all Go modules
```

Run directly: `go run .` (listens on `:8090`; needs `POSTGRES_*`/`REDIS_*`/`KAFKA_*` env vars — see root `docker-compose.yml`/`.env.example`).

Hands-on AC verification (needs real Postgres/Redis/Kafka — `docker-compose.yml`):

```
docker compose exec devcontainer bash -c "cd apps/copy-engine && go test -tags=integration ./... -run TestAC -v"
```
