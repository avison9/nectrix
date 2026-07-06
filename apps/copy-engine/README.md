# copy-engine

The Copy Engine — a Go service that consumes normalized trade events from a master's `BrokerAdapter` and computes/dispatches proportional orders into each follower's broker account. Sharded by master-account hash; a separate deployable from `core-app` from day one, for sub-second latency and independent restart/scaling.

This ticket (TICKET-001) only stands up the module skeleton and a `/healthz` hello-world endpoint — the real pipeline (sizing, risk guard, relationship matching, order dispatch) lands in Phase 1.

## Design references

- `nectrix_plan/docs/08-copy-trading-engine.md` — the pipeline this service implements.
- `nectrix_plan/docs/09-money-management-risk-formulas.md` — sizing/risk logic (Phase 1).
- `nectrix_plan/docs/appendix-a-copy-engine-pseudocode.md` — reference pseudocode.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-009-broker-adapter-interface-stub.md` — the next ticket that wires a stub `BrokerAdapter` into this pipeline.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface, tied together via the root `go.work`.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-lint    # golangci-lint across all Go modules
```

Run directly: `go run .` (listens on `:8090`).
