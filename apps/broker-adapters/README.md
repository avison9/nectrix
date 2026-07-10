# broker-adapters

Broker Adapter workers — Go processes implementing the `BrokerAdapter` interface for each supported broker, normalizing broker-specific behavior into the canonical domain types the Copy Engine operates on. A separate, networked deployable from `copy-engine`, sharing the same normalized types and idempotency primitives; cross-service side effects flow through Kafka, not synchronous calls.

TICKET-001 stood up the module skeleton and a `/healthz` hello-world endpoint. TICKET-002 added connection-draining. The `BrokerAdapter` interface itself (and a stub adapter emitting synthetic events) is TICKET-009. **TICKET-101 replaced the stub with a real cTrader Open API adapter** — OAuth-linked accounts, a real Protobuf/TLS connection to cTrader's own servers, and real order placement — verified hands-on against a real cTrader demo account (see `apps/core-app/README.md`'s "cTrader Broker Linking" section for the full architecture diagram and live-verification runbook; this README covers the Go-side pieces specifically).

## Packages (`internal/`)

- **`ctraderapi`** — the low-level client: TLS dial, 4-byte-BE length-prefixed `ProtoMessage` framing, request/response correlation via `clientMsgId`, heartbeats, streamed-event dispatch. No domain knowledge.
- **`ctrader`** — the real `domain.BrokerAdapter` implementation on top of `ctraderapi`: connection lifecycle (with reconnect/backoff), account snapshot/positions, symbol resolution (adapter-wide cache — `ResolveSymbol`/`GetSymbolSpecification` don't take a `ConnectionHandle`), order placement/modify/close, trade-event streaming, and `ListAccountsByAccessToken` (queries both demo and live hosts, used once during OAuth linking).
- **`dedupadapter`** — decorates any `domain.BrokerAdapter`, adding a Redis-backed idempotency guard in front of `PlaceOrder` (broker-side client-tag matching isn't a reliable dedup guarantee on its own).
- **`tradesignals`** — turns `StreamTradeEvents`' callback into a real publish to the `trade-signals` Kafka topic (partition key `master_broker_account_id`) — this service's first real business Kafka producer.
- **`internalapi`** — `POST /internal/ctrader/accounts {accessToken}` → `[{ctidTraderAccountId, isLive, traderLogin, brokerTitleShort}]`, called once by Core App during the OAuth callback (before any `broker_accounts` row exists) — cTrader's account-listing call only exists over the Protobuf connection, so Java can't do it directly.
- **`coreappclient`** — the HTTP client for Core App's internal-only endpoints: lightweight `broker_accounts` listing, per-account decrypted-credentials fetch (only for newly-discovered accounts), and connection-status reporting (`ReportConnectionStatus` — publishes `BrokerConnectionEvent` on the Java side).
- **`reconcile`** — the poll loop (`main.go`'s `reconcileInterval`, default 30s) keeping this service's live connection set in sync with Core App's view of which `broker_accounts` should be connected: discovers new accounts, connects them, streams their trade events to Kafka, and reports real connection-status transitions back.

Go never touches Postgres directly — Core App (Java) is the single source of truth for `broker_accounts` and the single `EnvelopeEncryptionService` caller; this service only ever sees already-decrypted tokens, fetched per-request over the internal HTTP hop above.

## Design references

- `nectrix_plan/docs/04-architecture-overview.md` §4.3 — the `BrokerAdapter` interface.
- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` — broker linking flow.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-009-broker-adapter-interface-stub.md` — defines the interface + stub adapter.
- `nectrix_plan/phases/phase-1-mvp/tickets/TICKET-101-ctrader-adapter.md` — the real cTrader adapter.
- `packages/ctrader-proto/README.md` — vendored cTrader Open API proto schema + codegen.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface.
- `packages/ctrader-proto/go` — generated Go bindings for cTrader's own published Open API proto schema.
- `packages/redis-client/go` — the shared idempotency (`dedupadapter`) primitive.
- `packages/event-contracts/go` — the `trade-signals` Kafka message contract.

All tied together via the root `go.work`.

## Configuration

Real env vars this service reads (see root `.env.example`):

| Var | Required | Notes |
|---|---|---|
| `CTRADER_CLIENT_ID` / `CTRADER_CLIENT_SECRET` | yes | This platform's own registered cTrader Open API application — `main.go` fails fast (`log.Fatalf`) if unset. Never a per-user credential. |
| `INTERNAL_SERVICE_TOKEN` | yes | Shared secret authenticating to Core App's internal endpoints — must be byte-for-byte identical to Core App's own copy. |
| `CORE_APP_INTERNAL_BASE_URL` | no (default `http://localhost:8080`) | Where `coreappclient` calls Core App's internal API. |
| `REDIS_HOST`/`REDIS_PORT`, `KAFKA_HOST`/`KAFKA_PORT` | no (localhost defaults) | Standard connection config, same convention as `apps/copy-engine`. |

## Container image

`Dockerfile` here is multi-stage (`golang:1.26.4-bookworm` build → `gcr.io/distroless/static-debian12:nonroot` runtime). **Build context must be the repo root**, not this directory:

```
docker build -f apps/broker-adapters/Dockerfile -t broker-adapters .
```

CI builds, Trivy-scans, and pushes this to `ghcr.io/avison9/nectrix/broker-adapters:<commit-sha>` on every merge to `main` — see the root README's CI/CD section. Deployed via `deploy/base/broker-adapters/` (Kustomize), in the `copy-engine` namespace, restricted by a `NetworkPolicy` on both this service's outbound broker traffic and Core App's inbound `/internal/**` traffic (see `deploy/base/core-app/network-policy.yaml`).

## Commands

```
make go-build   # builds all Go modules, including this one
make go-test    # tests all Go modules, including this one (unit only — see below for integration)
make go-lint    # golangci-lint across all Go modules
```

Real integration tests (need live infra — Redis/Kafka via `docker compose up`, no live cTrader
needed except where noted): `go test -tags=integration ./internal/dedupadapter/... ./internal/tradesignals/...`

Run directly: `go run .` (listens on `:8091`) — needs the env vars above set.
