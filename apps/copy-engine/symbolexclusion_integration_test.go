//go:build integration

// Feature — copy_relationships.excluded_symbols (035-copy-relationship-symbol-exclusions.sql), a
// Follower-editable per-relationship symbol EXCLUSION list. These tests prove the two halves of
// dispatch.go's own design comment: a brand-new position in an excluded symbol never opens, but a
// position already copied before the exclusion was added still closes normally — excluding a
// symbol must never strand an already-copied position with no way to close it.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

func TestExcludedSymbol_BlocksNewOpen(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	mustExec(t, ctx, pool, `UPDATE copy_relationships SET excluded_symbols = '{EURUSD}' WHERE id = $1`, relationshipID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "exclusion-open-" + uuid.NewString()
	body, err := json.Marshal(stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	}) // Symbol left blank -- defaults to "EURUSD" (stubadapter.buildEvent), the excluded symbol.
	if err != nil {
		t.Fatalf("marshal inject params: %v", err)
	}
	resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("POST inject: %v", err)
	}
	resp.Body.Close()

	if got := fake.placeOrderCallCount(followerAccountID); got != 0 {
		t.Fatalf("PlaceOrder called %d times, want 0 -- EURUSD is excluded for this relationship, no new position should ever open", got)
	}

	var copiedTradesCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&copiedTradesCount); err != nil {
		t.Fatalf("query copied_trades count: %v", err)
	}
	if copiedTradesCount != 0 {
		t.Fatalf("copied_trades row count = %d, want 0", copiedTradesCount)
	}

	// The signal itself is still recorded (dedup/audit trail) -- only dispatch is skipped.
	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)
}

func TestExcludedSymbol_AlreadyOpenPositionStillCloses(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "exclusion-close-" + uuid.NewString()

	// 1. Open the position for real, no exclusion in effect yet.
	postInjectWithOpenPrice(t, server, brokerPositionID, time.Now().UTC().Format(time.RFC3339), 1.0, 1.2000)
	if got := fake.placeOrderCallCount(followerAccountID); got != 1 {
		t.Fatalf("PlaceOrder called %d times, want exactly 1 (the initial open)", got)
	}

	// 2. Follower excludes EURUSD AFTER the position was already copied.
	mustExec(t, ctx, pool, `UPDATE copy_relationships SET excluded_symbols = '{EURUSD}' WHERE id = $1`, relationshipID)

	// 3. The master closes that same position -- this must still reach ClosePosition despite the
	// now-added exclusion, or the follower's copy would be permanently stranded open.
	closeBody, err := json.Marshal(stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionClosed),
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339),
		VolumeLots:       0,
	})
	if err != nil {
		t.Fatalf("marshal close inject params: %v", err)
	}
	resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", bytes.NewReader(closeBody))
	if err != nil {
		t.Fatalf("POST inject close: %v", err)
	}
	resp.Body.Close()

	if got := len(fake.closePositionCallsFor(followerAccountID)); got != 1 {
		t.Fatalf("ClosePosition called %d times, want exactly 1 -- excluding a symbol after a position was already copied must not block closing it", got)
	}

	var status string
	if err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status); err != nil {
		t.Fatalf("query copied_trades status: %v", err)
	}
	if status != "CLOSED" {
		t.Fatalf("copied_trades.status = %q, want CLOSED", status)
	}
}
