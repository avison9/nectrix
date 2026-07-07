// Package redisdeduper is a TEMPORARY, self-contained implementation of
// packages/go-domain's Deduper interface, using a single SET key 1 NX EX ttl
// call — no cluster-aware routing, no shared connection-pool conventions.
// Must be replaced by TICKET-008's canonical shared idempotency helper once
// that ticket lands (packages/go-domain/idempotency.go's own comment already
// flags this: "the real Redis-backed implementation lands in TICKET-008").
// Mirrors packages/event-contracts/java's RedisDeduplicator, same precedent.
package redisdeduper

import (
	"context"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/redis/go-redis/v9"
)

const keyPrefix = "events:dedup:"

// Compile-time assertion that Deduper satisfies domain.Deduper.
var _ domain.Deduper = (*Deduper)(nil)

// Deduper implements domain.Deduper using go-redis.
type Deduper struct {
	client *redis.Client
	ttl    time.Duration
}

func New(client *redis.Client, ttl time.Duration) *Deduper {
	return &Deduper{client: client, ttl: ttl}
}

// SeenBefore reports whether key was already recorded before this call. True
// = duplicate (already present); false = newly recorded, proceed.
func (d *Deduper) SeenBefore(ctx context.Context, key string) (bool, error) {
	// SetNX returns true if the key was set (not seen before), false if it
	// already existed (a duplicate) — the exact inverse of SeenBefore's own
	// return convention, so this must be negated.
	set, err := d.client.SetNX(ctx, keyPrefix+key, "1", d.ttl).Result()
	if err != nil {
		return false, err
	}
	return !set, nil
}
