# ctrader-proto

Vendored, read-only copy of Spotware's official cTrader Open API protobuf message
definitions — **never edit `proto/*.proto` by hand**; re-vendor from upstream and
regenerate instead.

- Source: https://github.com/spotware/openapi-proto-messages
- Vendored at commit `3fd8bddfbe0cfc2ecfda079623dc4e498af11e66` (2025-11-13)
- License: MIT (`LICENSE-openapi-proto-messages`, copied verbatim from upstream)

This is a third-party schema (Spotware's, not ours) — deliberately kept in its own
package, separate from `packages/event-contracts` (our own canonical schema), so the
distinction between "we own this" and "we vendor this" stays obvious from the
directory layout alone.

## Regenerating

```
make ctrader-proto-gen
```

Runs `protoc` against `proto/*.proto`, output committed to `go/gen/` — same
`--go_opt=paths=source_relative` convention as `packages/event-contracts`' own
codegen (see root `Makefile`'s `proto-gen` target).

## Consumers

`apps/broker-adapters/internal/ctraderapi` (TICKET-101) is the only consumer today —
the low-level Protobuf/TLS client that speaks this schema directly. No other service
should ever import this package; everything above `ctraderapi` deals only in this
platform's own normalized domain types (`packages/go-domain`).
