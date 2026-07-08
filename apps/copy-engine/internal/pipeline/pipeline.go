// Package pipeline is TICKET-009's minimal Copy Engine pipeline shape:
// Normalizer -> Dedup Filter -> Relationship Matcher -> Order Dispatcher ->
// publish, built directly off docs/08-copy-trading-engine.md §8.2 and
// Appendix A.1-A.3's pseudocode. Money-management/risk-guard formulas are
// genuinely out of scope (Phase 1, docs/09-money-management-risk-formulas.md)
// -- the Order Dispatcher here does a straight 1:1 volume copy instead of
// running a real sizing formula.
package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
)

// Pipeline holds everything HandleEvent needs. followerHandle is a single,
// pre-connected BrokerAdapter handle for the one follower account this
// stub-era pipeline dispatches to -- real multi-follower connection
// management is Phase 1 scope; dispatchOrder (see dispatch.go) refuses to
// silently misroute an order to the wrong account if a relationship's
// follower doesn't match this handle.
type Pipeline struct {
	pool           *pgxpool.Pool
	deduper        domain.Deduper
	adapter        domain.BrokerAdapter
	followerHandle domain.ConnectionHandle
	kafkaWriter    *kafka.Writer
}

func New(pool *pgxpool.Pool, deduper domain.Deduper, adapter domain.BrokerAdapter, followerHandle domain.ConnectionHandle, kafkaWriter *kafka.Writer) *Pipeline {
	return &Pipeline{pool: pool, deduper: deduper, adapter: adapter, followerHandle: followerHandle, kafkaWriter: kafkaWriter}
}

// HandleEvent is the onEvent callback registered via
// domain.BrokerAdapter.StreamTradeEvents -- the Go shape of Appendix A.1's
// onBrokerAdapterEvent.
func (p *Pipeline) HandleEvent(ctx context.Context, event domain.NormalizedTradeEvent) error {
	if err := normalize(event); err != nil {
		return fmt.Errorf("pipeline: normalize: %w", err)
	}

	masterAccountID, err := uuid.Parse(event.MasterBrokerAccountID)
	if err != nil {
		return fmt.Errorf("pipeline: invalid masterBrokerAccountId %q: %w", event.MasterBrokerAccountID, err)
	}

	// Dedup Filter (docs/08-copy-trading-engine.md §8.2 point 2 / Appendix
	// A.1): fast path via Redis, durable guard via trade_signals' unique
	// constraint -- Redis is a fast-path optimization, Postgres is the
	// durable guard (docs/15-event-driven-architecture.md §15.5).
	dedupeKey := buildDedupeKey(event)
	seen, err := p.deduper.SeenBefore(ctx, dedupeKey)
	if err != nil {
		return fmt.Errorf("pipeline: dedup check: %w", err)
	}
	if seen {
		return nil // fast-path drop, already seen recently
	}

	signalID, inserted, err := p.insertTradeSignal(ctx, masterAccountID, event)
	if err != nil {
		return fmt.Errorf("pipeline: insert trade_signals: %w", err)
	}
	if !inserted {
		// Unique-constraint violation: durable dedupe caught a redelivery
		// Redis's fast path missed (e.g. a Redis flush/restart) -- exactly
		// what makes AC3 true regardless of Redis's own race behavior.
		return nil
	}

	return p.processSignalForAllRelationships(ctx, masterAccountID, signalID, event)
}

func normalize(event domain.NormalizedTradeEvent) error {
	switch {
	case event.EventID == "":
		return errors.New("eventId is required")
	case event.MasterBrokerAccountID == "":
		return errors.New("masterBrokerAccountId is required")
	case event.Position.BrokerPositionID == "":
		return errors.New("position.brokerPositionId is required")
	case event.ServerTimestamp == "":
		return errors.New("serverTimestamp is required")
	}
	return nil
}

// buildDedupeKey matches Appendix A.1's buildDedupeKey exactly -- the
// composite (master, position, eventType, serverTimestamp) key, not the
// event envelope's event_id (that's a different dedup mechanism used
// elsewhere for Kafka topic consumers, see packages/event-contracts/go's
// eventconsumer package).
func buildDedupeKey(event domain.NormalizedTradeEvent) string {
	return fmt.Sprintf("signal:%s:%s:%s:%s",
		event.MasterBrokerAccountID, event.Position.BrokerPositionID, event.EventType, event.ServerTimestamp)
}

// insertTradeSignal is Appendix A.1's postgres.tryInsert("trade_signals", ...)
// -- returns inserted=false (not an error) on a 23505 unique_violation.
func (p *Pipeline) insertTradeSignal(ctx context.Context, masterAccountID uuid.UUID, event domain.NormalizedTradeEvent) (uuid.UUID, bool, error) {
	serverTS, err := time.Parse(time.RFC3339, event.ServerTimestamp)
	if err != nil {
		return uuid.Nil, false, fmt.Errorf("invalid serverTimestamp %q: %w", event.ServerTimestamp, err)
	}
	rawPayload, err := json.Marshal(event)
	if err != nil {
		return uuid.Nil, false, fmt.Errorf("marshal raw_payload: %w", err)
	}

	var signalID uuid.UUID
	err = p.pool.QueryRow(ctx, `
		INSERT INTO trade_signals (
			master_broker_account_id, broker_position_id, event_type, canonical_symbol,
			direction, volume_lots, closed_volume_lots, fill_price, sl_price, tp_price,
			server_timestamp, raw_payload
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		RETURNING id`,
		masterAccountID, event.Position.BrokerPositionID, string(event.EventType), event.Position.Symbol.CanonicalCode,
		string(event.Position.Direction), event.Position.VolumeLots, event.ClosedVolumeLots, event.FillPrice,
		event.Position.CurrentSLPrice, event.Position.CurrentTPPrice, serverTS, rawPayload,
	).Scan(&signalID)

	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return uuid.Nil, false, nil
		}
		return uuid.Nil, false, err
	}
	return signalID, true, nil
}
