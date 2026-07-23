//go:build integration

// TICKET-124 — proves the new POST /internal/pnl/unrealized-batch endpoint computes the exact
// same figure computeRealizedPnL already established (mirroring
// realizedpnl_integration_test.go's own hand-computed BUY/SELL verification style), and that the
// endpoint rejects requests without a valid internal service token.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	domain "github.com/avison9/nectrix/go-domain"
)

func TestUnrealizedPnLBatch_Buy_MatchesRealizedPnlFormula(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	const openPrice = 1.10000
	const currentPrice = 1.10080 // +80 pips, a BUY gains when price rises

	items := []pipeline.UnrealizedPnLItem{{
		ID:                      "row-1",
		FollowerBrokerAccountID: followerAccountID,
		CanonicalSymbol:         "EURUSD",
		AssetClass:              string(domain.AssetClassFX),
		Direction:               string(domain.TradeDirectionBuy),
		VolumeLots:              1.0,
		OpenPrice:               openPrice,
		CurrentPrice:            currentPrice,
	}}

	results := postUnrealizedPnLBatch(t, server, dispatchTestSharedSecret, items)
	if len(results) != 1 {
		t.Fatalf("got %d results, want 1", len(results))
	}
	if results[0].ID != "row-1" {
		t.Fatalf("result ID = %q, want %q (correlation key must be echoed back)", results[0].ID, "row-1")
	}
	if results[0].UnrealizedPnl == nil {
		t.Fatalf("unrealizedPnl is nil, want a real computed value")
	}
	// EURUSD per seedDispatchFixture's own symbol_mappings row: contract_size=100000.
	want := (currentPrice - openPrice) * 100000 * 1.0
	if !approxEqual(*results[0].UnrealizedPnl, want) {
		t.Fatalf("unrealizedPnl = %v, want %v (hand-computed BUY case)", *results[0].UnrealizedPnl, want)
	}
}

func TestUnrealizedPnLBatch_Sell_MatchesRealizedPnlFormula(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	const openPrice = 1.10000
	const currentPrice = 1.09920 // -80 pips, a SELL gains when price falls

	items := []pipeline.UnrealizedPnLItem{{
		ID:                      "row-2",
		FollowerBrokerAccountID: followerAccountID,
		CanonicalSymbol:         "EURUSD",
		AssetClass:              string(domain.AssetClassFX),
		Direction:               string(domain.TradeDirectionSell),
		VolumeLots:              1.0,
		OpenPrice:               openPrice,
		CurrentPrice:            currentPrice,
	}}

	results := postUnrealizedPnLBatch(t, server, dispatchTestSharedSecret, items)
	if len(results) != 1 || results[0].UnrealizedPnl == nil {
		t.Fatalf("got %+v, want exactly one result with a non-nil unrealizedPnl", results)
	}
	want := (openPrice - currentPrice) * 100000 * 1.0
	if !approxEqual(*results[0].UnrealizedPnl, want) {
		t.Fatalf("unrealizedPnl = %v, want %v (hand-computed SELL case)", *results[0].UnrealizedPnl, want)
	}
}

func TestUnrealizedPnLBatch_UnmappedSymbol_ReturnsNilNotError(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	items := []pipeline.UnrealizedPnLItem{{
		ID:                      "row-3",
		FollowerBrokerAccountID: followerAccountID,
		CanonicalSymbol:         "XAUUSD", // no symbol_mappings row seeded for this one
		AssetClass:              string(domain.AssetClassCommodity),
		Direction:               string(domain.TradeDirectionBuy),
		VolumeLots:              1.0,
		OpenPrice:               2000.0,
		CurrentPrice:            2010.0,
	}}

	results := postUnrealizedPnLBatch(t, server, dispatchTestSharedSecret, items)
	if len(results) != 1 {
		t.Fatalf("got %d results, want 1", len(results))
	}
	if results[0].UnrealizedPnl != nil {
		t.Fatalf("unrealizedPnl = %v, want nil for an unmapped symbol (never a fabricated value)", *results[0].UnrealizedPnl)
	}
}

func TestUnrealizedPnLBatch_WithoutValidToken_IsRejected(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, _, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	body, _ := json.Marshal([]pipeline.UnrealizedPnLItem{})
	req, err := http.NewRequest(http.MethodPost, server.URL+"/internal/pnl/unrealized-batch", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	req.Header.Set("X-Internal-Service-Token", "wrong-token")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusUnauthorized)
	}
}

func postUnrealizedPnLBatch(t *testing.T, server *httptest.Server, token string, items []pipeline.UnrealizedPnLItem) []pipeline.UnrealizedPnLResult {
	t.Helper()
	body, err := json.Marshal(items)
	if err != nil {
		t.Fatalf("marshal items: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, server.URL+"/internal/pnl/unrealized-batch", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	req.Header.Set("X-Internal-Service-Token", token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var results []pipeline.UnrealizedPnLResult
	if err := json.NewDecoder(resp.Body).Decode(&results); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	return results
}
