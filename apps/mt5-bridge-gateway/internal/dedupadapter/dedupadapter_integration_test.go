//go:build integration

// Mirrors apps/broker-adapters' own identical integration test: duplicate
// PlaceOrder calls with the same idempotency key must not create duplicate
// positions, proven against a real local Redis (docker-compose.yml), not the
// fakeDeduper used by the fast unit tests in dedupadapter_test.go.
package dedupadapter_test

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/dedupadapter"
	domain "github.com/avison9/nectrix/go-domain"
	redisclient "github.com/avison9/nectrix/redis-client/go"
	"github.com/google/uuid"
)

func TestConcurrentDuplicatePlaceOrderCalls_ExactlyOneReachesTheBroker(t *testing.T) {
	ctx := context.Background()
	client, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		t.Fatalf("new redis client: %v", err)
	}
	deduper := redisclient.NewDeduper(client, time.Minute)

	inner := &fakeBrokerAdapter{}
	a := dedupadapter.New(inner, deduper)
	order := domain.NormalizedOrderRequest{IdempotencyKey: "mt-bridge-race-" + uuid.NewString()}

	const goroutines = 50
	var ready sync.WaitGroup
	ready.Add(goroutines)
	start := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(goroutines)
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			ready.Done()
			<-start
			_, _ = a.PlaceOrder(ctx, domain.ConnectionHandle{}, order)
		}()
	}
	ready.Wait()
	close(start)
	wg.Wait()

	if got := inner.calls(); got != 1 {
		t.Fatalf("wrapped adapter PlaceOrder called %d times against real Redis under concurrent duplicates, want exactly 1", got)
	}
}
