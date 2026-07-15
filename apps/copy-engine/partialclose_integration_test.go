//go:build integration

// TICKET-107's own new tests, covering exactly the approved plan's
// testing-strategy table (partial close proportionality, full close zero
// residual, SL/TP modify sync, follower-pinned SL/TP). Reuses
// dispatch_integration_test.go's seedDispatchFixture/fakeBrokerService/
// buildDispatchRouter/buildDispatchTestServer wiring -- no parallel fixture.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// postInjectEvent generalizes dispatch_integration_test.go's
// postInjectWithOpenPrice -- TICKET-107's tests need to inject
// PARTIALLY_CLOSED/CLOSED/MODIFIED events too, not just OPENED ones, so the
// full stubadapter.InjectEventParams is exposed to the caller here.
func postInjectEvent(t *testing.T, server *httptest.Server, params stubadapter.InjectEventParams) {
	t.Helper()
	body, err := json.Marshal(params)
	if err != nil {
		t.Fatalf("marshal inject params: %v", err)
	}
	resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("POST inject: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("POST inject: status %d body %s", resp.StatusCode, b)
	}
}

// queryCopiedTradeState reads back the one copied_trades row a
// relationship's fixture produces -- used throughout this file to assert
// state after each injected event.
func queryCopiedTradeState(t *testing.T, ctx context.Context, pool *pgxpool.Pool, relationshipID string) (followerBrokerPositionID string, currentOpenVolumeLots float64, status string) {
	t.Helper()
	err := pool.QueryRow(ctx, `SELECT follower_broker_position_id, current_open_volume_lots, status FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).
		Scan(&followerBrokerPositionID, &currentOpenVolumeLots, &status)
	if err != nil {
		t.Fatalf("query copied_trades state: %v", err)
	}
	return followerBrokerPositionID, currentOpenVolumeLots, status
}

// float64Ptr is a small literal-friendly helper for the *float64 fields
// stubadapter.InjectEventParams needs for close/modify events.
func float64Ptr(v float64) *float64 { return &v }

// docs/09 §9.5's partial-close ratio math, tested for 3 different original
// ratios, including one that requires real NEAREST-rounding at a lot-step
// boundary (1/3, not evenly divisible by lot_step=0.01).
func TestPartialClose_ProportionalVolume(t *testing.T) {
	cases := []struct {
		name             string
		masterVolume     float64 // == follower's initial computed_volume_lots (MULTIPLIER x1.0)
		closedVolumeLots float64
		remainingVolume  float64
		wantCloseVolume  float64
	}{
		{"Half", 10.0, 5.0, 5.0, 5.00},
		{"ThirtyPercent", 10.0, 3.0, 7.0, 3.00},
		{"RoundsAtLotStepBoundary", 1.0, 1.0, 2.0, 0.33}, // ratio=1/3, raw=0.3333.., NEAREST @ 0.01 -> 0.33
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ctx := context.Background()
			pool := newTestPool(t)
			deduper := newTestDeduper(t)
			topic := createDedicatedTopic(t)
			writer := newWriter(topic)
			t.Cleanup(func() { writer.Close() })

			masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
			// seedDispatchFixture's risk_profiles.max_lot_per_trade=5.0 would
			// otherwise cap the follower's initial computed_volume_lots below
			// this test's own master volumes (up to 10) -- raise it so the
			// hand-computed ratio expectations below hold against the
			// UNCAPPED volume, matching what handlePartialClose actually
			// bases its ratio math on (current_open_volume_lots).
			mustExec(t, ctx, pool, `UPDATE risk_profiles SET max_lot_per_trade = 20 WHERE id = (SELECT risk_profile_id FROM copy_relationships WHERE id = $1)`, relationshipID)
			fake := newFakeBrokerService()
			fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
			fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
			router := buildDispatchRouter(t, fake)
			server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

			brokerPositionID := "partial-" + c.name
			baseTime := time.Now().UTC()

			postInjectEvent(t, server, stubadapter.InjectEventParams{
				BrokerPositionID: brokerPositionID,
				ServerTimestamp:  baseTime.Format(time.RFC3339Nano),
				VolumeLots:       c.masterVolume,
				OpenPrice:        1.2000,
			})

			postInjectEvent(t, server, stubadapter.InjectEventParams{
				BrokerPositionID: brokerPositionID,
				EventType:        string(domain.TradeEventPositionPartiallyClosed),
				ServerTimestamp:  baseTime.Add(time.Second).Format(time.RFC3339Nano),
				VolumeLots:       c.remainingVolume,
				ClosedVolumeLots: float64Ptr(c.closedVolumeLots),
			})

			followerPositionID, currentOpenVolume, status := queryCopiedTradeState(t, ctx, pool, relationshipID)
			if status != "PARTIALLY_CLOSED" {
				t.Fatalf("status = %q, want PARTIALLY_CLOSED", status)
			}
			wantRemaining := c.masterVolume - c.wantCloseVolume
			if !approxEqual(currentOpenVolume, wantRemaining) {
				t.Fatalf("current_open_volume_lots = %v, want %v", currentOpenVolume, wantRemaining)
			}

			calls := fake.closePositionCallsFor(followerAccountID)
			if len(calls) != 1 {
				t.Fatalf("ClosePosition called %d times, want 1", len(calls))
			}
			if calls[0].PositionID != followerPositionID {
				t.Fatalf("ClosePosition positionID = %q, want %q", calls[0].PositionID, followerPositionID)
			}
			if calls[0].VolumeLots == nil || !approxEqual(*calls[0].VolumeLots, c.wantCloseVolume) {
				t.Fatalf("ClosePosition volume = %v, want %v", calls[0].VolumeLots, c.wantCloseVolume)
			}
		})
	}
}

// The correctness fix this ticket's plan is built around: TWO sequential
// partial closes must preserve proportionality against the follower's
// CURRENT open volume, not the immutable original computed_volume_lots.
// Master opens 10, closes 3 (ratio 0.3), then closes 3.5 of the remaining 7
// (ratio 0.5) -- master retains 3.5/10=35%. Using currentOpenVolumeLots as
// the basis, the follower also retains exactly 35% (1.75/5... here 1:1
// multiplier so 3.5/10=35%), matching the master exactly. Using the
// immutable original as the basis (the pseudocode's literal field name)
// would instead leave the follower at 20%, breaking proportionality -- see
// partialclose.go's handlePartialClose doc comment for the full derivation.
func TestPartialClose_SequentialCloses_PreserveProportionality(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	// See TestPartialClose_ProportionalVolume's identical comment: without
	// this, the fixture's default max_lot_per_trade=5.0 would cap the
	// follower's initial volume below this test's master volume of 10.
	mustExec(t, ctx, pool, `UPDATE risk_profiles SET max_lot_per_trade = 20 WHERE id = (SELECT risk_profile_id FROM copy_relationships WHERE id = $1)`, relationshipID)
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	const brokerPositionID = "sequential-partial-close"
	baseTime := time.Now().UTC()

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  baseTime.Format(time.RFC3339Nano),
		VolumeLots:       10.0,
		OpenPrice:        1.2000,
	})

	// Close #1: master closes 3 of 10 (ratio 0.3) -> follower closes 10*0.3=3.0, leaving 7.0.
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionPartiallyClosed),
		ServerTimestamp:  baseTime.Add(time.Second).Format(time.RFC3339Nano),
		VolumeLots:       7.0,
		ClosedVolumeLots: float64Ptr(3.0),
	})

	_, afterFirst, _ := queryCopiedTradeState(t, ctx, pool, relationshipID)
	if !approxEqual(afterFirst, 7.0) {
		t.Fatalf("after close #1, current_open_volume_lots = %v, want 7.0", afterFirst)
	}

	// Close #2: master closes 3.5 of the remaining 7 (ratio 0.5) -> follower
	// closes CURRENT open volume (7.0) * 0.5 = 3.5, leaving 3.5 (35% of the
	// original 10 -- matching the master's own 3.5/10=35% retained exactly).
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionPartiallyClosed),
		ServerTimestamp:  baseTime.Add(2 * time.Second).Format(time.RFC3339Nano),
		VolumeLots:       3.5,
		ClosedVolumeLots: float64Ptr(3.5),
	})

	_, afterSecond, _ := queryCopiedTradeState(t, ctx, pool, relationshipID)
	if !approxEqual(afterSecond, 3.5) {
		t.Fatalf("after close #2, current_open_volume_lots = %v, want 3.5 (35%% of original 10, matching master's retained fraction) -- got the broken value if this reads 1.0", afterSecond)
	}

	calls := fake.closePositionCallsFor(followerAccountID)
	if len(calls) != 2 {
		t.Fatalf("ClosePosition called %d times, want 2", len(calls))
	}
	if calls[0].VolumeLots == nil || !approxEqual(*calls[0].VolumeLots, 3.0) {
		t.Fatalf("close #1 volume = %v, want 3.0", calls[0].VolumeLots)
	}
	if calls[1].VolumeLots == nil || !approxEqual(*calls[1].VolumeLots, 3.5) {
		t.Fatalf("close #2 volume = %v, want 3.5", calls[1].VolumeLots)
	}
}

// docs/09 §9.5's full-close edge case: after prior partial closes, a full
// close must close the ENTIRE remaining volume, bypassing the ratio math
// entirely, leaving zero residual.
func TestFullClose_AfterPartialCloses_ZeroResidual(t *testing.T) {
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

	const brokerPositionID = "full-close-after-partial"
	baseTime := time.Now().UTC()

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  baseTime.Format(time.RFC3339Nano),
		VolumeLots:       10.0,
		OpenPrice:        1.2000,
	})
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionPartiallyClosed),
		ServerTimestamp:  baseTime.Add(time.Second).Format(time.RFC3339Nano),
		VolumeLots:       7.0,
		ClosedVolumeLots: float64Ptr(3.0),
	})
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionClosed),
		ServerTimestamp:  baseTime.Add(2 * time.Second).Format(time.RFC3339Nano),
		VolumeLots:       0,
		ClosedVolumeLots: float64Ptr(7.0),
	})

	_, currentOpenVolume, status := queryCopiedTradeState(t, ctx, pool, relationshipID)
	if status != "CLOSED" {
		t.Fatalf("status = %q, want CLOSED", status)
	}
	if currentOpenVolume != 0 {
		t.Fatalf("current_open_volume_lots = %v, want 0 (zero residual)", currentOpenVolume)
	}

	calls := fake.closePositionCallsFor(followerAccountID)
	if len(calls) != 2 {
		t.Fatalf("ClosePosition called %d times, want 2 (1 partial + 1 full)", len(calls))
	}
	if calls[1].VolumeLots != nil {
		t.Fatalf("full close's ClosePosition volume = %v, want nil (bypasses ratio math, closes entire remaining)", *calls[1].VolumeLots)
	}
}

// docs/09 §9.6's SL pip-distance translation, verified for both BUY and
// SELL, anchored on the follower's REAL fill price (deliberately different
// from the master's own open price, proving handleModify uses
// copied_trades.filled_price, not an open-path approximation).
func TestModifySL_BothDirections(t *testing.T) {
	cases := []struct {
		name              string
		direction         domain.TradeDirection
		masterOpenPrice   float64
		masterNewSL       float64
		followerFillPrice float64
		wantFollowerSL    float64
	}{
		// BUY: SL 50 pips below master's open. followerSL = followerFillPrice - 50 pips.
		{"Buy", domain.TradeDirectionBuy, 1.2000, 1.1950, 1.20050, 1.19550},
		// SELL: SL 50 pips above master's open. followerSL = followerFillPrice + 50 pips.
		{"Sell", domain.TradeDirectionSell, 1.2000, 1.2050, 1.19950, 1.20450},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
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
			fake.setFilledPrice(followerAccountID, c.followerFillPrice)
			router := buildDispatchRouter(t, fake)
			server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

			brokerPositionID := "modify-sl-" + c.name
			baseTime := time.Now().UTC()

			postInjectEvent(t, server, stubadapter.InjectEventParams{
				BrokerPositionID: brokerPositionID,
				Direction:        string(c.direction),
				ServerTimestamp:  baseTime.Format(time.RFC3339Nano),
				VolumeLots:       1.0,
				OpenPrice:        c.masterOpenPrice,
			})
			postInjectEvent(t, server, stubadapter.InjectEventParams{
				BrokerPositionID: brokerPositionID,
				EventType:        string(domain.TradeEventPositionModified),
				Direction:        string(c.direction),
				ServerTimestamp:  baseTime.Add(time.Second).Format(time.RFC3339Nano),
				VolumeLots:       1.0,
				OpenPrice:        c.masterOpenPrice,
				CurrentSLPrice:   float64Ptr(c.masterNewSL),
			})

			calls := fake.modifyPositionCallsFor(followerAccountID)
			if len(calls) != 1 {
				t.Fatalf("ModifyPosition called %d times, want 1", len(calls))
			}
			if calls[0].SLPrice == nil || !approxEqual(*calls[0].SLPrice, c.wantFollowerSL) {
				t.Fatalf("ModifyPosition SLPrice = %v, want %v", calls[0].SLPrice, c.wantFollowerSL)
			}
		})
	}
}

// docs/09 §9.6's digit-count normalization: master quotes at a 5-digit
// (0.0001 pip) precision, follower at a 3-digit-style (0.01 pip) precision
// -- the SAME 50-pip distance must translate through EACH side's own
// pip_size, not a raw price copy.
func TestModifySL_DifferingPipSizes_CrossBrokerDigitCounts(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, _ := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	// Widen the follower's EURUSD mapping to a JPY-style 3-digit/0.01-pip
	// quote, distinctly different from the master's 5-digit/0.0001 pip --
	// seedDispatchFixture gives both sides identical pip_size by default.
	mustExec(t, ctx, pool, `UPDATE symbol_mappings SET pip_size = 0.01, digits = 3 WHERE broker_account_id = $1 AND canonical_symbol = 'EURUSD'`, followerAccountID)

	const followerFillPrice = 130.500
	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	fake.setFilledPrice(followerAccountID, followerFillPrice)
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	const brokerPositionID = "modify-sl-cross-pip-size"
	const masterOpenPrice = 1.2000
	const masterNewSL = 1.1950 // 50 pips below master open at masterPipSize=0.0001
	baseTime := time.Now().UTC()

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  baseTime.Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        masterOpenPrice,
	})
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionModified),
		ServerTimestamp:  baseTime.Add(time.Second).Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        masterOpenPrice,
		CurrentSLPrice:   float64Ptr(masterNewSL),
	})

	calls := fake.modifyPositionCallsFor(followerAccountID)
	if len(calls) != 1 {
		t.Fatalf("ModifyPosition called %d times, want 1", len(calls))
	}
	// 50 pips at followerPipSize=0.01 = 0.50 price units below the follower's
	// own fill price (130.500), NOT 50*0.0001=0.005 (the master's pip size).
	wantFollowerSL := followerFillPrice - 0.50
	if calls[0].SLPrice == nil || !approxEqual(*calls[0].SLPrice, wantFollowerSL) {
		t.Fatalf("ModifyPosition SLPrice = %v, want %v", calls[0].SLPrice, wantFollowerSL)
	}
}

// FR-3.7 / docs/08 §8.7: a follower with pinned SL/TP never has ModifyPosition
// called against their real position, but the master's modification is still
// published (real, Kafka-visible transparency) with a reject_reason
// distinguishing "observed but not applied" from a normally-applied MODIFIED.
func TestModify_FollowerPinnedSlTp_NotAppliedButPublished(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")
	mustExec(t, ctx, pool, `UPDATE risk_profiles SET pin_follower_sl_tp = TRUE WHERE id = (SELECT risk_profile_id FROM copy_relationships WHERE id = $1)`, relationshipID)

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	const brokerPositionID = "modify-pinned-sltp"
	baseTime := time.Now().UTC()

	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  baseTime.Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})
	postInjectEvent(t, server, stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		EventType:        string(domain.TradeEventPositionModified),
		ServerTimestamp:  baseTime.Add(time.Second).Format(time.RFC3339Nano),
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
		CurrentSLPrice:   float64Ptr(1.1950),
	})

	if got := fake.modifyPositionCallCount(followerAccountID); got != 0 {
		t.Fatalf("ModifyPosition called %d times, want 0 (follower has pinned SL/TP)", got)
	}

	// The OPEN event above already published its own CopiedTradeEvent
	// (OPENED) to this same topic -- read until the MODIFIED one specifically,
	// rather than assuming it's the very first message.
	reader := newReader(topic, "test-group-"+brokerPositionID)
	defer reader.Close()
	event := readCopiedTradeEventOfType(t, reader, eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_MODIFIED)
	if event.GetCopyRelationshipId() != relationshipID {
		t.Fatalf("copy_relationship_id = %q, want %q", event.GetCopyRelationshipId(), relationshipID)
	}
	if event.GetRejectReason() != "PINNED_NOT_APPLIED" {
		t.Fatalf("reject_reason = %q, want PINNED_NOT_APPLIED", event.GetRejectReason())
	}
}

// readCopiedTradeEventOfType extends pipeline_integration_test.go's
// readCopiedTradeEvent -- reads until a CopiedTradeEvent of the specific
// wanted type is found, skipping any earlier ones a test's own OPEN
// injection (or the readiness-probe sentinel) already published to the same
// topic.
func readCopiedTradeEventOfType(t *testing.T, reader *kafka.Reader, wantType eventsv1.CopiedTradeEventType) *eventsv1.CopiedTradeEvent {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			t.Fatalf("read copied-trades topic: %v", err)
		}
		var event eventsv1.CopiedTradeEvent
		if err := proto.Unmarshal(msg.Value, &event); err != nil || event.GetCopyRelationshipId() == "" {
			continue
		}
		if event.GetEventType() != wantType {
			continue
		}
		return &event
	}
}
