package redisclient

import (
	"context"

	"github.com/redis/go-redis/v9"
)

const rateLimitKeyPrefix = "ratelimit:"

// tokenBucketScript mirrors packages/redis-client/java's TokenBucketRateLimiter exactly — same
// lazy-refill-against-server-TIME design, same single-hash-key shape, same reasoning for why this
// is plain EVAL every call rather than a cached-SHA EVALSHA dance (Redis's script cache is
// per-shard in cluster mode; a SHA cached via one key's node isn't guaranteed present on a
// different key's node, so client-side SHA caching — including go-redis's own *redis.Script.Run
// helper — is a real correctness hazard here, not just a missed optimization).
const tokenBucketScript = `
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + tonumber(now[2]) / 1000

local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens, last = tonumber(b[1]), tonumber(b[2])
if tokens == nil then
  tokens, last = capacity, now_ms
end

tokens = math.min(capacity, tokens + (now_ms - last) / 1000 * rate)

local allowed = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
-- rate == 0 is a legitimate "fixed allowance, never refills" bucket (used
-- by, e.g., the AC3 concurrency test) — capacity/rate would be a division
-- by zero (Lua evaluates it to inf, which PEXPIRE then rejects as "not an
-- integer"), so fall back to a fixed 1-hour TTL for that case instead of
-- computing a refill-based one.
local ttl_ms
if rate > 0 then
  ttl_ms = math.ceil(capacity / rate * 1000) + 1000
else
  ttl_ms = 3600000
end
redis.call('PEXPIRE', KEYS[1], ttl_ms)
return allowed
`

// RateLimiter is the token-bucket rate limiter (docs/14-api-specification.md §14.12 specifies
// token-bucket, not a fixed-window counter). Atomicity comes from Redis's single-threaded command
// execution — the whole script runs as one unit, so concurrent TryConsume calls on the same key
// are fully serialized server-side, no application-level locking needed.
type RateLimiter struct {
	client redis.Cmdable
}

func NewRateLimiter(client redis.Cmdable) *RateLimiter {
	return &RateLimiter{client: client}
}

// TryConsume attempts to consume one token from key's bucket (capacity, refilling at
// refillPerSecond tokens/second). Returns true if a token was available and consumed.
func (r *RateLimiter) TryConsume(ctx context.Context, key string, capacity int, refillPerSecond float64) (bool, error) {
	keys := []string{rateLimitKeyPrefix + key}
	args := []interface{}{capacity, refillPerSecond, 1}
	result, err := r.client.Eval(ctx, tokenBucketScript, keys, args...).Result()
	if err != nil {
		return false, err
	}
	allowed, ok := result.(int64)
	return ok && allowed == 1, nil
}
