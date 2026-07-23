package pipeline

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/observability"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// openCopiedTrade is findOpenCopiedTrade's result shape -- just what
// handlePartialClose/handleClose/handleModify each need.
type openCopiedTrade struct {
	id                       uuid.UUID
	followerBrokerPositionID string
	currentOpenVolumeLots    float64
	filledPrice              *float64
	// direction (bugfix, realized-P&L) -- the ORIGINAL open trade_signal's own direction, not
	// whatever a later PARTIALLY_CLOSED/CLOSED/MODIFIED event's own Position.Direction happens to
	// echo. A position's direction never changes after it opens, so the durably-stored value is
	// the trustworthy one for P&L sign -- computeRealizedPnL's own caller must never rely on the
	// close event's own direction field for this reason.
	direction string
}

// findOpenCopiedTrade locates the still-open copied_trades row for a given
// master position, across all three of this ticket's handlers.
// copied_trades.trade_signal_id FKs to the SPECIFIC trade_signals row that
// triggered the OPEN -- a later PARTIALLY_CLOSED/CLOSED/MODIFIED event is a
// DIFFERENT trade_signals row (trade_signals has
// UNIQUE(master_broker_account_id, broker_position_id, event_type,
// server_timestamp), every event type/timestamp is its own row), so this
// must JOIN back through trade_signals rather than look up trade_signal_id
// directly.
//
// Returns found=false (not an error) when no open row exists --
// appendix-a-copy-engine-pseudocode.md §A.5/§A.6 both say the same thing:
// "reconciliation job will catch this on next pass if it's a genuine miss"
// (TICKET-109, not yet built) -- this is a logged no-op here, not a failure.
func (p *Pipeline) findOpenCopiedTrade(ctx context.Context, relationshipID, masterBrokerAccountID uuid.UUID, brokerPositionID string) (openCopiedTrade, bool, error) {
	var t openCopiedTrade
	var followerPositionID *string
	err := p.pool.QueryRow(ctx, `
		SELECT ct.id, ct.follower_broker_position_id, ct.current_open_volume_lots, ct.filled_price, ts.direction
		FROM copied_trades ct
		JOIN trade_signals ts ON ts.id = ct.trade_signal_id
		WHERE ct.copy_relationship_id = $1 AND ts.master_broker_account_id = $2 AND ts.broker_position_id = $3
		  AND ct.status IN ('FILLED','PARTIALLY_CLOSED')
		ORDER BY ct.created_at DESC LIMIT 1`,
		relationshipID, masterBrokerAccountID, brokerPositionID,
	).Scan(&t.id, &followerPositionID, &t.currentOpenVolumeLots, &t.filledPrice, &t.direction)
	if errors.Is(err, pgx.ErrNoRows) {
		return openCopiedTrade{}, false, nil
	}
	if err != nil {
		return openCopiedTrade{}, false, fmt.Errorf("query copied_trades for open trade: %w", err)
	}
	if followerPositionID != nil {
		t.followerBrokerPositionID = *followerPositionID
	}
	return t, true, nil
}

// handlePartialClose is appendix-a-copy-engine-pseudocode.md §A.5 /
// docs/09-money-management-risk-formulas.md §9.5, with one real correctness
// fix beyond the pseudocode's literal field name: rawCloseVolume is computed
// against the follower's CURRENT open volume (copiedTrade.currentOpenVolumeLots),
// not the immutable computed_volume_lots the pseudocode's own
// "copiedTrade.computedVolumeLots" line literally names.
//
// Worked example proving why: master opens 10 lots, follower opens 5
// (computed_volume_lots=5). Two sequential partial closes -- (1) master
// closes 3 of 10 (ratio 0.3), (2) master then closes 3.5 of the remaining 7
// (ratio 0.5). Using the IMMUTABLE computed_volume_lots=5 as the basis both
// times: close#1=1.5 (follower has 3.5 left), close#2=2.5 (follower ends at
// 1.0) -- master retains 35% of original, follower retains only 20%:
// proportionality breaks. Using the follower's CURRENT open volume as the
// basis instead: close#1=1.5 (follower has 3.5), close#2=3.5*0.5=1.75
// (follower ends at 1.75) -- follower retains 35%, exactly matching the
// master. This also matches docs/08 §8.6's own prose ("the follower always
// closes the *same fraction* of THEIR OWN POSITION that the master closed of
// theirs" -- "their own position" at event time is the current remaining
// volume, not the frozen original).
func (p *Pipeline) handlePartialClose(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	if event.ClosedVolumeLots == nil {
		return fmt.Errorf("handlePartialClose: event missing closedVolumeLots")
	}

	copiedTrade, found, err := p.findOpenCopiedTrade(ctx, rel.id, rel.masterBrokerAccountID, event.Position.BrokerPositionID)
	if err != nil {
		return fmt.Errorf("find open copied trade: %w", err)
	}
	if !found {
		slog.Default().Warn("pipeline: partial close for an unknown/already-closed position, skipping",
			"copyRelationshipId", rel.id, "brokerPositionId", event.Position.BrokerPositionID)
		return nil
	}

	followerSpec, followerMapped, err := p.loadConfirmedSymbolSpec(ctx, rel.followerBrokerAccountID, event.Position.Symbol)
	if err != nil {
		return fmt.Errorf("load follower symbol spec: %w", err)
	}
	if !followerMapped {
		slog.Default().Warn("pipeline: partial close skipped, follower symbol mapping is no longer confirmed",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id)
		return nil
	}

	// docs/09 §9.5: event.Position.VolumeLots is the master's remaining
	// volume AFTER this close (per adapter contract), so
	// (closedVolumeLots + remaining) is what existed immediately before it.
	closedRatio := *event.ClosedVolumeLots / (*event.ClosedVolumeLots + event.Position.VolumeLots)
	rawCloseVolume := copiedTrade.currentOpenVolumeLots * closedRatio

	normalizedClose, rejected, rejectReason := moneymgmt.NormalizeLot(rawCloseVolume, followerSpec, moneymgmt.RoundingNearest)
	if rejected {
		// §9.5's own edge case: "if follower_close_volume normalizes to 0 ...
		// treat the master's partial close as fully proportionally
		// represented by no action ... log it, do not error."
		// moneymgmt.NormalizeLot's existing below-min-lot behavior IS this
		// edge case -- nothing further to compute.
		slog.Default().Info("pipeline: partial close rounds below min lot, no action needed",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id, "rawCloseVolume", rawCloseVolume, "rejectReason", rejectReason)
		return nil
	}
	// Never over-close beyond what's actually open -- a safety net against
	// rounding/redelivery races, independent of NormalizeLot's own
	// broker-wide max_lot clamp.
	closeVolume := math.Min(normalizedClose, copiedTrade.currentOpenVolumeLots)
	if closeVolume <= 0 {
		return nil
	}

	followerBrokerType, err := p.loadBrokerType(ctx, rel.followerBrokerAccountID)
	if err != nil {
		return fmt.Errorf("load follower broker type: %w", err)
	}
	followerRemote, err := p.router.For(followerBrokerType)
	if err != nil {
		return fmt.Errorf("resolve follower remote adapter: %w", err)
	}

	result, err := followerRemote.ClosePosition(ctx, rel.followerBrokerAccountID.String(), copiedTrade.followerBrokerPositionID, &closeVolume)
	if err != nil {
		return fmt.Errorf("close position (partial): %w", err)
	}
	if !result.Success {
		slog.Default().Warn("pipeline: partial ClosePosition rejected by broker",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id, "rejectReason", result.RejectReason)
		return nil
	}

	newOpenVolume := copiedTrade.currentOpenVolumeLots - closeVolume
	if _, err := p.pool.Exec(ctx, `UPDATE copied_trades SET status='PARTIALLY_CLOSED', current_open_volume_lots=$1 WHERE id=$2`,
		newOpenVolume, copiedTrade.id); err != nil {
		return fmt.Errorf("update copied_trades (partial close): %w", err)
	}

	return p.publishCopiedTradePartiallyClosed(ctx, rel.id, event, closeVolume)
}

// handleClose is appendix-a-copy-engine-pseudocode.md §A.6: always closes
// the follower's ENTIRE remaining volume, bypassing handlePartialClose's
// ratio math entirely -- §9.5's full-close edge case, guaranteeing no
// residual dust position regardless of any prior partial closes/rounding
// drift.
func (p *Pipeline) handleClose(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	copiedTrade, found, err := p.findOpenCopiedTrade(ctx, rel.id, rel.masterBrokerAccountID, event.Position.BrokerPositionID)
	if err != nil {
		return fmt.Errorf("find open copied trade: %w", err)
	}
	if !found {
		slog.Default().Warn("pipeline: full close for an unknown/already-closed position, skipping",
			"copyRelationshipId", rel.id, "brokerPositionId", event.Position.BrokerPositionID)
		return nil
	}

	followerBrokerType, err := p.loadBrokerType(ctx, rel.followerBrokerAccountID)
	if err != nil {
		return fmt.Errorf("load follower broker type: %w", err)
	}
	followerRemote, err := p.router.For(followerBrokerType)
	if err != nil {
		return fmt.Errorf("resolve follower remote adapter: %w", err)
	}

	// nil volume = close the entire remaining position.
	result, err := followerRemote.ClosePosition(ctx, rel.followerBrokerAccountID.String(), copiedTrade.followerBrokerPositionID, nil)
	if err != nil {
		return fmt.Errorf("close position (full): %w", err)
	}
	if !result.Success {
		slog.Default().Warn("pipeline: full ClosePosition rejected by broker",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id, "rejectReason", result.RejectReason)
		return nil
	}

	var realizedPnl *float64
	if copiedTrade.filledPrice != nil && result.FilledPrice != nil {
		realizedPnl = p.computeRealizedPnL(ctx, rel.followerBrokerAccountID, event.Position.Symbol,
			domain.TradeDirection(copiedTrade.direction), copiedTrade.currentOpenVolumeLots, *copiedTrade.filledPrice, *result.FilledPrice)
	}
	if _, err := p.pool.Exec(ctx, `UPDATE copied_trades SET status='CLOSED', current_open_volume_lots=0, closed_at=now(), realized_pnl=$1 WHERE id=$2`,
		realizedPnl, copiedTrade.id); err != nil {
		return fmt.Errorf("update copied_trades (close): %w", err)
	}

	return p.publishCopiedTradeClosed(ctx, rel.id, event)
}

// handleModify is docs/08-copy-trading-engine.md §8.7 / §9.6's SL/TP sync,
// respecting FR-3.7's follower-pinned-SL/TP override (risk_profiles.pin_follower_sl_tp).
func (p *Pipeline) handleModify(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	copiedTrade, found, err := p.findOpenCopiedTrade(ctx, rel.id, rel.masterBrokerAccountID, event.Position.BrokerPositionID)
	if err != nil {
		return fmt.Errorf("find open copied trade: %w", err)
	}
	if !found {
		slog.Default().Warn("pipeline: modify for an unknown/already-closed position, skipping",
			"copyRelationshipId", rel.id, "brokerPositionId", event.Position.BrokerPositionID)
		return nil
	}

	riskProfile, err := p.loadRiskProfile(ctx, rel.riskProfileID)
	if err != nil {
		return fmt.Errorf("load risk profile: %w", err)
	}

	if riskProfile.PinFollowerSLTP {
		// FR-3.7: the follower has pinned their own SL/TP -- the master's
		// modification is observed and published (real, Kafka-visible
		// transparency, not just a server log line) but never applied to the
		// follower's actual position.
		slog.Default().Info("pipeline: master SL/TP modification observed but not applied (follower has pinned SL/TP)",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id)
		return p.publishCopiedTradeModified(ctx, rel.id, event, "PINNED_NOT_APPLIED")
	}

	if copiedTrade.filledPrice == nil {
		// Shouldn't happen for a FILLED/PARTIALLY_CLOSED row (doDispatchOrder's
		// finalize step always sets it) -- fail loud rather than silently
		// mistranslating SL/TP against a missing anchor price.
		return fmt.Errorf("handleModify: copied_trades %s has no filled_price", copiedTrade.id)
	}

	masterSpec, masterMapped, err := p.loadConfirmedSymbolSpec(ctx, rel.masterBrokerAccountID, event.Position.Symbol)
	if err != nil {
		return fmt.Errorf("load master symbol spec: %w", err)
	}
	followerSpec, followerMapped, err := p.loadConfirmedSymbolSpec(ctx, rel.followerBrokerAccountID, event.Position.Symbol)
	if err != nil {
		return fmt.Errorf("load follower symbol spec: %w", err)
	}
	if !masterMapped || !followerMapped {
		slog.Default().Warn("pipeline: modify skipped, symbol mapping is no longer confirmed",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id)
		return nil
	}

	followerDirection := applyCopyDirection(event.Position.Direction, rel.copyDirection)
	// filledPrice is the follower's REAL fill price (known since this
	// position is already open) -- unlike the OPEN-path call site in
	// dispatch.go, no masterOpenPrice approximation is needed here.
	slPrice := translateSlTp(event.Position.CurrentSLPrice, event.Position.OpenPrice, *copiedTrade.filledPrice, event.Position.Direction, followerDirection, masterSpec.PipSize, followerSpec.PipSize, "SL")
	tpPrice := translateSlTp(event.Position.CurrentTPPrice, event.Position.OpenPrice, *copiedTrade.filledPrice, event.Position.Direction, followerDirection, masterSpec.PipSize, followerSpec.PipSize, "TP")

	followerBrokerType, err := p.loadBrokerType(ctx, rel.followerBrokerAccountID)
	if err != nil {
		return fmt.Errorf("load follower broker type: %w", err)
	}
	followerRemote, err := p.router.For(followerBrokerType)
	if err != nil {
		return fmt.Errorf("resolve follower remote adapter: %w", err)
	}

	result, err := followerRemote.ModifyPosition(ctx, rel.followerBrokerAccountID.String(), copiedTrade.followerBrokerPositionID, domain.SLTPChange{SLPrice: slPrice, TPPrice: tpPrice})
	if err != nil {
		return fmt.Errorf("modify position: %w", err)
	}
	if !result.Success {
		slog.Default().Warn("pipeline: ModifyPosition rejected by broker",
			"copyRelationshipId", rel.id, "copiedTradeId", copiedTrade.id, "rejectReason", result.RejectReason)
		return nil
	}

	return p.publishCopiedTradeModified(ctx, rel.id, event, "")
}

func (p *Pipeline) publishCopiedTradePartiallyClosed(ctx context.Context, relationshipID uuid.UUID, event domain.NormalizedTradeEvent, closeVolume float64) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopiedTradePartiallyClosed(ctx, relationshipID, event, closeVolume)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopiedTradePartiallyClosed(ctx context.Context, relationshipID uuid.UUID, event domain.NormalizedTradeEvent, closeVolume float64) error {
	msg := &eventsv1.CopiedTradeEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId: relationshipID.String(),
		EventType:          eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_PARTIALLY_CLOSED,
		BrokerPositionId:   event.Position.BrokerPositionID,
		Symbol: &eventsv1.NormalizedSymbol{
			CanonicalCode: event.Position.Symbol.CanonicalCode,
			AssetClass:    toProtoAssetClass(event.Position.Symbol.AssetClass),
		},
		VolumeLots: &closeVolume,
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopiedTradeEvent: %w", err)
	}
	return p.kafkaWriter.WriteMessages(ctx, kafka.Message{Key: []byte(relationshipID.String()), Value: value})
}

func (p *Pipeline) publishCopiedTradeClosed(ctx context.Context, relationshipID uuid.UUID, event domain.NormalizedTradeEvent) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopiedTradeClosed(ctx, relationshipID, event)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopiedTradeClosed(ctx context.Context, relationshipID uuid.UUID, event domain.NormalizedTradeEvent) error {
	msg := &eventsv1.CopiedTradeEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId: relationshipID.String(),
		EventType:          eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_CLOSED,
		BrokerPositionId:   event.Position.BrokerPositionID,
		Symbol: &eventsv1.NormalizedSymbol{
			CanonicalCode: event.Position.Symbol.CanonicalCode,
			AssetClass:    toProtoAssetClass(event.Position.Symbol.AssetClass),
		},
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopiedTradeEvent: %w", err)
	}
	return p.kafkaWriter.WriteMessages(ctx, kafka.Message{Key: []byte(relationshipID.String()), Value: value})
}

// publishCopiedTradeModified is TICKET-107 AC4's "still logged/visible for
// transparency" requirement's real, testable deliverable -- published
// whether or not the modification was actually applied. rejectReason is
// non-empty ONLY for the pinned-SL/TP-not-applied case (widening
// CopiedTradeEvent.reject_reason's original "set only for FAILED" doc
// comment -- see copied_trade_event.proto -- rather than adding a new proto
// field for what's otherwise an identical wire shape).
func (p *Pipeline) publishCopiedTradeModified(ctx context.Context, relationshipID uuid.UUID, event domain.NormalizedTradeEvent, rejectReason string) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopiedTradeModified(ctx, relationshipID, event, rejectReason)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopiedTradeModified(ctx context.Context, relationshipID uuid.UUID, event domain.NormalizedTradeEvent, rejectReason string) error {
	msg := &eventsv1.CopiedTradeEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId: relationshipID.String(),
		EventType:          eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_MODIFIED,
		BrokerPositionId:   event.Position.BrokerPositionID,
		Symbol: &eventsv1.NormalizedSymbol{
			CanonicalCode: event.Position.Symbol.CanonicalCode,
			AssetClass:    toProtoAssetClass(event.Position.Symbol.AssetClass),
		},
		RejectReason: nullIfEmpty(rejectReason),
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopiedTradeEvent: %w", err)
	}
	return p.kafkaWriter.WriteMessages(ctx, kafka.Message{Key: []byte(relationshipID.String()), Value: value})
}
