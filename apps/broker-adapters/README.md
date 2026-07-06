# broker-adapters

Broker Adapter workers — Go processes implementing the `BrokerAdapter` interface for each supported broker (cTrader via Open API first, in Phase 1), normalizing broker-specific behavior into the canonical domain types the Copy Engine operates on. A separate deployable from `copy-engine`, sharing the same normalized types and idempotency primitives.

TICKET-001 stood up the module skeleton and a `/healthz` hello-world endpoint. TICKET-002 added connection-draining: an in-process `SIGTERM` handler logs a drain message and waits ~10s before shutting down its HTTP server — see `main.go`. The `BrokerAdapter` interface itself (and a stub adapter emitting synthetic events) is TICKET-009; real cTrader/MT5 connectivity is Phase 1.

## Design references

- `nectrix_plan/docs/04-architecture-overview.md` §4.3 — the `BrokerAdapter` interface this module will implement.
- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` — broker linking flow.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-009-broker-adapter-interface-stub.md` — defines the interface + stub adapter.
- `nectrix_plan/phases/phase-1-mvp/tickets/TICKET-101-ctrader-adapter.md` — the first real adapter.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface, tied together via the root `go.work`.

## Container image

`Dockerfile` here is multi-stage (`golang:1.26.4-bookworm` build → `gcr.io/distroless/static-debian12:nonroot` runtime). **Build context must be the repo root**, not this directory:

```
docker build -f apps/broker-adapters/Dockerfile -t broker-adapters .
```

CI builds, Trivy-scans, and pushes this to `ghcr.io/avison9/nectrix/broker-adapters:<commit-sha>` on every merge to `main` — see the root README's CI/CD section. Deployed via `deploy/base/broker-adapters/` (Kustomize), in the `copy-engine` namespace.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-test    # tests all Go modules, including this one
make go-lint    # golangci-lint across all Go modules
```

Run directly: `go run .` (listens on `:8091`).
