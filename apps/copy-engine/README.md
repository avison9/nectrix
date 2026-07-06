# copy-engine

The Copy Engine — a Go service that consumes normalized trade events from a master's `BrokerAdapter` and computes/dispatches proportional orders into each follower's broker account. Sharded by master-account hash; a separate deployable from `core-app` from day one, for sub-second latency and independent restart/scaling.

TICKET-001 stood up the module skeleton and a `/healthz` hello-world endpoint. TICKET-002 added connection-draining: an in-process `SIGTERM` handler logs a drain message and waits ~10s before shutting down its HTTP server, giving in-flight work a chance to finish before the process exits — see `main.go`. The real pipeline (sizing, risk guard, relationship matching, order dispatch) lands in Phase 1.

## Design references

- `nectrix_plan/docs/08-copy-trading-engine.md` — the pipeline this service implements.
- `nectrix_plan/docs/09-money-management-risk-formulas.md` — sizing/risk logic (Phase 1).
- `nectrix_plan/docs/appendix-a-copy-engine-pseudocode.md` — reference pseudocode.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-009-broker-adapter-interface-stub.md` — the next ticket that wires a stub `BrokerAdapter` into this pipeline.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface, tied together via the root `go.work`.

## Container image

`Dockerfile` here is multi-stage (`golang:1.26.4-bookworm` build → `gcr.io/distroless/static-debian12:nonroot` runtime — no shell, since drain logic is in-process, not a `preStop` hook). **Build context must be the repo root**, not this directory, since the build stage needs `packages/go-domain`/`packages/event-contracts/go` as sibling source:

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

Run directly: `go run .` (listens on `:8090`).
