# redis-client

TICKET-008's shared, cluster-aware Redis client library — the one place standalone-vs-cluster
topology branches, and the canonical home for the platform's idempotency-dedup and rate-limiting
primitives. Consolidates two self-contained helpers built ahead of this ticket by tickets that
needed Redis before it existed: TICKET-005's `RateLimiterService` (fixed-window INCR+EXPIRE) and
TICKET-007's `RedisDeduplicator`/`redisdeduper` (single-node only). Both call sites now delegate
here.

## The hard rule: Redis is never system-of-record

Per `nectrix_plan/docs/15-event-driven-architecture.md` §15.5: every fact cached in Redis —
idempotency keys, rate-limit buckets, active-relationship cache, session lookups, live position
cache — **must** have a Postgres-durable source of truth and a re-derivation path. Redis is a
fast-path optimization, not storage. Losing Redis state (a flush, a restart, a full cluster
failure) must degrade behavior predictably (a dedup key looks "new" again, a rate-limit bucket
resets to full), never corrupt or silently misbehave. This is why `AcceptanceCriteriaIntegrationTest`
(Java)/`acceptance_criteria_integration_test.go` (Go) both include a `FlushAll` mid-test rather than
only testing the happy path — see AC4 in each file.

The one thing genuinely out of scope for this ticket (Phase 1, `docs/08-copy-trading-engine.md`
§8.9) is the reconciliation job that rebuilds Redis-cached facts from Postgres after a flush — this
library proves flush-safety (no crash/corruption), not reconciliation.

## Layout

- `java/` — a standalone Gradle project, pulled into `event-contracts/java` and `core-app` via a
  Gradle composite build (`includeBuild`). Plain Jedis, not Spring Data Redis — this library must
  stay usable by any future Java service, not just Spring ones (same reasoning as TICKET-007's
  original `RedisDeduplicator`).
- `go/` — a standalone Go module, added to the repo's `go.work`. Consumed by `event-contracts/go`
  and directly by `copy-engine`.

Both languages expose the same shape:

| Concept | Java | Go |
|---|---|---|
| Client factory | `RedisClientFactory.create(RedisClientConfig)` → `UnifiedJedis` | `New(Config) (redis.Cmdable, error)` |
| Config from env | `RedisClientConfig.fromEnv()` | `ConfigFromEnv()` |
| Dedup | `Deduplicator` / `RedisDeduplicator` | `Deduper` / `NewDeduper` (implements `packages/go-domain`'s `Deduper` interface) |
| Rate limit | `RateLimiter` / `TokenBucketRateLimiter` | `RateLimiter` / `NewRateLimiter` |

## Cluster-vs-standalone: explicit switch, not auto-detection

`REDIS_MODE=standalone|cluster` (default `standalone`), plus `REDIS_HOST`/`REDIS_PORT` (standalone)
or `REDIS_CLUSTER_NODES` (comma-separated `host:port`, cluster mode) and an optional
`REDIS_PASSWORD`. This is a deliberate config switch rather than auto-detection: neither Jedis's
`JedisCluster` nor go-redis's `ClusterClient` works transparently against a plain
non-cluster-enabled node — `CLUSTER SLOTS` returns an empty table there, and command routing then
fails hard. Local/CI Redis (`docker-compose.yml`) is always standalone; real cloud deployments
(`infra/terraform/{aws,gcp}`) run cluster mode.

- Java: `RedisClientFactory.create(...)` returns `UnifiedJedis` — the common superclass of both
  `JedisPooled` (standalone) and `JedisCluster`, so every caller downstream is topology-agnostic.
- Go: `New(...)` returns `redis.Cmdable` — satisfied by both `*redis.Client` and
  `*redis.ClusterClient`, same effect.

## Idempotency dedup

`SET key value NX EX ttl` — a single atomic Redis command, already race-safe for concurrent calls
with the identical key (no Lua needed; `SETNX`+separate `EXPIRE` would not be atomic and is
deliberately not used). `seenBefore`/`SeenBefore` returns `true` if the key was already present
(a duplicate), `false` on first sighting. See `docs/08-copy-trading-engine.md` §8.3/appendix-A
§A.1 for the dedup-key convention (event envelope `event_id`, never a partition/business key).

## Rate limiting: token bucket

A single-key Lua script (`EVAL`), not a fixed-window counter — `docs/14-api-specification.md`
§14.12 specifies token-bucket. Lazy refill computed against Redis's own `TIME` command (not a
client-supplied timestamp, avoiding clock skew across app instances); atomicity comes from Redis's
single-threaded command execution, so concurrent `tryConsume`/`TryConsume` calls against the same
key are fully serialized server-side with no application-level locking. Single key also sidesteps
cluster mode's `CROSSSLOT` constraint (every key in one script's `KEYS[]` must hash to the same
slot).

Deliberately plain `EVAL` on every call, not a cached-SHA `EVALSHA`+`NOSCRIPT`-fallback dance
(including go-redis's own `*redis.Script.Run` helper, and Spring's `DefaultRedisScript`): in
cluster mode Redis's script cache is per-shard, not cluster-wide, so a SHA cached via one key's
`SCRIPT LOAD` isn't guaranteed present on the node owning a *different* rate-limit key's hash slot
— naive SHA caching is a real correctness hazard here, not just a missed optimization. The script
is small enough (~500 bytes) that paying the parse cost every call is the right trade-off at this
scope.

A `rate == 0` bucket (a fixed allowance that never refills, used by e.g. both languages' AC3 tests)
is handled explicitly — `capacity / rate` would otherwise divide by zero (Lua evaluates it to
`inf`, which `PEXPIRE` then rejects as "not an integer") — falling back to a fixed 1-hour idle TTL
instead of a refill-derived one.

## Consumers

- `packages/event-contracts/{java,go}`'s `IdempotentConsumer`/`eventconsumer.Consumer` — dedup
  check keyed on the event envelope's `event_id`.
- `apps/core-app/modules/auth`'s `RateLimiterService` — guards `/login` and `/2fa/verify`
  (`AuthProperties`' `maxAttempts`/`windowSeconds` translate to `capacity`/`refillPerSecond`; a
  deliberate approximation of the old fixed-window semantics, not identical behavior).
- `apps/copy-engine`'s `main.go` — constructs the shared client at startup and performs one real
  `Deduper.SeenBefore` call, proving the wiring resolves against the real local/CI Redis; real
  per-message usage lands with the ticket that owns copy-engine's actual trade-copy logic.

## Design references

- `nectrix_plan/docs/15-event-driven-architecture.md` §15.5 — the "Redis is never system-of-record"
  rule this whole package is built around.
- `nectrix_plan/docs/08-copy-trading-engine.md` §8.3/appendix-A §A.1 — the idempotency-key
  convention the dedup helper implements.
- `nectrix_plan/docs/14-api-specification.md` §14.12 — the token-bucket rate-limiting algorithm
  choice.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-008-redis-caching-setup.md` — this ticket.

## Commands

Unit tests (no live Redis needed):

```
docker compose exec devcontainer bash -c "cd packages/redis-client/java && ./gradlew test"
docker compose exec devcontainer bash -c "cd packages/redis-client/go && go test ./..."
```

Acceptance-criteria integration tests (need a real local Redis — `docker-compose.yml`; these are
the AC2/AC3/AC4 race-condition, concurrent-load, and flush-safety proofs):

```
docker compose exec devcontainer bash -c "cd packages/redis-client/java && ./gradlew integrationTest"
docker compose exec devcontainer bash -c "cd packages/redis-client/go && go test -tags=integration ./... -v"
```
