package redisclient

import (
	"context"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/redis/go-redis/v9"
)

const dedupKeyPrefix = "events:dedup:"

// Compile-time assertion that Deduper satisfies domain.Deduper.
var _ domain.Deduper = (*Deduper)(nil)

// Deduper is the canonical Redis-backed implementation of packages/go-domain's Deduper interface
// (that interface's own comment: "the real Redis-backed implementation lands in TICKET-008").
// Fast-path only per docs/15-event-driven-architecture.md §15.5 — Redis is never the sole guard
// for anything that matters financially; callers with a durable-storage path (a real DB unique
// constraint) must still rely on that as the ultimate guard.
type Deduper struct {
	client redis.Cmdable
	ttl    time.Duration
}

func NewDeduper(client redis.Cmdable, ttl time.Duration) *Deduper {
	return &Deduper{client: client, ttl: ttl}
}

// SeenBefore reports whether key was already recorded before this call. True = duplicate (already
// present); false = newly recorded, proceed. Single SET key val NX EX ttl command (via SetNX) —
// already atomic, no Lua script needed.
func (d *Deduper) SeenBefore(ctx context.Context, key string) (bool, error) {
	set, err := d.client.SetNX(ctx, dedupKeyPrefix+key, "1", d.ttl).Result()
	if err != nil {
		return false, err
	}
	return !set, nil
}
