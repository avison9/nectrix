//go:build integration

// TICKET-111's own new test, covering the stop-closure poller: a STOPPED
// relationship with an open position gets force-closed and publishes a
// CopyRelationshipEvent{STOPPED}, while an ACTIVE/PAUSED relationship with an
// open position is left untouched. Reuses dispatch_integration_test.go's
// seedDispatchFixture/fakeBrokerService/buildDispatchRouter/
// buildDispatchTestServer wiring and drawdown_integration_test.go's
// buildDrawdownTestPipeline/readCopyRelationshipEvent helpers -- no parallel
// fixture.
package main

import (
	"context"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
)

func TestStopClosure_StoppedRelationshipWithOpenPosition_ForceClosesAndPublishesEvent(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	riskTopic := createDedicatedTopic(t)
	riskWriter := newWriter(riskTopic)
	t.Cleanup(func() { riskWriter.Close() })
	copyRelTopic := createDedicatedTopic(t)
	copyRelWriter := newWriter(copyRelTopic)
	t.Cleanup(func() { copyRelWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "stop-closure-open-position",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	var copiedTradeID string
	if err := pool.QueryRow(ctx, `SELECT id FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&copiedTradeID); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}

	// The Java side (core-app's CopyRelationshipService.stop) has already
	// flipped the status by the time this poller ever sees the row -- this
	// poller only reacts to STOPPED, it never sets it.
	mustExec(t, ctx, pool, `UPDATE copy_relationships SET status = 'STOPPED', stopped_at = now() WHERE id = $1`, relationshipID)

	pl := buildDrawdownTestPipeline(pool, deduper, router, writer, riskWriter, copyRelWriter)
	if err := pl.CheckStopClosureOnce(ctx); err != nil {
		t.Fatalf("CheckStopClosureOnce: %v", err)
	}

	calls := fake.closePositionCallsFor(followerAccountID)
	if len(calls) != 1 {
		t.Fatalf("ClosePosition called %d times, want 1", len(calls))
	}
	if calls[0].VolumeLots != nil {
		t.Fatalf("ClosePosition volume = %v, want nil (full close)", *calls[0].VolumeLots)
	}

	var tradeStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE id = $1`, copiedTradeID).Scan(&tradeStatus); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if tradeStatus != "CLOSED" {
		t.Fatalf("copied_trades.status = %q, want CLOSED", tradeStatus)
	}

	copyRelReader := newReader(copyRelTopic, "test-group-stop-closure")
	defer copyRelReader.Close()
	copyRelEvent := readCopyRelationshipEvent(t, copyRelReader)
	if copyRelEvent.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("CopyRelationshipEvent copy_relationship_id = %q, want %q", copyRelEvent.GetCopyRelationshipId(), relationshipID)
	}
	if copyRelEvent.GetEventType() != eventsv1.CopyRelationshipEventType_COPY_RELATIONSHIP_EVENT_TYPE_STOPPED {
		t.Fatalf("CopyRelationshipEvent event_type = %v, want STOPPED", copyRelEvent.GetEventType())
	}
}

// A second sweep after the position is already closed must not call
// ClosePosition again -- loadStoppedRelationshipsWithOpenPositions' own join
// simply stops returning a relationship once it has no more qualifying rows.
func TestStopClosure_AlreadyClosedRelationship_IsNotProcessedAgain(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	riskTopic := createDedicatedTopic(t)
	riskWriter := newWriter(riskTopic)
	t.Cleanup(func() { riskWriter.Close() })
	copyRelTopic := createDedicatedTopic(t)
	copyRelWriter := newWriter(copyRelTopic)
	t.Cleanup(func() { copyRelWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	mustExec(t, ctx, pool, `UPDATE copy_relationships SET status = 'STOPPED', stopped_at = now() WHERE id = $1`, relationshipID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	pl := buildDrawdownTestPipeline(pool, deduper, router, writer, riskWriter, copyRelWriter)
	if err := pl.CheckStopClosureOnce(ctx); err != nil {
		t.Fatalf("CheckStopClosureOnce: %v", err)
	}

	if got := fake.closePositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ClosePosition called %d times, want 0 (relationship has no open copied_trades rows)", got)
	}
}

// AC4 shouldn't touch anything still ACTIVE -- only a real status='STOPPED'
// row is ever selected by loadStoppedRelationshipsWithOpenPositions.
func TestStopClosure_ActiveRelationshipWithOpenPosition_IsNotTouched(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	riskTopic := createDedicatedTopic(t)
	riskWriter := newWriter(riskTopic)
	t.Cleanup(func() { riskWriter.Close() })
	copyRelTopic := createDedicatedTopic(t)
	copyRelWriter := newWriter(copyRelTopic)
	t.Cleanup(func() { copyRelWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "stop-closure-active-untouched",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	pl := buildDrawdownTestPipeline(pool, deduper, router, writer, riskWriter, copyRelWriter)
	if err := pl.CheckStopClosureOnce(ctx); err != nil {
		t.Fatalf("CheckStopClosureOnce: %v", err)
	}

	if got := fake.closePositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ClosePosition called %d times, want 0 (relationship is still ACTIVE, not STOPPED)", got)
	}
	var tradeStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&tradeStatus); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if tradeStatus != "FILLED" {
		t.Fatalf("copied_trades.status = %q, want FILLED (untouched)", tradeStatus)
	}
}
