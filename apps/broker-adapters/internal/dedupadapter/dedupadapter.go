// Package dedupadapter decorates a domain.BrokerAdapter with a Redis-backed
// idempotency guard in front of PlaceOrder. Broker-side client-tag matching
// (see internal/ctrader/orders.go's own doc-comment) isn't a reliable
// server-side dedup guarantee, so this platform's own guarantee has to be
// checked BEFORE the wrapped adapter ever talks to the real broker — a
// duplicate call must never risk opening a second real position.
package dedupadapter

import (
	"context"
	"fmt"

	domain "github.com/avison9/nectrix/go-domain"
)

const dedupKeyPrefix = "broker-adapters:place-order:"

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
// calling the wrapped adapter. A duplicate is not an error — the first call
// already handled it — so this returns a plain unsuccessful result (no
// BrokerPositionID/FilledPrice to fabricate) rather than an error, matching
// how a genuine broker rejection is reported.
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
