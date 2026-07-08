rootProject.name = "event-contracts"

// TICKET-008 — the Deduplicator interface (used by IdempotentConsumer.Config) now lives in
// packages/redis-client/java, mirroring the Go side's existing event-contracts -> go-domain
// dependency direction (a generic Redis primitive, not event-specific).
// Explicit name= required: Gradle derives an included build's internal "build
// path" from its directory's leaf name by default (NOT rootProject.name), and
// both packages/event-contracts/java and packages/redis-client/java happen to
// share the leaf directory name "java" — without this, core-app (which
// includes event-contracts/java, which includes this) fails with "Included
// build .../redis-client/java has build path :java which is the same as
// included build .../event-contracts/java".
includeBuild("../../redis-client/java") {
    name = "redis-client"
}
