//go:build integration

// TICKET-109's own new tests, covering exactly the approved plan's
// testing-strategy table (missed open/close/partial-close/modify, zero
// false positives, idempotent replay, follower-side ledger correction).
// Reuses dispatch_integration_test.go's/partialclose_integration_test.go's
// seedDispatchFixture/fakeBrokerService/buildDispatchRouter/
// buildDispatchTestServer/postInjectEvent wiring -- plus this file's own
// small Pipeline builder (mirrors drawdown_integration_test.go's
// buildDrawdownTestPipeline), wiring a real reconciliationEventWriter these
// tests read back from.
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
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

func buildReconcileTestPipeline(pool *pgxpool.Pool, deduper domain.Deduper, router *remoteadapter.Router, kafkaWriter, reconciliationWriter *kafka.Writer) *pipeline.Pipeline {
	return pipeline.New(pool, deduper, router, moneymgmt.NewFrankfurterClient(nil, nil), kafkaWriter, nil, nil, reconciliationWriter)
}

// readReconciliationDriftOfType mirrors readCopiedTradeEventOfType's own
// readiness-probe-tolerant pattern -- reads until a drift of the specific
// wanted type is found.
func readReconciliationDriftOfType(t *testing.T, reader *kafka.Reader, wantType eventsv1.ReconciliationDriftType) *eventsv1.ReconciliationDriftDetected {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			t.Fatalf("read reconciliation topic: %v", err)
		}
		var event eventsv1.ReconciliationDriftDetected
		if err := proto.Unmarshal(msg.Value, &event); err != nil || event.GetBrokerAccountId() == "" {
			continue
		}
		if event.GetDriftType() != wantType {
			continue
		}
		return &event
	}
}

// assertNoRealReconciliationDrift reads with a short deadline and expects
// it to expire -- proving no real drift message (beyond the readiness-probe
// sentinel) was published, the honest way to assert a negative against a
// Kafka topic.
func assertNoRealReconciliationDrift(t *testing.T, reader *kafka.Reader) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			return // deadline expired, as expected -- no real drift arrived
		}
		var event eventsv1.ReconciliationDriftDetected
		if err := proto.Unmarshal(msg.Value, &event); err != nil || event.GetBrokerAccountId() == "" {
			continue // the readiness-probe sentinel, skip it
		}
		t.Fatalf("unexpected reconciliation drift published: %+v", &event)
	}
}

func openPosition(brokerPositionID string, volumeLots, openPrice float64, sl, tp *float64) domain.NormalizedPosition {
	return domain.NormalizedPosition{
		BrokerPositionID: brokerPositionID,
		Symbol:           domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		Direction:        domain.TradeDirectionBuy,
		VolumeLots:       volumeLots,
		OpenPrice:        openPrice,
		CurrentSLPrice:   sl,
		CurrentTPPrice:   tp,
		OpenedAt:         time.Now().UTC().Format(time.RFC3339),
	}
}

// AC1's primary case: a dropped OPEN event is detected and correctly
// synthesized within one reconciliation cycle.
func TestReconciliation_MissedOpen_SynthesizesAndReplays(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	// The simulated drop: the broker reports this position as open, but it
	// was NEVER sent via postInjectEvent -- no real stream toggling needed.
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("missed-open-pos", 1.0, 1.2000, nil, nil)})
	router := buildDispatchRouter(t, fake)
	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)

	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, "missed-open-pos", 1)

	var status string
	if err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "FILLED" {
		t.Fatalf("copied_trades.status = %q, want FILLED (proves replay through the real pipeline)", status)
	}
	if got := fake.placeOrderCallCount(followerAccountID); got != 1 {
		t.Fatalf("PlaceOrder called %d times, want 1", got)
	}

	reader := newReader(reconTopic, "test-group-missed-open")
	defer reader.Close()
	drift := readReconciliationDriftOfType(t, reader, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_OPEN)
	if drift.GetBrokerAccountId() != masterAccountID {
		t.Fatalf("drift broker_account_id = %q, want %q", drift.GetBrokerAccountId(), masterAccountID)
	}
}

func TestReconciliation_MissedClose_SynthesizesAndReplays(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	// Open live, normally.
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "missed-close-pos",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	// Simulate the master closing it while the stream was down: the broker
	// now reports NOTHING open, but no CLOSED event was ever sent.
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{})

	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	var status string
	var currentOpenVolume float64
	if err := pool.QueryRow(ctx, `SELECT status, current_open_volume_lots FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status, &currentOpenVolume); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "CLOSED" {
		t.Fatalf("copied_trades.status = %q, want CLOSED", status)
	}
	if currentOpenVolume != 0 {
		t.Fatalf("current_open_volume_lots = %v, want 0", currentOpenVolume)
	}

	calls := fake.closePositionCallsFor(followerAccountID)
	if len(calls) != 1 {
		t.Fatalf("ClosePosition called %d times, want 1", len(calls))
	}
	if calls[0].VolumeLots != nil {
		t.Fatalf("ClosePosition volume = %v, want nil (full close, real handleClose path)", *calls[0].VolumeLots)
	}

	reader := newReader(reconTopic, "test-group-missed-close")
	defer reader.Close()
	readReconciliationDriftOfType(t, reader, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_CLOSE)
}

func TestReconciliation_MissedPartialClose_SynthesizesAndReplays(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "missed-partial-close-pos",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	// The master closed 60% while the stream was down -- broker now reports
	// 0.4 lots remaining, but no PARTIALLY_CLOSED event was ever sent.
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("missed-partial-close-pos", 0.4, 1.2000, nil, nil)})

	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	var status string
	var currentOpenVolume float64
	if err := pool.QueryRow(ctx, `SELECT status, current_open_volume_lots FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status, &currentOpenVolume); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "PARTIALLY_CLOSED" {
		t.Fatalf("copied_trades.status = %q, want PARTIALLY_CLOSED", status)
	}
	if !approxEqual(currentOpenVolume, 0.4) {
		t.Fatalf("current_open_volume_lots = %v, want 0.4", currentOpenVolume)
	}

	calls := fake.closePositionCallsFor(followerAccountID)
	if len(calls) != 1 || calls[0].VolumeLots == nil || !approxEqual(*calls[0].VolumeLots, 0.6) {
		t.Fatalf("ClosePosition calls = %+v, want one call closing 0.6 lots", calls)
	}

	reader := newReader(reconTopic, "test-group-missed-partial-close")
	defer reader.Close()
	readReconciliationDriftOfType(t, reader, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_PARTIAL_CLOSE)
}

func TestReconciliation_MissedModify_SynthesizesAndReplays(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "missed-modify-pos",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	// The master set a new SL while the stream was down -- 50 pips below
	// open, correct for BUY -- but no MODIFIED event was ever sent.
	newSL := 1.1950
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("missed-modify-pos", 1.0, 1.2000, &newSL, nil)})

	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	calls := fake.modifyPositionCallsFor(followerAccountID)
	if len(calls) != 1 {
		t.Fatalf("ModifyPosition called %d times, want 1", len(calls))
	}
	// followerFillPrice defaults to fakeDefaultFilledPrice (1.10000) -- 50
	// pips below that, correct for BUY.
	wantSL := fakeDefaultFilledPrice - 0.0050
	if calls[0].SLPrice == nil || !approxEqual(*calls[0].SLPrice, wantSL) {
		t.Fatalf("ModifyPosition SLPrice = %v, want %v", calls[0].SLPrice, wantSL)
	}

	reader := newReader(reconTopic, "test-group-missed-modify")
	defer reader.Close()
	readReconciliationDriftOfType(t, reader, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_MODIFY)
}

// Partial-close and modify are the only two diff types that can co-occur on
// the same position -- both must be synthesized and replayed in one tick.
func TestReconciliation_PartialCloseAndModify_BothSynthesizedInOneTick(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "co-occurring-pos",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})

	newSL := 1.1950
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("co-occurring-pos", 0.5, 1.2000, &newSL, nil)})

	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	closeCalls := fake.closePositionCallsFor(followerAccountID)
	if len(closeCalls) != 1 {
		t.Fatalf("ClosePosition called %d times, want 1", len(closeCalls))
	}
	modifyCalls := fake.modifyPositionCallsFor(followerAccountID)
	if len(modifyCalls) != 1 {
		t.Fatalf("ModifyPosition called %d times, want 1", len(modifyCalls))
	}

	var status string
	if err := pool.QueryRow(ctx, `SELECT status FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "PARTIALLY_CLOSED" {
		t.Fatalf("copied_trades.status = %q, want PARTIALLY_CLOSED", status)
	}
}

// AC2: a reconciliation pass against an already-fully-synced account
// produces zero synthetic events -- no false positives.
func TestReconciliation_FullySynced_NoSyntheticEvents(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "fully-synced-pos",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})
	// Broker state matches exactly what was just injected -- no drift.
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("fully-synced-pos", 1.0, 1.2000, nil, nil)})

	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce (1st): %v", err)
	}
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce (2nd): %v", err)
	}

	if got := fake.placeOrderCallCount(followerAccountID); got != 1 {
		t.Fatalf("PlaceOrder called %d times, want 1 (only the original live inject, reconciliation added none)", got)
	}
	if got := fake.closePositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ClosePosition called %d times, want 0", got)
	}
	if got := fake.modifyPositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ModifyPosition called %d times, want 0", got)
	}

	reader := newReader(reconTopic, "test-group-fully-synced")
	defer reader.Close()
	assertNoRealReconciliationDrift(t, reader)
}

// AC4: reconciliation-synthesized events go through the EXACT SAME
// idempotent pipeline as live events -- proven by running the check twice
// and confirming the second run is a true no-op (no duplicate row, no
// duplicate broker call), exercising the same claim-INSERT idempotency
// guard TICKET-106 already relies on.
func TestReconciliation_RepeatedRun_NoDuplicateDispatch(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("repeated-run-pos", 1.0, 1.2000, nil, nil)})
	router := buildDispatchRouter(t, fake)
	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)

	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce (1st): %v", err)
	}
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce (2nd): %v", err)
	}

	if got := fake.placeOrderCallCount(followerAccountID); got != 1 {
		t.Fatalf("PlaceOrder called %d times, want 1 (2nd run must be a no-op)", got)
	}
	var copiedTradesCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&copiedTradesCount); err != nil {
		t.Fatalf("query copied_trades count: %v", err)
	}
	if copiedTradesCount != 1 {
		t.Fatalf("copied_trades row count = %d, want 1", copiedTradesCount)
	}
}

// Follower-side: the broker already closed the position (e.g. a prior
// ClosePosition succeeded but its finalize UPDATE was never applied) --
// corrected directly, no ClosePosition call (it's already closed
// broker-side, calling again would be wrong).
func TestReconciliation_FollowerLedgerCorrected_ClosedOnBroker(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)

	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: "follower-closed-pos",
		ServerTimestamp:  time.Now().UTC().Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})
	// Keep the master in sync so only follower-side reconciliation has
	// anything to do in this test.
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("follower-closed-pos", 1.0, 1.2000, nil, nil)})
	// The follower's broker reports nothing open at all.
	fake.setOpenPositions(followerAccountID, []domain.NormalizedPosition{})

	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	var status string
	var currentOpenVolume float64
	if err := pool.QueryRow(ctx, `SELECT status, current_open_volume_lots FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status, &currentOpenVolume); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "CLOSED" {
		t.Fatalf("copied_trades.status = %q, want CLOSED (direct ledger correction)", status)
	}
	if currentOpenVolume != 0 {
		t.Fatalf("current_open_volume_lots = %v, want 0", currentOpenVolume)
	}
	if got := fake.closePositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ClosePosition called %d times, want 0 (already closed broker-side, must not call again)", got)
	}

	reader := newReader(reconTopic, "test-group-follower-closed")
	defer reader.Close()
	readReconciliationDriftOfType(t, reader, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_FOLLOWER_LEDGER_CORRECTED)
}

// Follower-side: TICKET-106's own explicitly-flagged crash-between-claim-
// and-finalize gap, closed for real. A stale PENDING row (simulating a
// crash after PlaceOrder succeeded broker-side but before the finalize
// UPDATE landed) is uniquely matched against an actual, otherwise-unmatched
// follower position and finalized to FILLED.
func TestReconciliation_FollowerStuckPending_FinalizedOnUniqueMatch(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })
	reconTopic := createDedicatedTopic(t)
	reconWriter := newWriter(reconTopic)
	t.Cleanup(func() { reconWriter.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	// Manufacture the stuck-PENDING scenario directly -- the normal
	// dispatch path always finalizes, so a real crash can't be triggered
	// from the outside; this reproduces its end state.
	signalID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO trade_signals (id, master_broker_account_id, broker_position_id, event_type, canonical_symbol, direction, volume_lots, server_timestamp, raw_payload)
		VALUES ($1,$2,$3,'POSITION_OPENED','EURUSD','BUY',1.0,now(),'{}')`,
		signalID, masterAccountID, "stuck-master-pos")
	pendingID := uuid.NewString()
	sizingSnapshot := `{"masterPosition":{"symbol":{"canonicalCode":"EURUSD"}},"appliedDirection":"BUY"}`
	mustExec(t, ctx, pool, `
		INSERT INTO copied_trades (id, copy_relationship_id, trade_signal_id, idempotency_key, status, computed_volume_lots, sizing_method_snapshot, created_at)
		VALUES ($1,$2,$3,'idem-stuck','PENDING',1.0,$4, now() - interval '5 minutes')`,
		pendingID, relationshipID, signalID, sizingSnapshot)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	// Keep the master in sync (matches the trade_signals row inserted
	// above) so only follower-side reconciliation has anything to do.
	fake.setOpenPositions(masterAccountID, []domain.NormalizedPosition{openPosition("stuck-master-pos", 1.0, 1.2000, nil, nil)})
	// The follower's broker reports a real position with no believed row
	// (the PENDING row above is neither FILLED nor PARTIALLY_CLOSED).
	fake.setOpenPositions(followerAccountID, []domain.NormalizedPosition{openPosition("actual-follower-pos", 1.0, 1.2050, nil, nil)})

	router := buildDispatchRouter(t, fake)
	pl := buildReconcileTestPipeline(pool, deduper, router, writer, reconWriter)
	if err := pl.CheckReconciliationOnce(ctx); err != nil {
		t.Fatalf("CheckReconciliationOnce: %v", err)
	}

	var status, followerPositionID string
	var filledPrice, currentOpenVolume float64
	if err := pool.QueryRow(ctx, `SELECT status, follower_broker_position_id, filled_price, current_open_volume_lots FROM copied_trades WHERE id = $1`, pendingID).
		Scan(&status, &followerPositionID, &filledPrice, &currentOpenVolume); err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "FILLED" {
		t.Fatalf("copied_trades.status = %q, want FILLED", status)
	}
	if followerPositionID != "actual-follower-pos" {
		t.Fatalf("follower_broker_position_id = %q, want actual-follower-pos", followerPositionID)
	}
	if !approxEqual(filledPrice, 1.2050) {
		t.Fatalf("filled_price = %v, want 1.2050", filledPrice)
	}
	if !approxEqual(currentOpenVolume, 1.0) {
		t.Fatalf("current_open_volume_lots = %v, want 1.0", currentOpenVolume)
	}

	reader := newReader(reconTopic, "test-group-stuck-pending")
	defer reader.Close()
	readReconciliationDriftOfType(t, reader, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_FOLLOWER_LEDGER_CORRECTED)
}
