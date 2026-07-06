package domain

import "context"

// Deduper reports whether a key has already been recorded within a dedup
// window. The real Redis-backed implementation lands in TICKET-008; this
// interface lets earlier code (Copy Engine, Broker Adapters) depend on the
// idempotency primitive now rather than inventing an ad hoc one per caller.
type Deduper interface {
	// SeenBefore records key if it has not been seen before and reports
	// whether it was already present. Implementations must make this atomic
	// (e.g. Redis SETNX) so concurrent callers with the same key never both
	// observe "not seen."
	SeenBefore(ctx context.Context, key string) (bool, error)
}
