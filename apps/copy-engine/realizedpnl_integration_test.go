//go:build integration

// Bugfix — copied_trades.realized_pnl used to be NULL forever: no close path (handleClose,
// forceCloseAllOpenPositions) ever computed or persisted it. These tests prove the fix for the
// primary handleClose path — a hand-computed BUY and SELL case, matching the exact
// priceDiff * contractSize * volumeLots formula computeRealizedPnL documents.
package main

import (
	"context"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

func TestHandleClose_RealizedPnl_Buy(t *testing.T) {
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
	const openPrice = 1.10000
	const closePrice = 1.10050 // +50 pips, a BUY profits when price rises
	fake.setFilledPrice(followerAccountID, openPrice)
	fake.setCloseFilledPrice(followerAccountID, closePrice)

	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "pnl-buy-" + uuid.NewString()
	postInjectWithOpenPrice(t, server, brokerPositionID, time.Now().UTC().Format(time.RFC3339), 1.0, openPrice)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionClosed),
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339),
		VolumeLots:       0,
	})

	var realizedPnl *float64
	if err := pool.QueryRow(ctx, `SELECT realized_pnl FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&realizedPnl); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if realizedPnl == nil {
		t.Fatalf("realized_pnl is NULL, want a real computed value")
	}
	// EURUSD per seedDispatchFixture's own symbol_mappings row: contract_size=100000, volume=1.0.
	want := (closePrice - openPrice) * 100000 * 1.0
	if !approxEqual(*realizedPnl, want) {
		t.Fatalf("realized_pnl = %v, want %v (hand-computed BUY case)", *realizedPnl, want)
	}
}

func TestHandleClose_RealizedPnl_Sell(t *testing.T) {
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
	const openPrice = 1.10000
	const closePrice = 1.09950 // -50 pips, a SELL profits when price falls
	fake.setFilledPrice(followerAccountID, openPrice)
	fake.setCloseFilledPrice(followerAccountID, closePrice)

	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "pnl-sell-" + uuid.NewString()
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		Direction:        "SELL",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339),
		VolumeLots:       1.0,
		OpenPrice:        openPrice,
	})
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionClosed),
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339),
		VolumeLots:       0,
	})

	var realizedPnl *float64
	if err := pool.QueryRow(ctx, `SELECT realized_pnl FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&realizedPnl); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if realizedPnl == nil {
		t.Fatalf("realized_pnl is NULL, want a real computed value")
	}
	want := (openPrice - closePrice) * 100000 * 1.0
	if !approxEqual(*realizedPnl, want) {
		t.Fatalf("realized_pnl = %v, want %v (hand-computed SELL case)", *realizedPnl, want)
	}
}
