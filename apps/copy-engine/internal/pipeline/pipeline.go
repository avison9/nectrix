// Package pipeline is the Copy Engine pipeline: Normalizer -> Dedup Filter ->
// Relationship Matcher -> Money Management -> Risk Guard -> Order Dispatcher
// -> publish, built directly off docs/08-copy-trading-engine.md §8.2 and
// Appendix A.1-A.3's pseudocode. TICKET-106 wires the real sizing/risk-guard/
// SL-TP/idempotency stages into the Order Dispatcher, replacing the earlier
// STUB_1_TO_1_COPY placeholder.
package pipeline

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/observability"
	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

// finishSpan records err (if any) on span before ending it — every pipeline
// stage's span uses this so a failed stage is visually distinct in Tempo,
// not just a silently-ended span.
func finishSpan(span trace.Span, err error) {
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
	}
	span.End()
}

// Pipeline holds everything HandleEvent needs. TICKET-106: the single
// shared adapter/followerHandle fields from the stub era are replaced by
// router, which resolves the right RemoteAdapter per relationship's actual
// master/follower domain.BrokerType at dispatch time -- master and follower
// accounts in one relationship may be on different broker types entirely
// (the whole point of the cross-broker acceptance criteria), so a single
// fixed adapter/handle pair can no longer be injected at construction.
type Pipeline struct {
	pool        *pgxpool.Pool
	deduper     domain.Deduper
	router      *remoteadapter.Router
	fx          moneymgmt.FXRateProvider
	kafkaWriter *kafka.Writer
	// riskEventWriter/copyRelationshipEventWriter (TICKET-108) are separate
	// kafka.Writer instances for the "risk" and "copy-relationships" topics
	// -- this codebase's established convention is one Writer per topic
	// (mirrors main.go's own kafkaWriter/tradeSignalsDLQWriter pair), never a
	// shared multi-topic writer.
	riskEventWriter             *kafka.Writer
	copyRelationshipEventWriter *kafka.Writer
	// reconciliationEventWriter (TICKET-109) is the "reconciliation" topic's
	// own Writer, same one-writer-per-topic convention.
	reconciliationEventWriter *kafka.Writer

	// selfStatusMu guards the two fields below -- the Engine Control page's
	// own self-reported snapshot, set at the end of every successful
	// CheckReconciliationOnce sweep (see reconcile.go's own comment).
	selfStatusMu            sync.Mutex
	lastReconcileAt         time.Time
	activeRelationshipCount int
}

func New(pool *pgxpool.Pool, deduper domain.Deduper, router *remoteadapter.Router, fx moneymgmt.FXRateProvider, kafkaWriter *kafka.Writer, riskEventWriter *kafka.Writer, copyRelationshipEventWriter *kafka.Writer, reconciliationEventWriter *kafka.Writer) *Pipeline {
	return &Pipeline{
		pool:                        pool,
		deduper:                     deduper,
		router:                      router,
		fx:                          fx,
		kafkaWriter:                 kafkaWriter,
		riskEventWriter:             riskEventWriter,
		copyRelationshipEventWriter: copyRelationshipEventWriter,
		reconciliationEventWriter:   reconciliationEventWriter,
	}
}

// HandleEvent is the onEvent callback registered via
// domain.BrokerAdapter.StreamTradeEvents -- the Go shape of Appendix A.1's
// onBrokerAdapterEvent.
//
// TICKET-010 AC2: the root span here (and every child span nested under it
// across dedup/relationship-match/dispatch/publish) shares whatever trace
// was already active on ctx -- when called via httpapi's inject endpoint,
// that's the otelhttp-created HTTP-request span, so the whole pipeline run
// is one single trace, from ingestion through to the published
// CopiedTradeEvent, exactly what AC2 asks for.
func (p *Pipeline) HandleEvent(ctx context.Context, event domain.NormalizedTradeEvent) error {
	ctx, rootSpan := observability.Tracer().Start(ctx, "pipeline.handle_event", trace.WithAttributes(
		attribute.String("nectrix.event_id", event.EventID),
		attribute.String("nectrix.master_broker_account_id", event.MasterBrokerAccountID),
		attribute.String("nectrix.broker_position_id", event.Position.BrokerPositionID),
		attribute.String("nectrix.event_type", string(event.EventType)),
	))
	var err error
	defer func() { finishSpan(rootSpan, err) }()

	if err = p.normalizeStage(ctx, event); err != nil {
		return fmt.Errorf("pipeline: normalize: %w", err)
	}

	var masterAccountID uuid.UUID
	masterAccountID, err = uuid.Parse(event.MasterBrokerAccountID)
	if err != nil {
		return fmt.Errorf("pipeline: invalid masterBrokerAccountId %q: %w", event.MasterBrokerAccountID, err)
	}

	var signalID uuid.UUID
	var proceed bool
	signalID, proceed, err = p.dedupStage(ctx, masterAccountID, event)
	if err != nil {
		return fmt.Errorf("pipeline: dedup: %w", err)
	}
	if !proceed {
		return nil
	}

	err = p.processSignalForAllRelationships(ctx, masterAccountID, signalID, event)
	return err
}

func (p *Pipeline) normalizeStage(ctx context.Context, event domain.NormalizedTradeEvent) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.normalize")
	err := normalize(event)
	finishSpan(span, err)
	return err
}

// dedupStage is the Dedup Filter (docs/08-copy-trading-engine.md §8.2 point
// 2 / Appendix A.1): fast path via Redis, durable guard via trade_signals'
// unique constraint -- Redis is a fast-path optimization, Postgres is the
// durable guard (docs/15-event-driven-architecture.md §15.5). Returns
// proceed=false (not an error) whenever the event was already
// processed -- either dedup layer catching it is a normal, expected outcome.
func (p *Pipeline) dedupStage(ctx context.Context, masterAccountID uuid.UUID, event domain.NormalizedTradeEvent) (uuid.UUID, bool, error) {
	ctx, span := observability.Tracer().Start(ctx, "pipeline.dedup")
	var err error
	defer func() { finishSpan(span, err) }()

	dedupeKey := buildDedupeKey(event)
	var seen bool
	seen, err = p.deduper.SeenBefore(ctx, dedupeKey)
	if err != nil {
		return uuid.Nil, false, err
	}
	if seen {
		return uuid.Nil, false, nil // fast-path drop, already seen recently
	}

	var signalID uuid.UUID
	var inserted bool
	signalID, inserted, err = p.insertTradeSignal(ctx, masterAccountID, event)
	if err != nil {
		return uuid.Nil, false, err
	}
	if !inserted {
		// Unique-constraint violation: durable dedupe caught a redelivery
		// Redis's fast path missed (e.g. a Redis flush/restart) -- exactly
		// what makes AC3 true regardless of Redis's own race behavior.
		return uuid.Nil, false, nil
	}
	return signalID, true, nil
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
