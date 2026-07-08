package com.nectrix.redisclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

/**
 * TICKET-008's real, hands-on verification of AC2/AC3/AC4 against a live local Redis
 * (docker-compose.yml) — see the ticket's own plan for the exact scope decisions.
 */
@Tag("integration")
class AcceptanceCriteriaIntegrationTest {

  /**
   * AC2: "Idempotency helper demonstrably prevents a duplicate side-effect when the same key is
   * submitted twice in rapid succession (race-condition test, not just sequential)." N threads all
   * call {@code seenBefore} with the *identical* key, released simultaneously via a {@link
   * CountDownLatch} barrier — exactly one must observe "not seen," every other thread must observe
   * "duplicate," regardless of scheduling order.
   */
  @Test
  void ac2_concurrentSameKeyCalls_exactlyOneWinsTheRace() throws Exception {
    UnifiedJedis jedis = RedisClientFactory.create(RedisClientConfig.fromEnv());
    Deduplicator dedup = new RedisDeduplicator(jedis, Duration.ofMinutes(1));
    String key = "ac2-race-" + UUID.randomUUID();

    int threadCount = 50;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);

    List<Callable<Boolean>> tasks =
        java.util.stream.IntStream.range(0, threadCount)
            .<Callable<Boolean>>mapToObj(
                i ->
                    () -> {
                      ready.countDown();
                      start.await();
                      return dedup.seenBefore(key);
                    })
            .toList();

    List<Future<Boolean>> futures =
        tasks.stream().map(pool::submit).toList();
    ready.await(5, TimeUnit.SECONDS);
    start.countDown();

    long notSeenCount = 0;
    long seenCount = 0;
    for (Future<Boolean> future : futures) {
      boolean wasSeenBefore = future.get(5, TimeUnit.SECONDS);
      if (wasSeenBefore) {
        seenCount++;
      } else {
        notSeenCount++;
      }
    }
    pool.shutdown();

    assertThat(notSeenCount).as("exactly one caller must win the race (observe 'not seen')").isEqualTo(1);
    assertThat(seenCount).isEqualTo(threadCount - 1);
  }

  /**
   * AC3: "Rate-limiting helper enforces a configurable limit accurately under concurrent load
   * (load-test with concurrent requests)." Zero refill rate isolates pure capacity enforcement —
   * with no tokens ever added back, exactly {@code capacity} of the concurrent requests must
   * succeed, deterministically, regardless of how many threads race for the bucket.
   */
  @Test
  void ac3_concurrentLoad_enforcesCapacityExactly() throws Exception {
    UnifiedJedis jedis = RedisClientFactory.create(RedisClientConfig.fromEnv());
    RateLimiter limiter = new TokenBucketRateLimiter(jedis);
    String key = "ac3-load-" + UUID.randomUUID();
    int capacity = 10;
    int concurrentRequests = 50;

    CountDownLatch ready = new CountDownLatch(concurrentRequests);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);

    List<Callable<Boolean>> tasks =
        java.util.stream.IntStream.range(0, concurrentRequests)
            .<Callable<Boolean>>mapToObj(
                i ->
                    () -> {
                      ready.countDown();
                      start.await();
                      return limiter.tryConsume(key, capacity, 0.0);
                    })
            .toList();

    List<Future<Boolean>> futures = tasks.stream().map(pool::submit).toList();
    ready.await(5, TimeUnit.SECONDS);
    start.countDown();

    AtomicInteger successCount = new AtomicInteger();
    for (Future<Boolean> future : futures) {
      if (future.get(5, TimeUnit.SECONDS)) {
        successCount.incrementAndGet();
      }
    }
    pool.shutdown();

    assertThat(successCount.get())
        .as("exactly capacity requests should succeed under concurrent load with zero refill")
        .isEqualTo(capacity);
  }

  /**
   * AC4: "A Redis flush/restart does not lose any data that isn't recoverable from Postgres
   * (demonstrate by flushing Redis in a test environment and confirming the system recovers via
   * reconciliation/cache-rebuild rather than silently misbehaving)." The real reconciliation job is
   * explicitly out of scope for this ticket (Phase 1, docs/08-copy-trading-engine.md §8.9) — what's
   * proven here is the narrower, honest property this ticket actually owns: after a flush, both
   * helpers keep working correctly with no crash/corruption. Losing dedup/rate-limit state on flush
   * is expected (Redis is fast-path-only by design, docs/15-event-driven-architecture.md §15.5),
   * not "silently misbehaving."
   */
  @Test
  void ac4_redisFlushMidTest_bothHelpersRecoverCleanlyAfterward() {
    UnifiedJedis jedis = RedisClientFactory.create(RedisClientConfig.fromEnv());
    Deduplicator dedup = new RedisDeduplicator(jedis, Duration.ofMinutes(1));
    RateLimiter limiter = new TokenBucketRateLimiter(jedis);

    String dedupKey = "ac4-dedup-" + UUID.randomUUID();
    String rateLimitKey = "ac4-ratelimit-" + UUID.randomUUID();

    assertThat(dedup.seenBefore(dedupKey)).isFalse();
    assertThat(dedup.seenBefore(dedupKey)).isTrue();
    assertThat(limiter.tryConsume(rateLimitKey, 2, 0.0)).isTrue();
    assertThat(limiter.tryConsume(rateLimitKey, 2, 0.0)).isTrue();
    assertThat(limiter.tryConsume(rateLimitKey, 2, 0.0)).isFalse(); // bucket exhausted pre-flush

    jedis.flushAll();

    // Post-flush: no exception, no corruption. The SAME dedup key is treated as brand-new
    // (expected — Redis lost its fast-path memory; this is exactly why Postgres-backed callers
    // must never treat Redis as their sole guard) and the SAME rate-limit key gets a fresh,
    // full bucket (also expected — not "silently misbehaving," a clean, predictable reset).
    assertThat(dedup.seenBefore(dedupKey)).isFalse();
    assertThat(limiter.tryConsume(rateLimitKey, 2, 0.0)).isTrue();
    assertThat(limiter.tryConsume(rateLimitKey, 2, 0.0)).isTrue();
    assertThat(limiter.tryConsume(rateLimitKey, 2, 0.0)).isFalse();
  }
}
