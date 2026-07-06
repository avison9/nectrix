# mt5-bridge-gateway

The Go gateway process that terminates connections from the MT5 Expert Advisor bridge. MT5 has no first-party public API, so trade events/order execution flow through an MQL5 EA (running inside each MT5 terminal) that connects out to this gateway, which then normalizes events into the same canonical domain types the Copy Engine and other Broker Adapters use.

This ticket (TICKET-001) only stands up the module skeleton and a `/healthz` hello-world endpoint. The EA itself and the real bridge protocol are Phase 1.

## Design references

- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` — MT5 linking strategy (Strategy A: EA + gateway).
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go for the gateway (MQL5 for the EA itself).
- `nectrix_plan/phases/phase-1-mvp/tickets/TICKET-102-mt5-adapter.md` — the real MT5 adapter/bridge implementation.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface, tied together via the root `go.work`.

## Container image

`Dockerfile` here is multi-stage (`golang:1.26.4-bookworm` build → `gcr.io/distroless/static-debian12:nonroot` runtime). **Build context must be the repo root**, not this directory:

```
docker build -f apps/mt5-bridge-gateway/Dockerfile -t mt5-bridge-gateway .
```

CI builds, Trivy-scans, and pushes this to `ghcr.io/avison9/nectrix/mt5-bridge-gateway:<commit-sha>` on every merge to `main` — see the root README's CI/CD section. Deployed via `deploy/base/mt5-bridge-gateway/` (Kustomize), in the `copy-engine` namespace. No connection-draining hook here (AC5 of TICKET-002 named only `copy-engine`/`broker-adapters`) — a one-line addition later if needed.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-test    # tests all Go modules, including this one
make go-lint    # golangci-lint across all Go modules
```

Run directly: `go run .` (listens on `:8092`).
