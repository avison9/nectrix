// Package dedupadapter decorates a domain.BrokerAdapter with a Redis-backed
// idempotency guard in front of PlaceOrder — the exact counterpart of
// apps/broker-adapters/internal/dedupadapter (TICKET-101). Duplicated rather
// than imported for the same Go internal-package-visibility reason
// documented in this service's internal/tradesignals package.
package dedupadapter

import (
	"context"
	"fmt"

	domain "github.com/avison9/nectrix/go-domain"
)

const dedupKeyPrefix = "mt5-bridge-gateway:place-order:"

// Adapter wraps a domain.BrokerAdapter, embedding it so every method other
// than PlaceOrder passes straight through unchanged.
type Adapter struct {
	domain.BrokerAdapter
	deduper domain.Deduper
}

var _ domain.BrokerAdapter = (*Adapter)(nil)

func New(wrapped domain.BrokerAdapter, deduper domain.Deduper) *Adapter {
	return &Adapter{BrokerAdapter: wrapped, deduper: deduper}
}

// PlaceOrder checks order.IdempotencyKey against the dedup store before ever
// calling the wrapped adapter — an EA-bridge order command traveling over a
// WebSocket connection that can drop and retry mid-flight needs this same
// platform-side guarantee cTrader's own dedupadapter provides, since a
// duplicate call must never risk opening a second real position.
func (a *Adapter) PlaceOrder(ctx context.Context, handle domain.ConnectionHandle, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	if order.IdempotencyKey == "" {
		return domain.NormalizedOrderResult{}, fmt.Errorf("dedupadapter: PlaceOrder requires a non-empty IdempotencyKey")
	}

	seen, err := a.deduper.SeenBefore(ctx, dedupKeyPrefix+order.IdempotencyKey)
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("dedupadapter: dedup check: %w", err)
	}
	if seen {
		return domain.NormalizedOrderResult{
			Success:      false,
			RejectReason: "duplicate PlaceOrder suppressed: idempotency key already seen",
		}, nil
	}

	return a.BrokerAdapter.PlaceOrder(ctx, handle, order)
}
