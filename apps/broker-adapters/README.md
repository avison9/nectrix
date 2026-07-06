# broker-adapters

Broker Adapter workers — Go processes implementing the `BrokerAdapter` interface for each supported broker (cTrader via Open API first, in Phase 1), normalizing broker-specific behavior into the canonical domain types the Copy Engine operates on. A separate deployable from `copy-engine`, sharing the same normalized types and idempotency primitives.

This ticket (TICKET-001) only stands up the module skeleton and a `/healthz` hello-world endpoint. The `BrokerAdapter` interface itself (and a stub adapter emitting synthetic events) is TICKET-009; real cTrader/MT5 connectivity is Phase 1.

## Design references

- `nectrix_plan/docs/04-architecture-overview.md` §4.3 — the `BrokerAdapter` interface this module will implement.
- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` — broker linking flow.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-009-broker-adapter-interface-stub.md` — defines the interface + stub adapter.
- `nectrix_plan/phases/phase-1-mvp/tickets/TICKET-101-ctrader-adapter.md` — the first real adapter.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface, tied together via the root `go.work`.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-lint    # golangci-lint across all Go modules
```

Run directly: `go run .` (listens on `:8091`).
