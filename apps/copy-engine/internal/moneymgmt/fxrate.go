package moneymgmt

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// FXRateProvider: 1 unit of `from` == Rate(from, to) units of `to`.
// Implementations MUST short-circuit from==to to 1.0 with zero network
// calls -- most sizing computations are same-currency in practice, and must
// not pay a round trip for the common case.
type FXRateProvider interface {
	Rate(ctx context.Context, from, to string) (float64, error)
}

type cachedRate struct {
	rate      float64
	fetchedAt time.Time
}

// fxCacheTTL: Frankfurter/ECB rates only update once daily (~16:00 CET);
// an hour is generous headroom against hammering the API on every trade
// signal without serving day-old data for most of the day.
const fxCacheTTL = time.Hour

// frankfurterClient is a real HTTP client against api.frankfurter.dev (free,
// keyless, ECB daily reference rates) -- sufficient for account-currency
// conversion in sizing math (not trade pricing itself); the once-daily
// update cadence is normal for this use case, not a limitation that matters
// here. Caching shape mirrors
// apps/mt5-bridge-gateway/internal/mtadapter/symbols.go's symbolCache
// exactly: a sync.RWMutex-guarded map, adapter/client-wide.
type frankfurterClient struct {
	httpClient *http.Client
	baseURL    string
	logger     *slog.Logger

	mu    sync.RWMutex
	cache map[string]cachedRate
}

// NewFrankfurterClient builds a real FXRateProvider. httpClient/logger
// default to http.DefaultClient/slog.Default() when nil.
func NewFrankfurterClient(httpClient *http.Client, logger *slog.Logger) FXRateProvider {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &frankfurterClient{
		httpClient: httpClient,
		baseURL:    "https://api.frankfurter.dev/v1/latest",
		logger:     logger,
		cache:      make(map[string]cachedRate),
	}
}

func cacheKey(from, to string) string { return from + "|" + to }

// Rate implements the three-tier fallback documented in this ticket's plan:
//  1. A fresh (within-TTL) cached rate exists -> serve it, no network call.
//  2. Network/HTTP failure, but ANY previously-fetched value exists (even
//     stale) -> serve it and log a WARN -- a Frankfurter blip must not halt
//     copy-trading platform-wide.
//  3. No cached value at all (cold-start outage) -> return a wrapped error
//     -- nothing safe to fall back to; silently guessing an FX rate for
//     money-sizing math is worse than rejecting the signal.
func (c *frankfurterClient) Rate(ctx context.Context, from, to string) (float64, error) {
	if from == to {
		return 1, nil
	}

	key := cacheKey(from, to)

	if cached, ok := c.getCached(key); ok && time.Since(cached.fetchedAt) < fxCacheTTL {
		return cached.rate, nil
	}

	rate, err := c.fetch(ctx, from, to)
	if err != nil {
		if cached, ok := c.getCached(key); ok {
			c.logger.Warn("moneymgmt: fx rate fetch failed, serving stale cached rate",
				"from", from, "to", to, "error", err, "cachedAt", cached.fetchedAt)
			return cached.rate, nil
		}
		return 0, fmt.Errorf("moneymgmt: fx rate lookup for %s->%s failed with no cached fallback: %w", from, to, err)
	}

	c.putCached(key, cachedRate{rate: rate, fetchedAt: time.Now()})
	return rate, nil
}

func (c *frankfurterClient) getCached(key string) (cachedRate, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	v, ok := c.cache[key]
	return v, ok
}

func (c *frankfurterClient) putCached(key string, v cachedRate) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cache[key] = v
}

type frankfurterResponse struct {
	Amount float64            `json:"amount"`
	Base   string             `json:"base"`
	Date   string             `json:"date"`
	Rates  map[string]float64 `json:"rates"`
}

func (c *frankfurterClient) fetch(ctx context.Context, from, to string) (float64, error) {
	u := fmt.Sprintf("%s?base=%s&symbols=%s", c.baseURL, url.QueryEscape(from), url.QueryEscape(to))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return 0, fmt.Errorf("build frankfurter request: %w", err)
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return 0, fmt.Errorf("frankfurter request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return 0, fmt.Errorf("frankfurter: unexpected status %d: %s", resp.StatusCode, string(body))
	}

	var parsed frankfurterResponse
	if err := json.NewDecoder(resp.Body).Decode(&parsed); err != nil {
		return 0, fmt.Errorf("decode frankfurter response: %w", err)
	}

	rate, ok := parsed.Rates[to]
	if !ok {
		return 0, fmt.Errorf("frankfurter: response missing rate for %q", to)
	}
	return rate, nil
}
