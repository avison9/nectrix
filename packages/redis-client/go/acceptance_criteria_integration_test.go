//go:build integration

// TICKET-008's real, hands-on verification of AC2/AC3/AC4 against a live local Redis
// (docker-compose.yml) — see the ticket's own plan for the exact scope decisions. Mirrors
// packages/redis-client/java's AcceptanceCriteriaIntegrationTest exactly, same three tests.
package redisclient_test

import (
	"context"
	"sync"
	"testing"
	"time"

	redisclient "github.com/avison9/nectrix/redis-client/go"
	"github.com/google/uuid"
)

// AC2: "Idempotency helper demonstrably prevents a duplicate side-effect when the same key is
// submitted twice in rapid succession (race-condition test, not just sequential)." N goroutines
// all call SeenBefore with the identical key, released simultaneously via a WaitGroup start
// barrier — exactly one must observe "not seen," every other goroutine must observe "duplicate."
func TestAC2_ConcurrentSameKeyCalls_ExactlyOneWinsTheRace(t *testing.T) {
	ctx := context.Background()
	client, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	dedup := redisclient.NewDeduper(client, time.Minute)
	key := "ac2-race-" + uuid.NewString()

	const goroutines = 50
	var ready sync.WaitGroup
	ready.Add(goroutines)
	start := make(chan struct{})
	results := make([]bool, goroutines)
	var wg sync.WaitGroup
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		go func(idx int) {
			defer wg.Done()
			ready.Done()
			<-start
			seen, err := dedup.SeenBefore(ctx, key)
			if err != nil {
				t.Errorf("SeenBefore: %v", err)
				return
			}
			results[idx] = seen
		}(i)
	}

	ready.Wait()
	close(start)
	wg.Wait()

	notSeenCount := 0
	seenCount := 0
	for _, seen := range results {
		if seen {
			seenCount++
		} else {
			notSeenCount++
		}
	}

	if notSeenCount != 1 {
		t.Fatalf("notSeenCount = %d, want exactly 1 (exactly one caller must win the race)", notSeenCount)
	}
	if seenCount != goroutines-1 {
		t.Fatalf("seenCount = %d, want %d", seenCount, goroutines-1)
	}
}

// AC3: "Rate-limiting helper enforces a configurable limit accurately under concurrent load
// (load-test with concurrent requests)." Zero refill rate isolates pure capacity enforcement —
// with no tokens ever added back, exactly capacity of the concurrent requests must succeed,
// deterministically, regardless of how many goroutines race for the bucket.
func TestAC3_ConcurrentLoad_EnforcesCapacityExactly(t *testing.T) {
	ctx := context.Background()
	client, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	limiter := redisclient.NewRateLimiter(client)
	key := "ac3-load-" + uuid.NewString()
	const capacity = 10
	const concurrentRequests = 50

	var ready sync.WaitGroup
	ready.Add(concurrentRequests)
	start := make(chan struct{})
	results := make([]bool, concurrentRequests)
	var wg sync.WaitGroup
	wg.Add(concurrentRequests)

	for i := 0; i < concurrentRequests; i++ {
		go func(idx int) {
			defer wg.Done()
			ready.Done()
			<-start
			ok, err := limiter.TryConsume(ctx, key, capacity, 0.0)
			if err != nil {
				t.Errorf("TryConsume: %v", err)
				return
			}
			results[idx] = ok
		}(i)
	}

	ready.Wait()
	close(start)
	wg.Wait()

	successCount := 0
	for _, ok := range results {
		if ok {
			successCount++
		}
	}

	if successCount != capacity {
		t.Fatalf("successCount = %d, want exactly %d (capacity, under concurrent load with zero refill)", successCount, capacity)
	}
}

// AC4: "A Redis flush/restart does not lose any data that isn't recoverable from Postgres
// (demonstrate by flushing Redis in a test environment and confirming the system recovers via
// reconciliation/cache-rebuild rather than silently misbehaving)." The real reconciliation job is
// explicitly out of scope for this ticket (Phase 1, docs/08-copy-trading-engine.md §8.9) — what's
// proven here is the narrower, honest property this ticket actually owns: after a flush, both
// helpers keep working correctly with no crash/corruption. Losing dedup/rate-limit state on flush
// is expected (Redis is fast-path-only by design), not "silently misbehaving."
func TestAC4_RedisFlushMidTest_BothHelpersRecoverCleanlyAfterward(t *testing.T) {
	ctx := context.Background()
	client, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	dedup := redisclient.NewDeduper(client, time.Minute)
	limiter := redisclient.NewRateLimiter(client)

	dedupKey := "ac4-dedup-" + uuid.NewString()
	rateLimitKey := "ac4-ratelimit-" + uuid.NewString()

	mustSeenBefore(t, ctx, dedup, dedupKey, false)
	mustSeenBefore(t, ctx, dedup, dedupKey, true)
	mustTryConsume(t, ctx, limiter, rateLimitKey, true)
	mustTryConsume(t, ctx, limiter, rateLimitKey, true)
	mustTryConsume(t, ctx, limiter, rateLimitKey, false) // bucket exhausted pre-flush

	if err := client.FlushAll(ctx).Err(); err != nil {
		t.Fatalf("FlushAll: %v", err)
	}

	// Post-flush: no exception, no corruption. The SAME dedup key is treated as brand-new
	// (expected) and the SAME rate-limit key gets a fresh, full bucket (also expected).
	mustSeenBefore(t, ctx, dedup, dedupKey, false)
	mustTryConsume(t, ctx, limiter, rateLimitKey, true)
	mustTryConsume(t, ctx, limiter, rateLimitKey, true)
	mustTryConsume(t, ctx, limiter, rateLimitKey, false)
}

func mustSeenBefore(t *testing.T, ctx context.Context, dedup *redisclient.Deduper, key string, want bool) {
	t.Helper()
	got, err := dedup.SeenBefore(ctx, key)
	if err != nil {
		t.Fatalf("SeenBefore: %v", err)
	}
	if got != want {
		t.Fatalf("SeenBefore(%q) = %v, want %v", key, got, want)
	}
}

func mustTryConsume(t *testing.T, ctx context.Context, limiter *redisclient.RateLimiter, key string, want bool) {
	t.Helper()
	got, err := limiter.TryConsume(ctx, key, 2, 0.0)
	if err != nil {
		t.Fatalf("TryConsume: %v", err)
	}
	if got != want {
		t.Fatalf("TryConsume(%q) = %v, want %v", key, got, want)
	}
}
