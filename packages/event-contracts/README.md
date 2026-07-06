# event-contracts

The canonical event-contract definitions — one `.proto` source of truth, generated into both Java (for `core-app`) and Go (for `copy-engine`/`broker-adapters`), so the event contract has one definition, not three hand-maintained copies.

## Layout

- `proto/nectrix/events/v1/` — the canonical `.proto` schema. Currently models `NormalizedTradeEvent` (see `nectrix_plan/docs/05-domain-model.md` §5.3).
- `go/` — a standalone Go module. Generated code lives in `go/gen/` and is checked in (standard Go convention — no build-time codegen step).
- `java/` — a standalone Gradle project using the `protobuf-gradle-plugin`. Generated at build time (not checked in — standard Gradle convention), pulled into `core-app` via a Gradle composite build (`includeBuild` in `core-app/settings.gradle.kts`).
- `testdata/sample_trade_event.json` — a single fixture shared by both the Go and Java round-trip tests, proving the two generated languages agree on structure without needing a live cross-process round trip.

**Protobuf version note**: `java/build.gradle.kts` pins `protobuf-java`/`protobuf-java-util` to `4.34.2` specifically to match Spring Boot 4.1's managed dependency version — `core-app`'s dependency-management BOM overrides any higher version declared here, and a mismatched gencode/runtime version throws at class-init time. Keep this pinned version in sync with whatever `core-app`'s Spring Boot version resolves, not just "latest".

## Design references

- `nectrix_plan/docs/15-event-driven-architecture.md` §15.3 — the full topic catalog and partition-key rationale this schema will grow to cover.
- `nectrix_plan/docs/05-domain-model.md` §5.3 — the canonical domain types this schema mirrors.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-001-repo-scaffolding.md` — the ticket that established this package.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-007-message-bus-setup.md` — where the full topic catalog and typed pub/sub helpers land.

## Commands

```
make proto-gen   # regenerate Go code from the .proto source
```

Round-trip tests (from the repo root, inside the devcontainer):

```
docker compose exec devcontainer bash -c "cd packages/event-contracts/go && go test ./... -run TestRoundTrip -v"
docker compose exec devcontainer bash -c "cd packages/event-contracts/java && ./gradlew test --tests '*RoundTripTest*'"
```
