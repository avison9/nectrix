// TICKET-111: force-closes open copied_trades for a CopyRelationship the
// user just stopped (AC4 -- "/stop force-closes all open positions"). The
// actual status transition (ACTIVE/PAUSED -> STOPPED) happens on the Java
// side (core-app's CopyRelationshipService), synchronously, in response to
// POST /copy-relationships/{id}/stop -- that endpoint does NOT force-close
// positions itself. This periodic poller is what actually does it,
// reusing drawdown.go's forceCloseAllOpenPositions exactly (same "iterate
// open copied_trades, ClosePosition, mark CLOSED, best-effort per row"
// behavior a drawdown-triggered force-close already has), just triggered
// by a different status value. A bounded poll-interval delay (same
// interval already tolerated for drawdown monitoring) rather than a new
// synchronous internal HTTP endpoint or a new Kafka topic/schema --
// consistent with this codebase's existing async/event-driven style for
// copy-engine <-> core-app coordination.
package pipeline

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/observability"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// loadStoppedRelationshipsWithOpenPositions -- the counterpart to
// loadActiveRelationshipsForDrawdownCheck's global ACTIVE sweep, but for
// STOPPED relationships that still have at least one open (FILLED or
// PARTIALLY_CLOSED) copied_trades row. The DISTINCT + join means a
// relationship already fully closed out (no more qualifying copied_trades)
// simply stops appearing here on the next tick -- no separate "already
// processed" bookkeeping needed.
func (p *Pipeline) loadStoppedRelationshipsWithOpenPositions(ctx context.Context) ([]relationship, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT DISTINCT cr.id, cr.master_broker_account_id, cr.follower_broker_account_id,
		       cr.money_management_profile_id, cr.risk_profile_id, cr.copy_direction
		FROM copy_relationships cr
		JOIN copied_trades ct ON ct.copy_relationship_id = cr.id
		WHERE cr.status = 'STOPPED' AND ct.status IN ('FILLED','PARTIALLY_CLOSED')`)
	if err != nil {
		return nil, fmt.Errorf("query stopped copy_relationships with open positions: %w", err)
	}
	defer rows.Close()

	var relationships []relationship
	for rows.Next() {
		var r relationship
		if err := rows.Scan(&r.id, &r.masterBrokerAccountID, &r.followerBrokerAccountID, &r.moneyManagementProfileID, &r.riskProfileID, &r.copyDirection); err != nil {
			return nil, fmt.Errorf("scan stopped copy_relationships row: %w", err)
		}
		relationships = append(relationships, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate stopped copy_relationships rows: %w", err)
	}
	return relationships, nil
}

// RunStopClosureMonitor polls every interval until ctx is cancelled -- same
// immediate-tick-then-ticker shape as RunDrawdownMonitor.
func (p *Pipeline) RunStopClosureMonitor(ctx context.Context, interval time.Duration) {
	p.runStopClosureCheckLogged(ctx)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.runStopClosureCheckLogged(ctx)
		}
	}
}

func (p *Pipeline) runStopClosureCheckLogged(ctx context.Context) {
	if err := p.CheckStopClosureOnce(ctx); err != nil {
		slog.Default().Error("pipeline: stop-closure monitor tick failed", "error", err)
	}
}

// CheckStopClosureOnce is exported for the same reason
// drawdown.go's CheckDrawdownOnce is -- deterministic, synchronous
// integration-test calls instead of waiting on the real ticker. A single
// relationship's failure is logged and the sweep continues, same
// one-bad-account-never-blocks-the-others precedent as the drawdown/
// reconciliation loops.
func (p *Pipeline) CheckStopClosureOnce(ctx context.Context) error {
	relationships, err := p.loadStoppedRelationshipsWithOpenPositions(ctx)
	if err != nil {
		return fmt.Errorf("load stopped relationships with open positions: %w", err)
	}
	for _, rel := range relationships {
		if err := p.closeStoppedRelationshipPositions(ctx, rel); err != nil {
			slog.Default().Error("pipeline: stop-closure failed for relationship", "copyRelationshipId", rel.id, "error", err)
		}
	}
	return nil
}

func (p *Pipeline) closeStoppedRelationshipPositions(ctx context.Context, rel relationship) error {
	followerBrokerType, err := p.loadBrokerType(ctx, rel.followerBrokerAccountID)
	if err != nil {
		return fmt.Errorf("load follower broker type: %w", err)
	}
	followerRemote, err := p.router.For(followerBrokerType)
	if err != nil {
		return fmt.Errorf("resolve follower remote adapter: %w", err)
	}
	p.forceCloseAllOpenPositions(ctx, rel, followerRemote)
	if err := p.publishCopyRelationshipStopped(ctx, rel); err != nil {
		return fmt.Errorf("publish copy relationship stopped event: %w", err)
	}
	return nil
}

func (p *Pipeline) publishCopyRelationshipStopped(ctx context.Context, rel relationship) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopyRelationshipStopped(ctx, rel)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopyRelationshipStopped(ctx context.Context, rel relationship) error {
	msg := &eventsv1.CopyRelationshipEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId:      rel.id.String(),
		EventType:               eventsv1.CopyRelationshipEventType_COPY_RELATIONSHIP_EVENT_TYPE_STOPPED,
		MasterBrokerAccountId:   rel.masterBrokerAccountID.String(),
		FollowerBrokerAccountId: rel.followerBrokerAccountID.String(),
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopyRelationshipEvent: %w", err)
	}
	return p.copyRelationshipEventWriter.WriteMessages(ctx, kafka.Message{Key: []byte(rel.id.String()), Value: value})
}
