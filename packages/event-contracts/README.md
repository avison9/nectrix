# event-contracts

The canonical event-contract definitions — one `.proto` source of truth, generated into both Java (for `core-app`) and Go (for `copy-engine`/`broker-adapters`), so the event contract has one definition, not three hand-maintained copies. As of TICKET-007, this package also carries a reusable typed producer + idempotent-consumer-with-DLQ helper library in both languages — infrastructure only, no real business producer/consumer wired to a domain event yet (that lands with the tickets that actually own each topic's business logic).

## Layout

- `proto/nectrix/events/v1/` — the canonical `.proto` schema. `envelope.proto` defines the shared `EventEnvelope` (event_id, occurred_at, schema_version) embedded in every topic event message except `NormalizedTradeEvent` (which predates the envelope and keeps its own equivalent fields). One `.proto` file per topic — see the topic catalog below.
- `go/` — a standalone Go module. Generated code lives in `go/gen/` and is checked in (standard Go convention — no build-time codegen step). `go/eventconsumer/` is the producer/idempotent-consumer helper package; `go/redisdeduper/` is the temporary Redis-backed idempotency-dedup default (see below).
- `java/` — a standalone Gradle project using the `protobuf-gradle-plugin`. Generated at build time (not checked in — standard Gradle convention), pulled into `core-app` via a Gradle composite build (`includeBuild` in `core-app/settings.gradle.kts`). `java/src/main/java/com/nectrix/events/consumer/` is the producer/idempotent-consumer helper package.
- `testdata/` — fixtures shared by both the Go and Java round-trip tests, proving the two generated languages agree on structure without needing a live cross-process round trip. One fixture per proto message type covered by a round-trip test (not all of them — enough to prove the generation mechanism works for every topic's shape).

**Protobuf version note**: `java/build.gradle.kts` pins `protobuf-java`/`protobuf-java-util` to `4.34.2` specifically to match Spring Boot 4.1's managed dependency version — `core-app`'s dependency-management BOM overrides any higher version declared here, and a mismatched gencode/runtime version throws at class-init time. Keep this pinned version in sync with whatever `core-app`'s Spring Boot version resolves, not just "latest".

## Topic catalog (TICKET-007)

| Topic | Partition key | Proto message | Retention |
|---|---|---|---|
| `broker-connection` | `broker_account_id` | `BrokerConnectionEvent` | 30 days |
| `trade-signals` | `master_broker_account_id` | `NormalizedTradeEvent` (pre-existing) | 90 days |
| `copied-trades` | `copy_relationship_id` | `CopiedTradeEvent` | 90 days |
| `copy-relationships` | `copy_relationship_id` | `CopyRelationshipEvent` | 1 year |
| `billing` | `user_id` | `BillingEvent` | 1 year |
| `partner` | `partner_id` | `PartnerEvent` | 1 year |
| `risk` | `copy_relationship_id` | `RiskEvent` | 1 year |
| `invitations` | `invitation_id` | `InvitationEvent` | 1 year |
| `follow-requests` | `follow_request_id` | `FollowRequestEvent` | 1 year |

The first 7 rows are `docs/15-event-driven-architecture.md` §15.3; `invitations`/`follow-requests` are the two additions from the invitation-only onboarding model (`docs/05-domain-model.md` §5.6) — that doc names the events but not a partition key, so `invitation_id`/`follow_request_id` were chosen by analogy to `copy-relationships`' `copy_relationship_id` (same per-entity ordering rationale). Every topic also has a `<topic>.dlq` variant (1 partition, no ordering requirement, just capture).

Each event message embeds `EventEnvelope` + the partition key + a `*EventType` discriminator enum + a handful of real, minimal fields (matching `NormalizedTradeEvent`'s own level of detail, not more — full business fields are speculative until the ticket that owns each topic's real producer lands).

Topics are created explicitly via `infra/kafka/create-topics.sh` (`make kafka-topics`), not left to Kafka's `auto.create.topics.enable` default — auto-create gives every topic a single partition, which would make the partition-key-ordering design untestable (and pointless).

## Producer + idempotent-consumer helper

Both languages expose the same shape: a thin typed producer (`EventProducer<T>` in Java, `eventconsumer.Publish[T]` in Go) and a generic idempotent-consumer-with-DLQ wrapper (`IdempotentConsumer<T>` in Java, `eventconsumer.Consumer[T]` in Go) — reusable across all 9 topics' message types, not one bespoke consumer per topic.

Per record: deserialize (a parse failure routes straight to the DLQ, no retry — it's deterministic); check-and-skip duplicates via a pluggable dedup check keyed on the envelope's `event_id` (**not** the partition key — using the partition key as the idempotency key would make every same-key event look like a duplicate of the first one ever seen, a mistake made and caught during this ticket's own test development); on a genuinely new record, invoke the caller's handler with exponential-backoff retry; on final exhaustion, publish the original bytes untouched to `<topic>.dlq` with failure-context headers (`x-dlq-original-topic`/`-partition`/`-offset`/`-error-message`/`-attempt-count`) and only then commit. Manual commit only, never auto-commit — auto-commit would let the poll loop advance past a record whose outcome isn't yet certain. If the DLQ publish itself fails, this propagates uncommitted so a restart naturally redelivers the record rather than silently losing it.

**Idempotency-dedup default**: Go's `redisdeduper` package implements `packages/go-domain`'s already-forward-declared `Deduper` interface (that interface's own comment: "the real Redis-backed implementation lands in TICKET-008"). Java has no equivalent pre-existing interface, so `com.nectrix.events.consumer.RedisDeduplicator` fills the same role — both are small, self-contained, Redis-`SETNX`-based, and explicitly flagged for consolidation once TICKET-008 delivers the platform's shared Redis helper (same precedent as TICKET-005's `RateLimiterService`, built self-contained ahead of that same ticket).

## Design references

- `nectrix_plan/docs/15-event-driven-architecture.md` §15.3/§15.4/§15.6 — the topic catalog, at-least-once delivery + idempotency-key guidance, and DLQ/backpressure design this package implements.
- `nectrix_plan/docs/05-domain-model.md` §5.3/§5.6 — the canonical domain types and domain events this schema mirrors.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-001-repo-scaffolding.md` — the ticket that established this package.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-007-message-bus-setup.md` — the ticket that added the full topic catalog and typed pub/sub + idempotent-consumer helpers.

## Commands

```
make proto-gen     # regenerate Go code from the .proto source (Java gencode happens at build time)
make kafka-topics  # create the full topic catalog (idempotent) against the local/CI broker
```

Round-trip tests (from the repo root, inside the devcontainer):

```
docker compose exec devcontainer bash -c "cd packages/event-contracts/go && go test ./... -run TestRoundTrip -v"
docker compose exec devcontainer bash -c "cd packages/event-contracts/java && ./gradlew test --tests '*RoundTripTest*'"
```

Producer/consumer + DLQ integration tests (need a real Kafka broker + Redis — `docker-compose.yml`):

```
docker compose exec devcontainer bash -c "cd packages/event-contracts/go && go test -tags=integration ./eventconsumer/... -v"
docker compose exec devcontainer bash -c "cd packages/event-contracts/java && ./gradlew integrationTest"
```
