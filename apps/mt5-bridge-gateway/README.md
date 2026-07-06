# mt5-bridge-gateway

The Go gateway process that terminates connections from the MT5 Expert Advisor bridge. MT5 has no first-party public API, so trade events/order execution flow through an MQL5 EA (running inside each MT5 terminal) that connects out to this gateway, which then normalizes events into the same canonical domain types the Copy Engine and other Broker Adapters use.

This ticket (TICKET-001) only stands up the module skeleton and a `/healthz` hello-world endpoint. The EA itself and the real bridge protocol are Phase 1.

## Design references

- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` — MT5 linking strategy (Strategy A: EA + gateway).
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Go for the gateway (MQL5 for the EA itself).
- `nectrix_plan/phases/phase-1-mvp/tickets/TICKET-102-mt5-adapter.md` — the real MT5 adapter/bridge implementation.

## Dependencies

- `packages/go-domain` — shared normalized domain types and the `Deduper` idempotency interface, tied together via the root `go.work`.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-lint    # golangci-lint across all Go modules
```

Run directly: `go run .` (listens on `:8092`).
