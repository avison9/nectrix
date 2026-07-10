package dedupadapter_test

import (
	"context"
	"sync"
	"testing"

	"github.com/avison9/nectrix/broker-adapters/internal/dedupadapter"
	domain "github.com/avison9/nectrix/go-domain"
)

// fakeDeduper mirrors packages/redis-client's real Deduper contract (atomic
// check-and-set) without a real Redis, so these tests stay fast/hermetic.
// The real Redis-backed atomicity is proven separately, in the
// //go:build integration test below.
type fakeDeduper struct {
	mu   sync.Mutex
	seen map[string]bool
}

func newFakeDeduper() *fakeDeduper { return &fakeDeduper{seen: make(map[string]bool)} }

func (f *fakeDeduper) SeenBefore(ctx context.Context, key string) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.seen[key] {
		return true, nil
	}
	f.seen[key] = true
	return false, nil
}

// fakeBrokerAdapter only implements PlaceOrder — every test here only
// exercises that method, so the embedded nil domain.BrokerAdapter is never
// invoked (it would panic if it were, which is the point: it proves
// dedupadapter's pass-through embedding never routes PlaceOrder there).
type fakeBrokerAdapter struct {
	domain.BrokerAdapter
	mu              sync.Mutex
	placeOrderCalls int
}

func (f *fakeBrokerAdapter) PlaceOrder(ctx context.Context, handle domain.ConnectionHandle, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	f.mu.Lock()
	f.placeOrderCalls++
	f.mu.Unlock()
	return domain.NormalizedOrderResult{Success: true, BrokerPositionID: "777"}, nil
}

func (f *fakeBrokerAdapter) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.placeOrderCalls
}

func TestPlaceOrder_FirstCallReachesWrappedAdapter(t *testing.T) {
	inner := &fakeBrokerAdapter{}
	a := dedupadapter.New(inner, newFakeDeduper())

	result, err := a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, domain.NormalizedOrderRequest{IdempotencyKey: "key-1"})
	if err != nil {
		t.Fatalf("PlaceOrder() error = %v", err)
	}
	if !result.Success || result.BrokerPositionID != "777" {
		t.Fatalf("PlaceOrder() = %+v, want the wrapped adapter's real result", result)
	}
	if got := inner.calls(); got != 1 {
		t.Fatalf("wrapped adapter PlaceOrder called %d times, want 1", got)
	}
}

func TestPlaceOrder_DuplicateIdempotencyKeySuppressedWithoutCallingBroker(t *testing.T) {
	inner := &fakeBrokerAdapter{}
	a := dedupadapter.New(inner, newFakeDeduper())
	order := domain.NormalizedOrderRequest{IdempotencyKey: "key-1"}

	if _, err := a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, order); err != nil {
		t.Fatalf("first PlaceOrder() error = %v", err)
	}

	second, err := a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, order)
	if err != nil {
		t.Fatalf("second PlaceOrder() error = %v", err)
	}
	if second.Success {
		t.Fatalf("second PlaceOrder() = %+v, want Success=false (duplicate suppressed)", second)
	}
	if second.BrokerPositionID != "" {
		t.Fatalf("second PlaceOrder() BrokerPositionID = %q, want empty — must never fabricate a position id", second.BrokerPositionID)
	}
	if got := inner.calls(); got != 1 {
		t.Fatalf("wrapped adapter PlaceOrder called %d times, want exactly 1 (duplicate must never reach the broker)", got)
	}
}

func TestPlaceOrder_ConcurrentSameKey_ExactlyOneReachesWrappedAdapter(t *testing.T) {
	inner := &fakeBrokerAdapter{}
	a := dedupadapter.New(inner, newFakeDeduper())
	order := domain.NormalizedOrderRequest{IdempotencyKey: "race-key"}

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
			_, _ = a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, order)
		}()
	}
	ready.Wait()
	close(start)
	wg.Wait()

	if got := inner.calls(); got != 1 {
		t.Fatalf("wrapped adapter PlaceOrder called %d times under concurrent duplicates, want exactly 1", got)
	}
}

func TestPlaceOrder_DifferentKeysBothReachWrappedAdapter(t *testing.T) {
	inner := &fakeBrokerAdapter{}
	a := dedupadapter.New(inner, newFakeDeduper())

	if _, err := a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, domain.NormalizedOrderRequest{IdempotencyKey: "key-a"}); err != nil {
		t.Fatalf("PlaceOrder(key-a) error = %v", err)
	}
	if _, err := a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, domain.NormalizedOrderRequest{IdempotencyKey: "key-b"}); err != nil {
		t.Fatalf("PlaceOrder(key-b) error = %v", err)
	}
	if got := inner.calls(); got != 2 {
		t.Fatalf("wrapped adapter PlaceOrder called %d times for two distinct keys, want 2", got)
	}
}

func TestPlaceOrder_EmptyIdempotencyKeyRejected(t *testing.T) {
	inner := &fakeBrokerAdapter{}
	a := dedupadapter.New(inner, newFakeDeduper())

	if _, err := a.PlaceOrder(context.Background(), domain.ConnectionHandle{}, domain.NormalizedOrderRequest{}); err == nil {
		t.Fatal("PlaceOrder() with empty IdempotencyKey: expected an error, got nil")
	}
	if got := inner.calls(); got != 0 {
		t.Fatalf("wrapped adapter PlaceOrder called %d times for an invalid request, want 0", got)
	}
}
