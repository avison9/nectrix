package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/segmentio/kafka-go"
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
	rows, err := p.pool.Query(ctx, `
		SELECT id, follower_broker_account_id
		FROM copy_relationships
		WHERE master_broker_account_id = $1 AND status = 'ACTIVE'`, masterAccountID)
	if err != nil {
		return fmt.Errorf("query copy_relationships: %w", err)
	}
	defer rows.Close()

	var relationships []relationship
	for rows.Next() {
		var r relationship
		if err := rows.Scan(&r.id, &r.followerBrokerAccountID); err != nil {
			return fmt.Errorf("scan copy_relationships row: %w", err)
		}
		relationships = append(relationships, r)
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("iterate copy_relationships rows: %w", err)
	}

	for _, r := range relationships {
		if err := p.dispatchOrder(ctx, r, signalID, event); err != nil {
			return fmt.Errorf("dispatch order for relationship %s: %w", r.id, err)
		}
	}
	return nil
}

// dispatchOrder is the Order Dispatcher (Appendix A.3's handleOpen,
// stubbed): no money-management/risk-guard formulas (Phase 1) -- volume is
// a straight 1:1 copy of the master's VolumeLots. Calls the follower's
// BrokerAdapter.PlaceOrder, persists one copied_trades row, and publishes
// CopiedTradeEvent(OPENED) to the copied-trades Kafka topic.
func (p *Pipeline) dispatchOrder(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
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

func (p *Pipeline) publishCopiedTradeOpened(ctx context.Context, relationshipID uuid.UUID, result domain.NormalizedOrderResult, event domain.NormalizedTradeEvent) error {
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
