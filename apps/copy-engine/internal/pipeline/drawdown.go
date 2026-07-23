// TICKET-108: periodic per-relationship drawdown monitoring --
// docs/08-copy-trading-engine.md §8.8 / docs/09-money-management-risk-
// formulas.md §9.7's two-tier model. Unlike dispatch.go/partialclose.go
// (event-driven, triggered by a master's own trade signals), this is an
// account-level safety mechanism that must keep running even when a master
// isn't sending any signals at all -- a follower's account can drift into
// drawdown purely from market movement on already-open positions.
package pipeline

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/observability"
	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// loadActiveRelationshipsForDrawdownCheck is the drawdown monitor's own
// GLOBAL sweep query -- unlike matchRelationships (scoped to one master's
// masterAccountID, used only by the event-driven dispatch path), this
// periodic sweep runs across every active relationship platform-wide.
// Populates relationship.createdAt (matchRelationships' own SELECT/Scan is
// untouched and never populates it) -- the default "all-time-high since
// relationship start" rolling-window floor, §9.7's own recommendation.
func (p *Pipeline) loadActiveRelationshipsForDrawdownCheck(ctx context.Context) ([]relationship, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT id, master_broker_account_id, follower_broker_account_id,
		       money_management_profile_id, risk_profile_id, copy_direction, created_at
		FROM copy_relationships WHERE status = 'ACTIVE'`)
	if err != nil {
		return nil, fmt.Errorf("query copy_relationships for drawdown check: %w", err)
	}
	defer rows.Close()

	var relationships []relationship
	for rows.Next() {
		var r relationship
		if err := rows.Scan(&r.id, &r.masterBrokerAccountID, &r.followerBrokerAccountID, &r.moneyManagementProfileID, &r.riskProfileID, &r.copyDirection, &r.createdAt); err != nil {
			return nil, fmt.Errorf("scan copy_relationships row: %w", err)
		}
		relationships = append(relationships, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate copy_relationships rows: %w", err)
	}
	return relationships, nil
}

// RunDrawdownMonitor polls every interval until ctx is cancelled, mirroring
// apps/broker-adapters/internal/reconcile.Loop.Run's own immediate-tick-
// then-ticker shape: a fresh process shouldn't wait a full interval before
// its first check.
func (p *Pipeline) RunDrawdownMonitor(ctx context.Context, interval time.Duration) {
	p.runDrawdownCheckLogged(ctx)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.runDrawdownCheckLogged(ctx)
		}
	}
}

func (p *Pipeline) runDrawdownCheckLogged(ctx context.Context) {
	if err := p.CheckDrawdownOnce(ctx); err != nil {
		slog.Default().Error("pipeline: drawdown monitor tick failed", "error", err)
	}
}

// CheckDrawdownOnce is exported (unlike reconcile.Loop's own unexported
// reconcileOnce) because this ticket's integration tests live in package
// main at the top level of apps/copy-engine, alongside every other
// TICKET-106/107 integration test, and can only call exported Pipeline
// methods -- calling this directly (not waiting on the real ticker) is how
// those tests get deterministic, synchronous drawdown checks.
//
// A per-relationship failure is logged and the sweep continues to the next
// one, mirroring reconcile.reconcileOnce's own established
// one-bad-account-never-blocks-the-others precedent -- only a failure of
// the sweep query itself is a real, returned error.
func (p *Pipeline) CheckDrawdownOnce(ctx context.Context) error {
	relationships, err := p.loadActiveRelationshipsForDrawdownCheck(ctx)
	if err != nil {
		return fmt.Errorf("load active relationships for drawdown check: %w", err)
	}
	for _, rel := range relationships {
		if err := p.checkRelationshipDrawdown(ctx, rel); err != nil {
			slog.Default().Error("pipeline: drawdown check failed for relationship", "copyRelationshipId", rel.id, "error", err)
		}
	}
	return nil
}

// checkRelationshipDrawdown is docs/09 §9.7's formula, in full, for one
// relationship.
func (p *Pipeline) checkRelationshipDrawdown(ctx context.Context, rel relationship) error {
	riskProfile, err := p.loadRiskProfile(ctx, rel.riskProfileID)
	if err != nil {
		return fmt.Errorf("load risk profile: %w", err)
	}
	// Neither threshold configured -- skip entirely, including the live
	// broker call below, so relationships that never enabled this feature
	// don't pay for a GetAccountSnapshot round trip every single tick.
	if riskProfile.DrawdownPausePct == nil && riskProfile.DrawdownCloseAllPct == nil {
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

	snapshot, err := followerRemote.GetAccountSnapshot(ctx, rel.followerBrokerAccountID.String())
	if err != nil {
		return fmt.Errorf("get follower account snapshot: %w", err)
	}
	// This is what gives account_snapshots its first real REGULAR writer
	// (TICKET-106's writeAccountSnapshotBestEffort was previously only an
	// incidental byproduct of an actual trade dispatch) -- best-effort,
	// logged on failure, never blocks the drawdown computation below.
	p.writeAccountSnapshotBestEffort(ctx, rel.followerBrokerAccountID, snapshot)

	var historicalMax float64
	err = p.pool.QueryRow(ctx, `
		SELECT COALESCE(MAX(equity), 0) FROM account_snapshots
		WHERE broker_account_id = $1 AND captured_at >= $2`,
		rel.followerBrokerAccountID, rel.createdAt,
	).Scan(&historicalMax)
	if err != nil {
		return fmt.Errorf("query rolling high equity: %w", err)
	}
	// Folds in the just-fetched live equity regardless of whether the
	// best-effort write above actually landed -- correctness here never
	// depends on that write succeeding.
	rollingHigh := math.Max(historicalMax, snapshot.Equity)
	if rollingHigh <= 0 {
		// No meaningful drawdown definable for a degenerate/zero-equity
		// account -- defensive, not expected in practice.
		return nil
	}

	currentDrawdownPct, pauseTriggered, forceCloseTriggered := evaluateDrawdown(rollingHigh, snapshot.Equity, riskProfile.DrawdownPausePct, riskProfile.DrawdownCloseAllPct)
	if !pauseTriggered && !forceCloseTriggered {
		return nil
	}

	// PAUSE is published before any force-close action, matching both
	// §9.7's own block order and the natural notification narrative --
	// "you're being paused" logically precedes "and your positions are
	// being liquidated". Both independently fire when both thresholds are
	// crossed in the same tick (crossing the stricter drawdown_close_all_pct
	// always also crosses the looser drawdown_pause_pct in any sane
	// configuration) -- two distinct, accurately-thresholded audit events,
	// not a duplicate of the same one.
	if pauseTriggered {
		if err := p.publishRiskEvent(ctx, rel.id, currentDrawdownPct, *riskProfile.DrawdownPausePct, eventsv1.RiskEventSeverity_RISK_EVENT_SEVERITY_PAUSE); err != nil {
			return fmt.Errorf("publish drawdown pause risk event: %w", err)
		}
	}
	if forceCloseTriggered {
		p.forceCloseAllOpenPositions(ctx, rel, followerRemote)
		if err := p.publishRiskEvent(ctx, rel.id, currentDrawdownPct, *riskProfile.DrawdownCloseAllPct, eventsv1.RiskEventSeverity_RISK_EVENT_SEVERITY_FORCE_CLOSE); err != nil {
			return fmt.Errorf("publish drawdown force-close risk event: %w", err)
		}
	}

	// One state transition regardless of how many thresholds fired --
	// publish CopyRelationshipEvent{PAUSED} only if this call actually
	// flipped the row (guards a same-tick double-fire).
	paused, err := p.pauseRelationshipIfActive(ctx, rel.id)
	if err != nil {
		return fmt.Errorf("pause relationship: %w", err)
	}
	if paused {
		if err := p.publishCopyRelationshipPaused(ctx, rel); err != nil {
			return fmt.Errorf("publish copy relationship paused event: %w", err)
		}
	}
	return nil
}

// evaluateDrawdown is docs/09 §9.7's two-tier threshold check, pure and
// DB-free -- mirrors the moneymgmt package's own ComputeLotSize/
// ApplyRiskGuard convention of keeping the actual math independently
// unit-testable without Postgres/Kafka. Both pausePct/closeAllPct are
// independently nullable (either, both, or neither may be configured).
func evaluateDrawdown(rollingHigh, currentEquity float64, pausePct, closeAllPct *float64) (currentDrawdownPct float64, pauseTriggered, forceCloseTriggered bool) {
	currentDrawdownPct = (rollingHigh - currentEquity) / rollingHigh * 100
	pauseTriggered = pausePct != nil && currentDrawdownPct >= *pausePct
	forceCloseTriggered = closeAllPct != nil && currentDrawdownPct >= *closeAllPct
	return currentDrawdownPct, pauseTriggered, forceCloseTriggered
}

// forceCloseAllOpenPositions mirrors partialclose.go's handleClose exactly
// (ClosePosition(..., nil) = full close) but iterates every open position
// under the relationship rather than one master-driven position. Best-effort
// per row: a single position's close failure (broker error or
// !result.Success) is logged and the loop continues to the next one --
// never aborts the whole sweep over one bad position. TICKET-109's
// Reconciliation Job (not yet built) is the eventual backstop for anything
// left stuck.
func (p *Pipeline) forceCloseAllOpenPositions(ctx context.Context, rel relationship, followerRemote remoteadapter.RemoteAdapter) {
	type openPosition struct {
		id                       uuid.UUID
		followerBrokerPositionID string
		currentOpenVolumeLots    float64
		filledPrice              *float64
		canonicalSymbol          string
		direction                string
	}

	// Bugfix — canonical_symbol/direction (via trade_signals) and filled_price/current_open_volume_lots
	// are the same inputs handleClose's own realized-P&L computation needs (see computeRealizedPnL's
	// own Javadoc) — this force-close path used to leave realized_pnl NULL forever, same gap.
	rows, err := p.pool.Query(ctx, `
		SELECT ct.id, ct.follower_broker_position_id, ct.current_open_volume_lots, ct.filled_price,
		       ts.canonical_symbol, ts.direction
		FROM copied_trades ct
		JOIN trade_signals ts ON ts.id = ct.trade_signal_id
		WHERE ct.copy_relationship_id = $1 AND ct.status IN ('FILLED','PARTIALLY_CLOSED')`, rel.id)
	if err != nil {
		slog.Default().Error("pipeline: query open copied_trades for force-close failed", "copyRelationshipId", rel.id, "error", err)
		return
	}
	var positions []openPosition
	for rows.Next() {
		var pos openPosition
		if err := rows.Scan(&pos.id, &pos.followerBrokerPositionID, &pos.currentOpenVolumeLots, &pos.filledPrice,
			&pos.canonicalSymbol, &pos.direction); err != nil {
			slog.Default().Error("pipeline: scan open copied_trades row for force-close failed", "copyRelationshipId", rel.id, "error", err)
			rows.Close()
			return
		}
		positions = append(positions, pos)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		slog.Default().Error("pipeline: iterate open copied_trades rows for force-close failed", "copyRelationshipId", rel.id, "error", err)
		return
	}

	for _, pos := range positions {
		result, err := followerRemote.ClosePosition(ctx, rel.followerBrokerAccountID.String(), pos.followerBrokerPositionID, nil)
		if err != nil {
			slog.Default().Error("pipeline: force-close ClosePosition failed", "copyRelationshipId", rel.id, "copiedTradeId", pos.id, "error", err)
			continue
		}
		if !result.Success {
			slog.Default().Warn("pipeline: force-close ClosePosition rejected by broker", "copyRelationshipId", rel.id, "copiedTradeId", pos.id, "rejectReason", result.RejectReason)
			continue
		}
		var realizedPnl *float64
		if pos.filledPrice != nil && result.FilledPrice != nil {
			realizedPnl = p.computeRealizedPnL(ctx, rel.followerBrokerAccountID,
				domain.NormalizedSymbol{CanonicalCode: pos.canonicalSymbol}, domain.TradeDirection(pos.direction),
				pos.currentOpenVolumeLots, *pos.filledPrice, *result.FilledPrice)
		}
		if _, err := p.pool.Exec(ctx, `UPDATE copied_trades SET status='CLOSED', current_open_volume_lots=0, closed_at=now(), realized_pnl=$1 WHERE id=$2`,
			realizedPnl, pos.id); err != nil {
			slog.Default().Error("pipeline: update copied_trades after force-close failed", "copyRelationshipId", rel.id, "copiedTradeId", pos.id, "error", err)
		}
	}
}

// pauseRelationshipIfActive is the guarded status transition -- the WHERE
// status='ACTIVE' clause makes this safe to call from a sweep that only
// ever selected ACTIVE rows to begin with, and the rows-affected check lets
// the caller publish CopyRelationshipEvent{PAUSED} exactly once per real
// transition.
func (p *Pipeline) pauseRelationshipIfActive(ctx context.Context, relationshipID uuid.UUID) (bool, error) {
	tag, err := p.pool.Exec(ctx, `UPDATE copy_relationships SET status='PAUSED' WHERE id=$1 AND status='ACTIVE'`, relationshipID)
	if err != nil {
		return false, fmt.Errorf("update copy_relationships: %w", err)
	}
	return tag.RowsAffected() > 0, nil
}

func (p *Pipeline) publishRiskEvent(ctx context.Context, relationshipID uuid.UUID, drawdownPct, thresholdPct float64, severity eventsv1.RiskEventSeverity) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishRiskEvent(ctx, relationshipID, drawdownPct, thresholdPct, severity)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishRiskEvent(ctx context.Context, relationshipID uuid.UUID, drawdownPct, thresholdPct float64, severity eventsv1.RiskEventSeverity) error {
	msg := &eventsv1.RiskEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId: relationshipID.String(),
		EventType:          eventsv1.RiskEventType_RISK_EVENT_TYPE_DRAWDOWN_THRESHOLD_BREACHED,
		DrawdownPct:        drawdownPct,
		ThresholdPct:       thresholdPct,
		Severity:           severity,
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal RiskEvent: %w", err)
	}
	return p.riskEventWriter.WriteMessages(ctx, kafka.Message{Key: []byte(relationshipID.String()), Value: value})
}

func (p *Pipeline) publishCopyRelationshipPaused(ctx context.Context, rel relationship) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishCopyRelationshipPaused(ctx, rel)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishCopyRelationshipPaused(ctx context.Context, rel relationship) error {
	msg := &eventsv1.CopyRelationshipEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		CopyRelationshipId:      rel.id.String(),
		EventType:               eventsv1.CopyRelationshipEventType_COPY_RELATIONSHIP_EVENT_TYPE_PAUSED,
		MasterBrokerAccountId:   rel.masterBrokerAccountID.String(),
		FollowerBrokerAccountId: rel.followerBrokerAccountID.String(),
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal CopyRelationshipEvent: %w", err)
	}
	return p.copyRelationshipEventWriter.WriteMessages(ctx, kafka.Message{Key: []byte(rel.id.String()), Value: value})
}
