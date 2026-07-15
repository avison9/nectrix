//go:build integration

// TICKET-108's own new tests, covering exactly the approved plan's
// testing-strategy table (pause tier, force-close tier, paused-excludes-
// new-signals regression). Reuses dispatch_integration_test.go's
// seedDispatchFixture/fakeBrokerService/buildDispatchRouter/
// buildDispatchTestServer wiring -- no parallel fixture, except this file's
// own small Pipeline builder (below), which additionally wires real
// risk/copy-relationships Kafka writers these tests read back from.
// buildDispatchTestServer's own signature stays untouched -- it's shared by
// 9 pre-existing call sites, none of which need drawdown assertions.
package main

import (
	"context"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// buildDrawdownTestPipeline mirrors buildDispatchTestServer's own Pipeline
// construction, but additionally wires real risk/copy-relationships
// writers -- this file's own tests need to read back what gets published to
// them, unlike every other test in this package.
func buildDrawdownTestPipeline(pool *pgxpool.Pool, deduper domain.Deduper, router *remoteadapter.Router, kafkaWriter, riskWriter, copyRelWriter *kafka.Writer) *pipeline.Pipeline {
	return pipeline.New(pool, deduper, router, moneymgmt.NewFrankfurterClient(nil, nil), kafkaWriter, riskWriter, copyRelWriter, nil)
}

// readRiskEvent skips any message that doesn't unmarshal into a RiskEvent
// with a real copy_relationship_id -- specifically the readiness-probe
// sentinel waitForProduceReady writes to the same topic before the test
// proper starts (see that function for why the probe exists), mirroring
// pipeline_integration_test.go's own readCopiedTradeEvent precedent exactly.
func readRiskEvent(t *testing.T, reader *kafka.Reader) *eventsv1.RiskEvent {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			t.Fatalf("read risk topic: %v", err)
		}
		var event eventsv1.RiskEvent
		if err := proto.Unmarshal(msg.Value, &event); err != nil || event.GetCopyRelationshipId() == "" {
			continue
		}
		return &event
	}
}

// readCopyRelationshipEvent mirrors readRiskEvent's own readiness-probe
// tolerance.
func readCopyRelationshipEvent(t *testing.T, reader *kafka.Reader) *eventsv1.CopyRelationshipEvent {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			t.Fatalf("read copy-relationships topic: %v", err)
		}
		var event eventsv1.CopyRelationshipEvent
		if err := proto.Unmarshal(msg.Value, &event); err != nil || event.GetCopyRelationshipId() == "" {
			continue
		}
		return &event
	}
}

func accountSnapshotWithEquity(brokerAccountID string, equity float64) domain.AccountSnapshot {
	return domain.AccountSnapshot{
		BrokerAccountID: brokerAccountID,
		Currency:        "USD",
		Balance:         equity,
		Equity:          equity,
		UsedMargin:      0,
		FreeMargin:      equity,
		AsOf:            time.Now().UTC().Format(time.RFC3339),
	}
}

// docs/09 §9.7 tier 1: a >10% drawdown pauses the relationship without
// touching any open position.
func TestDrawdown_PauseTier_PausesWithoutClosing(t *testing.T) {
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
	mustExec(t, ctx, pool, `UPDATE risk_profiles SET drawdown_pause_pct = 10 WHERE id = (SELECT risk_profile_id FROM copy_relationships WHERE id = $1)`, relationshipID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, accountSnapshotWithEquity(followerAccountID, 10000))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	// Establish the rolling high (10000) via a real OPEN dispatch, which
	// writes a real account_snapshots row for the follower as a byproduct.
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "drawdown-pause-tier",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	// Simulate an 11% equity drop from that high.
	fake.setSnapshot(followerAccountID, accountSnapshotWithEquity(followerAccountID, 8900))

	pl := buildDrawdownTestPipeline(pool, deduper, router, writer, riskWriter, copyRelWriter)
	if err := pl.CheckDrawdownOnce(ctx); err != nil {
		t.Fatalf("CheckDrawdownOnce: %v", err)
	}

	var relStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM copy_relationships WHERE id = $1`, relationshipID).Scan(&relStatus); err != nil {
		t.Fatalf("query copy_relationships: %v", err)
	}
	if relStatus != "PAUSED" {
		t.Fatalf("copy_relationships.status = %q, want PAUSED", relStatus)
	}

	var tradeStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&tradeStatus); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if tradeStatus != "FILLED" {
		t.Fatalf("copied_trades.status = %q, want FILLED (pause tier must never close positions)", tradeStatus)
	}
	if got := fake.closePositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ClosePosition called %d times, want 0 (pause tier only)", got)
	}

	riskReader := newReader(riskTopic, "test-group-drawdown-pause")
	defer riskReader.Close()
	riskEvent := readRiskEvent(t, riskReader)
	if riskEvent.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("RiskEvent copy_relationship_id = %q, want %q", riskEvent.GetCopyRelationshipId(), relationshipID)
	}
	if riskEvent.GetSeverity() != eventsv1.RiskEventSeverity_RISK_EVENT_SEVERITY_PAUSE {
		t.Fatalf("RiskEvent severity = %v, want PAUSE", riskEvent.GetSeverity())
	}
	if riskEvent.GetThresholdPct() != 10 {
		t.Fatalf("RiskEvent threshold_pct = %v, want 10", riskEvent.GetThresholdPct())
	}
	if !approxEqual(riskEvent.GetDrawdownPct(), 11.0) {
		t.Fatalf("RiskEvent drawdown_pct = %v, want ~11.0", riskEvent.GetDrawdownPct())
	}

	copyRelReader := newReader(copyRelTopic, "test-group-drawdown-pause")
	defer copyRelReader.Close()
	copyRelEvent := readCopyRelationshipEvent(t, copyRelReader)
	if copyRelEvent.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("CopyRelationshipEvent copy_relationship_id = %q, want %q", copyRelEvent.GetCopyRelationshipId(), relationshipID)
	}
	if copyRelEvent.GetEventType() != eventsv1.CopyRelationshipEventType_COPY_RELATIONSHIP_EVENT_TYPE_PAUSED {
		t.Fatalf("CopyRelationshipEvent event_type = %v, want PAUSED", copyRelEvent.GetEventType())
	}
}

// docs/09 §9.7 tier 2: a >=20% drawdown force-closes every open position,
// AND (since crossing the stricter threshold also crosses the looser one)
// publishes both a PAUSE and a FORCE_CLOSE RiskEvent, but only ONE
// CopyRelationshipEvent{PAUSED}.
func TestDrawdown_ForceCloseTier_ClosesAllOpenPositions(t *testing.T) {
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
	mustExec(t, ctx, pool, `UPDATE risk_profiles SET drawdown_pause_pct = 10, drawdown_close_all_pct = 20 WHERE id = (SELECT risk_profile_id FROM copy_relationships WHERE id = $1)`, relationshipID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, accountSnapshotWithEquity(followerAccountID, 10000))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "drawdown-force-close-tier",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	var copiedTradeID string
	if err := pool.QueryRow(ctx, `SELECT id FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&copiedTradeID); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}

	// Simulate a 25% equity drop -- crosses both thresholds.
	fake.setSnapshot(followerAccountID, accountSnapshotWithEquity(followerAccountID, 7500))

	pl := buildDrawdownTestPipeline(pool, deduper, router, writer, riskWriter, copyRelWriter)
	if err := pl.CheckDrawdownOnce(ctx); err != nil {
		t.Fatalf("CheckDrawdownOnce: %v", err)
	}

	calls := fake.closePositionCallsFor(followerAccountID)
	if len(calls) != 1 {
		t.Fatalf("ClosePosition called %d times, want 1", len(calls))
	}
	if calls[0].VolumeLots != nil {
		t.Fatalf("ClosePosition volume = %v, want nil (full close)", *calls[0].VolumeLots)
	}

	var tradeStatus string
	var currentOpenVolume float64
	if err := pool.QueryRow(ctx, `SELECT status, current_open_volume_lots FROM copied_trades WHERE id = $1`, copiedTradeID).Scan(&tradeStatus, &currentOpenVolume); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if tradeStatus != "CLOSED" {
		t.Fatalf("copied_trades.status = %q, want CLOSED", tradeStatus)
	}
	if currentOpenVolume != 0 {
		t.Fatalf("copied_trades.current_open_volume_lots = %v, want 0", currentOpenVolume)
	}

	var relStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM copy_relationships WHERE id = $1`, relationshipID).Scan(&relStatus); err != nil {
		t.Fatalf("query copy_relationships: %v", err)
	}
	if relStatus != "PAUSED" {
		t.Fatalf("copy_relationships.status = %q, want PAUSED", relStatus)
	}

	// Published in this exact order (see checkRelationshipDrawdown): PAUSE first, then FORCE_CLOSE.
	riskReader := newReader(riskTopic, "test-group-drawdown-force-close")
	defer riskReader.Close()
	pauseEvent := readRiskEvent(t, riskReader)
	if pauseEvent.GetSeverity() != eventsv1.RiskEventSeverity_RISK_EVENT_SEVERITY_PAUSE {
		t.Fatalf("first RiskEvent severity = %v, want PAUSE", pauseEvent.GetSeverity())
	}
	forceCloseEvent := readRiskEvent(t, riskReader)
	if forceCloseEvent.GetSeverity() != eventsv1.RiskEventSeverity_RISK_EVENT_SEVERITY_FORCE_CLOSE {
		t.Fatalf("second RiskEvent severity = %v, want FORCE_CLOSE", forceCloseEvent.GetSeverity())
	}
	if forceCloseEvent.GetThresholdPct() != 20 {
		t.Fatalf("FORCE_CLOSE RiskEvent threshold_pct = %v, want 20", forceCloseEvent.GetThresholdPct())
	}

	// Exactly ONE CopyRelationshipEvent{PAUSED} despite both tiers firing --
	// one state transition, not one per threshold.
	copyRelReader := newReader(copyRelTopic, "test-group-drawdown-force-close")
	defer copyRelReader.Close()
	copyRelEvent := readCopyRelationshipEvent(t, copyRelReader)
	if copyRelEvent.GetEventType() != eventsv1.CopyRelationshipEventType_COPY_RELATIONSHIP_EVENT_TYPE_PAUSED {
		t.Fatalf("CopyRelationshipEvent event_type = %v, want PAUSED", copyRelEvent.GetEventType())
	}
}

// AC3: a paused-by-drawdown relationship must not process any new copy
// signals until explicitly resumed -- a regression test against the
// pre-existing matchRelationships WHERE status='ACTIVE' filter, no new
// dispatch code involved.
func TestDrawdown_PausedRelationship_ProcessesNoNewSignals(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	// Paused by whatever means (a prior drawdown check, or directly here) --
	// AC3 only cares that a PAUSED relationship is excluded going forward.
	mustExec(t, ctx, pool, `UPDATE copy_relationships SET status = 'PAUSED' WHERE id = $1`, relationshipID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "drawdown-paused-no-new-signals",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	if got := fake.placeOrderCallCount(followerAccountID); got != 0 {
		t.Fatalf("PlaceOrder called %d times, want 0 (relationship is PAUSED)", got)
	}
	var copiedTradesCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&copiedTradesCount); err != nil {
		t.Fatalf("query copied_trades count: %v", err)
	}
	if copiedTradesCount != 0 {
		t.Fatalf("copied_trades row count = %d, want 0 (PAUSED relationship must be skipped by matchRelationships)", copiedTradesCount)
	}
}
