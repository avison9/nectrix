package moneymgmt

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// panickingTransport is a fake http.RoundTripper that fails any real
// request -- used to prove the same-currency short-circuit genuinely never
// hits the network, not just that it's documented to.
type panickingTransport struct{}

func (panickingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, fmt.Errorf("panickingTransport: no request should ever be made")
}

func TestFrankfurterClient_SameCurrency_NoNetworkCall(t *testing.T) {
	client := NewFrankfurterClient(&http.Client{Transport: panickingTransport{}}, slog.Default())
	rate, err := client.Rate(context.Background(), "USD", "USD")
	if err != nil {
		t.Fatalf("Rate returned error: %v", err)
	}
	if rate != 1 {
		t.Fatalf("rate = %v, want 1", rate)
	}
}

func TestFrankfurterClient_FetchesAndCaches(t *testing.T) {
	var requestCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&requestCount, 1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"amount":1,"base":"EUR","date":"2026-07-13","rates":{"USD":1.08}}`))
	}))
	defer server.Close()

	fc := &frankfurterClient{httpClient: server.Client(), baseURL: server.URL, logger: slog.Default(), cache: make(map[string]cachedRate)}

	rate, err := fc.Rate(context.Background(), "EUR", "USD")
	if err != nil {
		t.Fatalf("Rate returned error: %v", err)
	}
	if rate != 1.08 {
		t.Fatalf("rate = %v, want 1.08", rate)
	}

	// Second call within TTL must be served from cache -- no second request.
	if _, err := fc.Rate(context.Background(), "EUR", "USD"); err != nil {
		t.Fatalf("second Rate call returned error: %v", err)
	}
	if got := atomic.LoadInt32(&requestCount); got != 1 {
		t.Fatalf("expected exactly 1 HTTP request (second call served from cache), got %d", got)
	}
}

func TestFrankfurterClient_FetchFailure_NoCachedFallback_ReturnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	fc := &frankfurterClient{httpClient: server.Client(), baseURL: server.URL, logger: slog.Default(), cache: make(map[string]cachedRate)}

	_, err := fc.Rate(context.Background(), "EUR", "USD")
	if err == nil {
		t.Fatal("expected an error when the fetch fails and there is no cached fallback")
	}
}

func TestFrankfurterClient_FetchFailure_ServesStaleCachedFallback(t *testing.T) {
	var fail atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if fail.Load() {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"amount":1,"base":"EUR","date":"2026-07-13","rates":{"USD":1.08}}`))
	}))
	defer server.Close()

	fc := &frankfurterClient{httpClient: server.Client(), baseURL: server.URL, logger: slog.Default(), cache: make(map[string]cachedRate)}

	// First call succeeds and populates the cache.
	if _, err := fc.Rate(context.Background(), "EUR", "USD"); err != nil {
		t.Fatalf("first Rate call returned error: %v", err)
	}

	// Force the cached entry to be stale (past TTL) so the next call
	// actually attempts a real re-fetch instead of serving the fresh cache.
	fc.putCached(cacheKey("EUR", "USD"), cachedRate{rate: 1.08, fetchedAt: time.Now().Add(-2 * fxCacheTTL)})
	fail.Store(true)

	rate, err := fc.Rate(context.Background(), "EUR", "USD")
	if err != nil {
		t.Fatalf("expected stale-cache fallback, got error: %v", err)
	}
	if rate != 1.08 {
		t.Fatalf("rate = %v, want stale cached 1.08", rate)
	}
}
