package com.nectrix.redisclient;

/**
 * Idempotency-check primitive (docs/15-event-driven-architecture.md §15.5 — fast-path dedup) —
 * mirrors {@code packages/go-domain}'s {@code Deduper} interface exactly (same contract, same
 * name intentionally different to avoid a same-named-but-different-package collision when both are
 * imported side by side in this monorepo's docs/examples).
 *
 * <p>Originally lived in {@code packages/event-contracts/java} (TICKET-007, that ticket's first
 * consumer) — moved here (TICKET-008) because it's a generic Redis primitive with nothing
 * event-specific about it, the same separation Go already had from day one ({@code go-domain}'s
 * {@code Deduper} is independent of {@code event-contracts/go}, which merely depends on it).
 */
public interface Deduplicator {

  /**
   * Records {@code key} if it has not been seen before and reports whether it was already
   * present. Implementations must make this atomic (a single Redis {@code SET key val NX EX ttl}
   * command, never a separate {@code SET}+{@code EXPIRE} pair) so concurrent callers with the same
   * key never both observe "not seen."
   *
   * @return true if {@code key} was already recorded (a duplicate — skip); false if this call
   *     recorded it for the first time (proceed).
   */
  boolean seenBefore(String key);
}
