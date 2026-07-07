package com.nectrix.events.consumer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter, deliberately short-lived (the retry loop blocks the consumer's
 * next {@code poll()}/heartbeat — long backoff belongs on the DLQ side, not here). Default: 3
 * attempts, 200ms initial, 2s cap — bounded well under a typical consumer-group session timeout.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

  public static RetryPolicy exponential(int maxAttempts, Duration initial, Duration max) {
    return new RetryPolicy(maxAttempts, initial, max);
  }

  public static RetryPolicy defaultPolicy() {
    return exponential(3, Duration.ofMillis(200), Duration.ofSeconds(2));
  }

  /** Backoff duration before attempt number {@code attempt} (1-indexed, i.e. before the 2nd try). */
  Duration backoffFor(int attempt) {
    long baseMillis = initialBackoff.toMillis() * (1L << Math.max(0, attempt - 1));
    long cappedMillis = Math.min(baseMillis, maxBackoff.toMillis());
    long jitteredMillis = ThreadLocalRandom.current().nextLong(cappedMillis + 1);
    return Duration.ofMillis(jitteredMillis);
  }
}
