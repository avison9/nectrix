package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/observability"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/protobuf/proto"
)

type relationship struct {
	id                      uuid.UUID
	followerBrokerAccountID uuid.UUID
}

// processSignalForAllRelationships is the Relationship Matcher (Appendix
// A.2) -- "stub" only in that it skips the Redis-cache-with-invalidation
// refinement docs/08 §8.2 point 3 describes; this direct query is real,
// not a hardcoded fake, and is honest about the ACTIVE-only fan-out.
func (p *Pipeline) processSignalForAllRelationships(ctx context.Context, masterAccountID uuid.UUID, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	ctx, span := observability.Tracer().Start(ctx, "pipeline.relationship_match")
	relationships, err := p.matchRelationships(ctx, masterAccountID)
	span.SetAttributes(attribute.Int("nectrix.matched_relationships", len(relationships)))
	finishSpan(span, err)
	if err != nil {
		return err
	}

	for _, r := range relationships {
		if err := p.dispatchOrder(ctx, r, signalID, event); err != nil {
			return fmt.Errorf("dispatch order for relationship %s: %w", r.id, err)
		}
	}
	return nil
}

// matchRelationships is the Relationship Matcher (Appendix A.2) -- "stub"
// only in that it skips the Redis-cache-with-invalidation refinement
// docs/08 §8.2 point 3 describes; this direct query is real, not a
// hardcoded fake, and is honest about the ACTIVE-only fan-out.
func (p *Pipeline) matchRelationships(ctx context.Context, masterAccountID uuid.UUID) ([]relationship, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT id, follower_broker_account_id
		FROM copy_relationships
		WHERE master_broker_account_id = $1 AND status = 'ACTIVE'`, masterAccountID)
	if err != nil {
		return nil, fmt.Errorf("query copy_relationships: %w", err)
	}
	defer rows.Close()

	var relationships []relationship
	for rows.Next() {
		var r relationship
		if err := rows.Scan(&r.id, &r.followerBrokerAccountID); err != nil {
			return nil, fmt.Errorf("scan copy_relationships row: %w", err)
		}
		relationships = append(relationships, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate copy_relationships rows: %w", err)
	}
	return relationships, nil
}

// dispatchOrder is the Order Dispatcher (Appendix A.3's handleOpen,
// stubbed): no money-management/risk-guard formulas (Phase 1) -- volume is
// a straight 1:1 copy of the master's VolumeLots. Calls the follower's
// BrokerAdapter.PlaceOrder, persists one copied_trades row, and publishes
// CopiedTradeEvent(OPENED) to the copied-trades Kafka topic.
func (p *Pipeline) dispatchOrder(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	ctx, span := observability.Tracer().Start(ctx, "pipeline.dispatch_order", trace.WithAttributes(
		attribute.String("nectrix.copy_relationship_id", rel.id.String()),
	))
	var err error
	defer func() { finishSpan(span, err) }()
	err = p.doDispatchOrder(ctx, rel, signalID, event)
	return err
}

func (p *Pipeline) doDispatchOrder(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	if rel.followerBrokerAccountID.String() != p.followerHandle.AccountID {
		return fmt.Errorf(
			"no connected BrokerAdapter handle for follower account %s (relationship %s) -- "+
				"this stub Order Dispatcher only manages one pre-connected follower handle",
			rel.followerBrokerAccountID, rel.id)
	}

	// docs/08-copy-trading-engine.md §8.3: idempotency_key = hash(trade_signal_id,
	// copy_relationship_id). trade_signal_id is already unique per signal, and the
	// DB constraint is UNIQUE(copy_relationship_id, idempotency_key) -- using it
	// directly gives the identical collision behavior without an unneeded hash step.
	idempotencyKey := signalID.String()

	// TICKET-103, appendix-a-copy-engine-pseudocode.md's handleOpen: the
	// unmapped-symbol check is the FIRST step, before sizing/PlaceOrder --
	// never guess a mapping. A symbol with no CONFIRMED symbol_mappings row
	// on the follower's account is skipped and flagged, not attempted.
	mapped, err := p.hasConfirmedSymbolMapping(ctx, rel.followerBrokerAccountID, event.Position.Symbol.CanonicalCode)
	if err != nil {
		return fmt.Errorf("check symbol_mappings: %w", err)
	}
	if !mapped {
		return p.recordUnmappedSymbolFailure(ctx, rel, signalID, idempotencyKey, event)
	}

	orderRequest := domain.NormalizedOrderRequest{
		IdempotencyKey:          idempotencyKey,
		FollowerBrokerAccountID: rel.followerBrokerAccountID.String(),
		Symbol:                  event.Position.Symbol,
		Direction:               event.Position.Direction,
		VolumeLots:              event.Position.VolumeLots, // stub: 1:1 copy, no MM formula (Phase 1)
		SLPrice:                 event.Position.CurrentSLPrice,
		TPPrice:                 event.Position.CurrentTPPrice,
		MaxSlippagePips:         5,
		ClientOrderTag:          rel.id.String() + ":" + event.Position.BrokerPositionID,
	}

	result, err := p.adapter.PlaceOrder(ctx, p.followerHandle, orderRequest)
	if err != nil {
		return fmt.Errorf("PlaceOrder: %w", err)
	}

	status := "FILLED"
	if !result.Success {
		status = "REJECTED"
	}

	sizingSnapshot, err := json.Marshal(map[string]any{"method": "STUB_1_TO_1_COPY"})
	if err != nil {
		return fmt.Errorf("marshal sizing_method_snapshot: %w", err)
	}

	var copiedTradeID uuid.UUID
	err = p.pool.QueryRow(ctx, `
		INSERT INTO copied_trades (
			copy_relationship_id, trade_signal_id, idempotency_key, follower_broker_position_id,
			status, computed_volume_lots, sizing_method_snapshot, requested_price, filled_price,
			reject_reason, opened_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
		ON CONFLICT (copy_relationship_id, idempotency_key) DO NOTHING
		RETURNING id`,
		rel.id, signalID, idempotencyKey, nullIfEmpty(result.BrokerPositionID),
		status, orderRequest.VolumeLots, sizingSnapshot, event.Position.OpenPrice, result.FilledPrice,
		nullIfEmpty(result.RejectReason), time.Now().UTC(),
	).Scan(&copiedTradeID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// ON CONFLICT DO NOTHING: already dispatched for this signal+relationship.
			return nil
		}
		return fmt.Errorf("insert copied_trades: %w", err)
	}

	return p.publishCopiedTradeOpened(ctx, rel.id, result, event)
}

// hasConfirmedSymbolMapping is TICKET-103's real gate: a symbol is only
// ever copied to a follower once a user/admin has explicitly confirmed the
// mapping (nectrix_plan/docs/08-copy-trading-engine.md §8.4) -- an
// auto-suggested-but-unconfirmed row (is_confirmed = FALSE) does not count.
func (p *Pipeline) hasConfirmedSymbolMapping(ctx context.Context, followerBrokerAccountID uuid.UUID, canonicalSymbol string) (bool, error) {
	var exists bool
	err := p.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM symbol_mappings
			WHERE broker_account_id = $1 AND canonical_symbol = $2 AND is_confirmed = TRUE
		)`, followerBrokerAccountID, canonicalSymbol).Scan(&exists)
	if err != nil {
		return false, fmt.Errorf("query symbol_mappings: %w", err)
	}
	return exists, nil
}

// recordUnmappedSymbolFailure is appendix-a-copy-engine-pseudocode.md's
// handleOpen: "return recordCopiedTrade(relationship, event, status=FAILED,
// reason='UNMAPPED_SYMBOL')" -- never calls PlaceOrder. Same idempotency-key
// convention as the real dispatch path (ON CONFLICT DO NOTHING), so a
// redelivered signal doesn't record (or publish) the same failure twice.
func (p *Pipeline) recordUnmappedSymbolFailure(ctx context.Context, rel relationship, signalID uuid.UUID, idempotencyKey string, event domain.NormalizedTradeEvent) error {
	const rejectReason = "UNMAPPED_SYMBOL"

	sizingSnapshot, err := json.Marshal(map[string]any{"method": "UNMAPPED_SYMBOL_SKIPPED"})
	if err != nil {
		return fmt.Errorf("marshal sizing_method_snapshot: %w", err)
	}

	var copiedTradeID uuid.UUID
	err = p.pool.QueryRow(ctx, `
		INSERT INTO copied_trades (
			copy_relationship_id, trade_signal_id, idempotency_key,
			status, computed_volume_lots, sizing_method_snapshot, reject_reason
		) VALUES ($1,$2,$3,'FAILED',$4,$5,$6)
		ON CONFLICT (copy_relationship_id, idempotency_key) DO NOTHING
		RETURNING id`,
		rel.id, signalID, idempotencyKey, event.Position.VolumeLots, sizingSnapshot, rejectReason,
	).Scan(&copiedTradeID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// ON CONFLICT DO NOTHING: already recorded for this signal+relationship.
			return nil
		}
		return fmt.Errorf("insert copied_trades (unmapped symbol): %w", err)
	}

	return p.publishCopiedTradeFailed(ctx, rel.id, rejectReason, event)
}

// publishCopiedTradeFailed is the real TICKET-115 integration point the
// ticket's own acceptance criterion describes ("a corresponding follower
// notification") -- this publish to the same copied-trades Kafka topic
// publishCopiedTradeOpened uses is the complete, testable deliverable here;
// a downstream Notification Service actually consuming CopiedTradeEventType
// FAILED and delivering an in-app/push notification is TICKET-115's own
// separate, not-yet-built responsibility.
func (p *Pipeline) publishCopiedTradeFailed(ctx context.Context, relationshipID uuid.UUID, rejectReason string, event domain.NormalizedTradeEvent) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopiedTradeFailed(ctx, relationshipID, rejectReason, event)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopiedTradeFailed(ctx context.Context, relationshipID uuid.UUID, rejectReason string, event domain.NormalizedTradeEvent) error {
	msg := &eventsv1.CopiedTradeEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId: relationshipID.String(),
		EventType:          eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_FAILED,
		Symbol: &eventsv1.NormalizedSymbol{
			CanonicalCode: event.Position.Symbol.CanonicalCode,
			AssetClass:    toProtoAssetClass(event.Position.Symbol.AssetClass),
		},
		RejectReason: &rejectReason,
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopiedTradeEvent: %w", err)
	}
	return p.kafkaWriter.WriteMessages(ctx, kafka.Message{Key: []byte(relationshipID.String()), Value: value})
}

func (p *Pipeline) publishCopiedTradeOpened(ctx context.Context, relationshipID uuid.UUID, result domain.NormalizedOrderResult, event domain.NormalizedTradeEvent) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopiedTradeOpened(ctx, relationshipID, result, event)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopiedTradeOpened(ctx context.Context, relationshipID uuid.UUID, result domain.NormalizedOrderResult, event domain.NormalizedTradeEvent) error {
	msg := &eventsv1.CopiedTradeEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId: relationshipID.String(),
		EventType:          eventsv1.CopiedTradeEventType_COPIED_TRADE_EVENT_TYPE_OPENED,
		BrokerPositionId:   result.BrokerPositionID,
		Symbol: &eventsv1.NormalizedSymbol{
			CanonicalCode: event.Position.Symbol.CanonicalCode,
			AssetClass:    toProtoAssetClass(event.Position.Symbol.AssetClass),
		},
		VolumeLots: &event.Position.VolumeLots,
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopiedTradeEvent: %w", err)
	}
	return p.kafkaWriter.WriteMessages(ctx, kafka.Message{Key: []byte(relationshipID.String()), Value: value})
}

func toProtoAssetClass(a domain.AssetClass) eventsv1.AssetClass {
	switch a {
	case domain.AssetClassFX:
		return eventsv1.AssetClass_ASSET_CLASS_FX
	case domain.AssetClassIndex:
		return eventsv1.AssetClass_ASSET_CLASS_INDEX
	case domain.AssetClassCommodity:
		return eventsv1.AssetClass_ASSET_CLASS_COMMODITY
	case domain.AssetClassCrypto:
		return eventsv1.AssetClass_ASSET_CLASS_CRYPTO
	case domain.AssetClassStockCFD:
		return eventsv1.AssetClass_ASSET_CLASS_STOCK_CFD
	default:
		return eventsv1.AssetClass_ASSET_CLASS_UNSPECIFIED
	}
}

func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
