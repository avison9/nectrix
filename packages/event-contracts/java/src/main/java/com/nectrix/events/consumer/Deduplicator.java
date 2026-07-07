package com.nectrix.events.consumer;

/**
 * Idempotency-check primitive used by {@link IdempotentConsumer} to decide whether a record has
 * already been processed. Mirrors {@code packages/go-domain}'s {@code Deduper} interface (Go's own
 * forward-declared idempotency primitive, "the real Redis-backed implementation lands in
 * TICKET-008") — Java has no equivalent shared interface yet, so this is that role for the Java
 * side, pending TICKET-008 consolidation.
 */
public interface Deduplicator {

  /**
   * Records {@code key} if it has not been seen before and reports whether it was already
   * present. Implementations must make this atomic (e.g. Redis {@code SET key val NX}) so
   * concurrent callers with the same key never both observe "not seen."
   *
   * @return true if {@code key} was already recorded (a duplicate — skip); false if this call
   *     recorded it for the first time (proceed).
   */
  boolean seenBefore(String key);
}
