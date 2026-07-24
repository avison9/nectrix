// TICKET-109: periodic reconciliation -- docs/08-copy-trading-engine.md
// §8.9 / appendix-a-copy-engine-pseudocode.md §A.8. Diffs actual
// broker-reported positions against platform-believed state and
// self-heals, but the remediation strategy differs by account role:
//
//   - MASTER side: believed state is derived from trade_signals (the
//     master's own event history). On any diff, this synthesizes a real
//     domain.NormalizedTradeEvent and replays it through p.HandleEvent --
//     the EXACT SAME entry point live stream events use (AC4's own
//     requirement, confirmed: no parallel path). This fans out through the
//     existing matchRelationships/dispatch/partial-close/close/modify
//     machinery, completely unchanged.
//   - FOLLOWER side: believed state is derived from copied_trades (status
//     FILLED/PARTIALLY_CLOSED). There is no NormalizedTradeEvent a
//     follower-driven correction could replay through p.HandleEvent --
//     that pipeline is master-signal-in/follower-order-out by shape, and a
//     follower position has no "originating master event" distinct from
//     the one already recorded. Inventing a fake master-side trigger here
//     would be more dangerous, not less. The follower's actual broker
//     state IS ground truth, so this corrects copied_trades DIRECTLY --
//     touching only status/current_open_volume_lots/
//     follower_broker_position_id/filled_price, never SL/TP (a follower
//     may have independently pinned SL/TP, TICKET-107's
//     risk_profiles.pin_follower_sl_tp -- "reconciling" it would be
//     actively wrong) and never volume increases (symmetric with the
//     master side's own growth exclusion).
package pipeline

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/observability"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// positionVolumeEpsilon absorbs float64 round-tripping noise (JSON/decimal
// conversions) in volume comparisons -- without it, AC2's "zero false
// positives on fully-synced accounts" would be at the mercy of
// floating-point exactness.
const positionVolumeEpsilon = 1e-6

// ==================== MASTER-SIDE ====================

// believedMasterPosition is derived from the latest trade_signals row per
// broker_position_id -- the master's own believed-open position state.
type believedMasterPosition struct {
	brokerPositionID string
	eventType        string
	canonicalSymbol  string
	direction        string
	volumeLots       float64
	slPrice          *float64
	tpPrice          *float64
}

// deriveBelievedMasterPositions is docs/08 §8.9 point 2's master-side half:
// "derived from ... the master's own trade_signals-derived state". Picks
// the LATEST row per broker_position_id (regardless of event_type -- an
// OPENED, MODIFIED, or PARTIALLY_CLOSED row can all be "latest"), then
// filters out CLOSED ones in Go (believed-closed, not open).
func (p *Pipeline) deriveBelievedMasterPositions(ctx context.Context, masterAccountID uuid.UUID) (map[string]believedMasterPosition, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT DISTINCT ON (broker_position_id) broker_position_id, event_type, canonical_symbol, direction, volume_lots, sl_price, tp_price
		FROM trade_signals
		WHERE master_broker_account_id = $1
		ORDER BY broker_position_id, server_timestamp DESC, received_at_gateway DESC`, masterAccountID)
	if err != nil {
		return nil, fmt.Errorf("query trade_signals for reconciliation: %w", err)
	}
	defer rows.Close()

	believed := make(map[string]believedMasterPosition)
	for rows.Next() {
		var bp believedMasterPosition
		if err := rows.Scan(&bp.brokerPositionID, &bp.eventType, &bp.canonicalSymbol, &bp.direction, &bp.volumeLots, &bp.slPrice, &bp.tpPrice); err != nil {
			return nil, fmt.Errorf("scan trade_signals row: %w", err)
		}
		if bp.eventType == string(domain.TradeEventPositionClosed) {
			continue
		}
		believed[bp.brokerPositionID] = bp
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate trade_signals rows: %w", err)
	}
	return believed, nil
}

type masterDriftType int

const (
	masterDriftMissedOpen masterDriftType = iota
	masterDriftMissedClose
	masterDriftMissedPartialClose
	masterDriftMissedModify
	masterDriftUnsupportedIncrease
)

// masterDrift pairs a diff with everything synthesizeMasterEvent/
// masterDriftEventTypeAndDetail need. actual is nil only for
// masterDriftMissedClose (the position no longer exists to read from).
type masterDrift struct {
	driftType        masterDriftType
	brokerPositionID string
	actual           *domain.NormalizedPosition
	believed         believedMasterPosition
}

// diffMasterPositions is pure and DB-free -- mirrors drawdown.go's own
// evaluateDrawdown precedent for direct unit-testability. CLOSE and
// PARTIAL_CLOSE are structurally mutually exclusive per broker_position_id
// (a full close means absent from actual, a partial close means present
// with lower volume) -- only PARTIAL_CLOSE + MODIFY can co-occur on the
// same position, and both are appended independently when they do.
func diffMasterPositions(actual []domain.NormalizedPosition, believed map[string]believedMasterPosition) []masterDrift {
	var drifts []masterDrift
	actualByID := make(map[string]domain.NormalizedPosition, len(actual))
	for _, pos := range actual {
		actualByID[pos.BrokerPositionID] = pos
	}

	for id, pos := range actualByID {
		posCopy := pos
		bp, ok := believed[id]
		if !ok {
			drifts = append(drifts, masterDrift{driftType: masterDriftMissedOpen, brokerPositionID: id, actual: &posCopy})
			continue
		}

		switch {
		case pos.VolumeLots < bp.volumeLots-positionVolumeEpsilon:
			drifts = append(drifts, masterDrift{driftType: masterDriftMissedPartialClose, brokerPositionID: id, actual: &posCopy, believed: bp})
		case pos.VolumeLots > bp.volumeLots+positionVolumeEpsilon:
			// Detected, never auto-corrected -- domain.TradeEventType has no
			// "increased" value.
			drifts = append(drifts, masterDrift{driftType: masterDriftUnsupportedIncrease, brokerPositionID: id, actual: &posCopy, believed: bp})
		}

		if pricePtrDiffers(pos.CurrentSLPrice, bp.slPrice) || pricePtrDiffers(pos.CurrentTPPrice, bp.tpPrice) {
			drifts = append(drifts, masterDrift{driftType: masterDriftMissedModify, brokerPositionID: id, actual: &posCopy, believed: bp})
		}
	}

	for id, bp := range believed {
		if _, ok := actualByID[id]; !ok {
			drifts = append(drifts, masterDrift{driftType: masterDriftMissedClose, brokerPositionID: id, believed: bp})
		}
	}

	return drifts
}

func pricePtrDiffers(a, b *float64) bool {
	if (a == nil) != (b == nil) {
		return true
	}
	if a == nil {
		return false
	}
	return math.Abs(*a-*b) > positionVolumeEpsilon
}

// synthesizeMasterEvent builds the full-clone-of-actual domain.NormalizedTradeEvent
// per drift type -- deliberately NEVER a sparse, diff-type-specific struct.
// insertTradeSignal writes volume_lots/sl_price/tp_price from event.Position
// into trade_signals regardless of event_type, and deriveBelievedMasterPositions'
// own DISTINCT ON ... ORDER BY server_timestamp DESC picks up whichever row
// is newest -- including MODIFIED/PARTIALLY_CLOSED rows. A sparse Position
// on a synthesized MODIFIED event (only SL/TP populated, volume left zero)
// would silently corrupt the VOLUME belief the very next tick, even though
// only SL/TP actually changed.
func synthesizeMasterEvent(masterAccountID string, drift masterDrift) domain.NormalizedTradeEvent {
	now := time.Now().UTC().Format(time.RFC3339)
	event := domain.NormalizedTradeEvent{
		EventID:               uuid.NewString(),
		MasterBrokerAccountID: masterAccountID,
		ReceivedAtGateway:     now,
	}

	switch drift.driftType {
	case masterDriftMissedOpen:
		event.EventType = domain.TradeEventPositionOpened
		event.ServerTimestamp = drift.actual.OpenedAt
		event.Position = *drift.actual

	case masterDriftMissedClose:
		event.EventType = domain.TradeEventPositionClosed
		// No historical close timestamp is available (the position simply
		// vanished from actualPositions) -- reconciliation can only know
		// it's closed as of THIS polling cycle, not the exact historical
		// close time. Honest limitation, not a bug.
		event.ServerTimestamp = now
		closedVolume := drift.believed.volumeLots
		event.ClosedVolumeLots = &closedVolume
		event.Position = domain.NormalizedPosition{
			BrokerPositionID: drift.brokerPositionID,
			// trade_signals has no asset_class column, so the believed
			// state can't recover the real one -- FX is a placeholder,
			// harmless because handleClose never reads Symbol at all.
			Symbol:     domain.NormalizedSymbol{CanonicalCode: drift.believed.canonicalSymbol, AssetClass: domain.AssetClassFX},
			Direction:  domain.TradeDirection(drift.believed.direction),
			VolumeLots: 0,
		}

	case masterDriftMissedPartialClose:
		event.EventType = domain.TradeEventPositionPartiallyClosed
		event.ServerTimestamp = now
		closedVolume := drift.believed.volumeLots - drift.actual.VolumeLots
		event.ClosedVolumeLots = &closedVolume
		event.Position = *drift.actual

	case masterDriftMissedModify:
		event.EventType = domain.TradeEventPositionModified
		event.ServerTimestamp = now
		event.Position = *drift.actual
	}

	return event
}

func masterDriftEventTypeAndDetail(drift masterDrift) (eventsv1.ReconciliationDriftType, string) {
	switch drift.driftType {
	case masterDriftMissedOpen:
		return eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_OPEN,
			fmt.Sprintf("actual position found (volume %.2f) with no ledger record -- synthesized POSITION_OPENED and replayed", drift.actual.VolumeLots)
	case masterDriftMissedClose:
		return eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_CLOSE,
			fmt.Sprintf("ledger believed open (volume %.2f) but no actual position found -- synthesized POSITION_CLOSED and replayed", drift.believed.volumeLots)
	case masterDriftMissedPartialClose:
		return eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_PARTIAL_CLOSE,
			fmt.Sprintf("actual volume %.2f vs ledger volume %.2f -- synthesized POSITION_PARTIALLY_CLOSED and replayed", drift.actual.VolumeLots, drift.believed.volumeLots)
	case masterDriftMissedModify:
		return eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_MODIFY,
			fmt.Sprintf("actual SL=%s TP=%s vs ledger SL=%s TP=%s -- synthesized POSITION_MODIFIED and replayed",
				formatPricePtr(drift.actual.CurrentSLPrice), formatPricePtr(drift.actual.CurrentTPPrice),
				formatPricePtr(drift.believed.slPrice), formatPricePtr(drift.believed.tpPrice))
	case masterDriftUnsupportedIncrease:
		return eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_UNSUPPORTED_VOLUME_INCREASE,
			fmt.Sprintf("actual volume %.2f vs ledger volume %.2f (growth not supported, no action taken)", drift.actual.VolumeLots, drift.believed.volumeLots)
	default:
		return eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_UNSPECIFIED, "unknown drift type"
	}
}

func formatPricePtr(p *float64) string {
	if p == nil {
		return "nil"
	}
	return fmt.Sprintf("%.5f", *p)
}

// reconcileMasterAccount is docs/08 §8.9's reconciliationTick, one master
// account's worth: diff, then for every drift EXCEPT unsupported growth,
// synthesize + replay through p.HandleEvent (literally the same entry
// point, confirmed no parallel path exists), then publish the drift event.
func (p *Pipeline) reconcileMasterAccount(ctx context.Context, masterAccountID uuid.UUID) error {
	masterBrokerType, err := p.loadBrokerType(ctx, masterAccountID)
	if err != nil {
		return fmt.Errorf("load master broker type: %w", err)
	}
	masterRemote, err := p.router.For(masterBrokerType)
	if err != nil {
		return fmt.Errorf("resolve master remote adapter: %w", err)
	}

	actual, err := masterRemote.GetOpenPositions(ctx, masterAccountID.String())
	if err != nil {
		return fmt.Errorf("get master open positions: %w", err)
	}
	believed, err := p.deriveBelievedMasterPositions(ctx, masterAccountID)
	if err != nil {
		return fmt.Errorf("derive believed master positions: %w", err)
	}

	for _, drift := range diffMasterPositions(actual, believed) {
		driftType, detail := masterDriftEventTypeAndDetail(drift)

		if drift.driftType == masterDriftUnsupportedIncrease {
			p.publishReconciliationDriftLogged(ctx, masterAccountID.String(), drift.brokerPositionID, driftType, detail)
			continue
		}

		event := synthesizeMasterEvent(masterAccountID.String(), drift)
		if err := p.HandleEvent(ctx, event); err != nil {
			return fmt.Errorf("replay synthesized event for position %s: %w", drift.brokerPositionID, err)
		}
		p.publishReconciliationDriftLogged(ctx, masterAccountID.String(), drift.brokerPositionID, driftType, detail)
	}
	return nil
}

// ==================== FOLLOWER-SIDE ====================

// believedFollowerPosition is one open (FILLED/PARTIALLY_CLOSED)
// copied_trades row for a given follower_broker_account_id.
type believedFollowerPosition struct {
	copiedTradeID            uuid.UUID
	followerBrokerPositionID string
	currentOpenVolumeLots    float64
}

// deriveBelievedFollowerPositions is docs/08 §8.9 point 2's follower-side
// half: "derived from copied_trades with status FILLED/PARTIALLY_CLOSED".
func (p *Pipeline) deriveBelievedFollowerPositions(ctx context.Context, followerAccountID uuid.UUID) (map[string]believedFollowerPosition, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT ct.id, ct.follower_broker_position_id, ct.current_open_volume_lots
		FROM copied_trades ct
		JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
		WHERE cr.follower_broker_account_id = $1 AND ct.status IN ('FILLED','PARTIALLY_CLOSED')`, followerAccountID)
	if err != nil {
		return nil, fmt.Errorf("query copied_trades for reconciliation: %w", err)
	}
	defer rows.Close()

	believed := make(map[string]believedFollowerPosition)
	for rows.Next() {
		var bp believedFollowerPosition
		var followerPositionID *string
		if err := rows.Scan(&bp.copiedTradeID, &followerPositionID, &bp.currentOpenVolumeLots); err != nil {
			return nil, fmt.Errorf("scan copied_trades row: %w", err)
		}
		if followerPositionID == nil {
			continue
		}
		bp.followerBrokerPositionID = *followerPositionID
		believed[bp.followerBrokerPositionID] = bp
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate copied_trades rows: %w", err)
	}
	return believed, nil
}

type followerDriftType int

const (
	followerDriftClosed followerDriftType = iota
	followerDriftPartialClose
	followerDriftUnsupportedIncrease
	// followerDriftUnmatchedPosition: an actual position with no believed
	// row at all -- a candidate for the stuck-PENDING match (TICKET-106's
	// crash-between-claim-and-finalize gap) or, failing that, a genuinely
	// unreconcilable position.
	followerDriftUnmatchedPosition
)

type followerDrift struct {
	driftType        followerDriftType
	brokerPositionID string
	actual           *domain.NormalizedPosition // nil for followerDriftClosed
	believed         believedFollowerPosition
}

// diffFollowerPositions is pure and DB-free, mirrors diffMasterPositions'
// own precedent. Deliberately never diffs SL/TP (a follower may have
// independently pinned SL/TP) and never auto-corrects volume growth
// (symmetric with the master side).
func diffFollowerPositions(actual []domain.NormalizedPosition, believed map[string]believedFollowerPosition) []followerDrift {
	var drifts []followerDrift
	actualByID := make(map[string]domain.NormalizedPosition, len(actual))
	for _, pos := range actual {
		actualByID[pos.BrokerPositionID] = pos
	}

	for id, pos := range actualByID {
		posCopy := pos
		bp, ok := believed[id]
		if !ok {
			drifts = append(drifts, followerDrift{driftType: followerDriftUnmatchedPosition, brokerPositionID: id, actual: &posCopy})
			continue
		}

		switch {
		case pos.VolumeLots < bp.currentOpenVolumeLots-positionVolumeEpsilon:
			drifts = append(drifts, followerDrift{driftType: followerDriftPartialClose, brokerPositionID: id, actual: &posCopy, believed: bp})
		case pos.VolumeLots > bp.currentOpenVolumeLots+positionVolumeEpsilon:
			drifts = append(drifts, followerDrift{driftType: followerDriftUnsupportedIncrease, brokerPositionID: id, actual: &posCopy, believed: bp})
		}
	}

	for id, bp := range believed {
		if _, ok := actualByID[id]; !ok {
			drifts = append(drifts, followerDrift{driftType: followerDriftClosed, brokerPositionID: id, believed: bp})
		}
	}

	return drifts
}

// pendingCandidate is a stale (older than the staleness gate) PENDING
// copied_trades row -- a candidate for TICKET-106's stuck-claim finalize.
type pendingCandidate struct {
	copiedTradeID      uuid.UUID
	computedVolumeLots float64
	symbol             string
	direction          string
}

// matchUnmatchedFollowerPositions pairs unmatched actual follower positions
// with stale PENDING copied_trades rows on symbol+direction+volume
// (epsilon-tolerant). Only BIDIRECTIONALLY-UNIQUE pairs are matched --
// ambiguous cases (a position matching 0 or 2+ candidates, or a candidate
// matching 2+ positions) are left unmatched. A bare "plausible" match isn't
// a safe bar for a live UPDATE that fabricates follower_broker_position_id/
// filled_price -- pure and DB-free, unit-testable directly.
func matchUnmatchedFollowerPositions(unmatchedActual []domain.NormalizedPosition, candidates []pendingCandidate) map[string]pendingCandidate {
	matchesForActual := make(map[string][]pendingCandidate)
	matchCountForCandidate := make(map[uuid.UUID]int)

	for _, pos := range unmatchedActual {
		for _, c := range candidates {
			if c.symbol == pos.Symbol.CanonicalCode && c.direction == string(pos.Direction) && math.Abs(c.computedVolumeLots-pos.VolumeLots) <= positionVolumeEpsilon {
				matchesForActual[pos.BrokerPositionID] = append(matchesForActual[pos.BrokerPositionID], c)
				matchCountForCandidate[c.copiedTradeID]++
			}
		}
	}

	result := make(map[string]pendingCandidate)
	for posID, matches := range matchesForActual {
		if len(matches) != 1 {
			continue
		}
		c := matches[0]
		if matchCountForCandidate[c.copiedTradeID] != 1 {
			continue
		}
		result[posID] = c
	}
	return result
}

// reconcileFollowerAccount is docs/08 §8.9's reconciliationTick, one
// follower account's worth -- direct ledger correction, never a synthetic
// event (see this file's own package doc comment for why).
func (p *Pipeline) reconcileFollowerAccount(ctx context.Context, followerAccountID uuid.UUID) error {
	followerBrokerType, err := p.loadBrokerType(ctx, followerAccountID)
	if err != nil {
		return fmt.Errorf("load follower broker type: %w", err)
	}
	followerRemote, err := p.router.For(followerBrokerType)
	if err != nil {
		return fmt.Errorf("resolve follower remote adapter: %w", err)
	}

	actual, err := followerRemote.GetOpenPositions(ctx, followerAccountID.String())
	if err != nil {
		return fmt.Errorf("get follower open positions: %w", err)
	}
	believed, err := p.deriveBelievedFollowerPositions(ctx, followerAccountID)
	if err != nil {
		return fmt.Errorf("derive believed follower positions: %w", err)
	}

	var unmatchedActual []domain.NormalizedPosition
	for _, drift := range diffFollowerPositions(actual, believed) {
		switch drift.driftType {
		case followerDriftClosed:
			if _, err := p.pool.Exec(ctx, `UPDATE copied_trades SET status='CLOSED', current_open_volume_lots=0, closed_at=now() WHERE id=$1`, drift.believed.copiedTradeID); err != nil {
				slog.Default().Error("pipeline: follower ledger correction (closed) failed", "followerBrokerAccountId", followerAccountID, "copiedTradeId", drift.believed.copiedTradeID, "error", err)
				continue
			}
			detail := fmt.Sprintf("ledger believed open (volume %.2f) but no actual position found -- corrected to CLOSED", drift.believed.currentOpenVolumeLots)
			p.publishReconciliationDriftLogged(ctx, followerAccountID.String(), drift.brokerPositionID, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_FOLLOWER_LEDGER_CORRECTED, detail)

		case followerDriftPartialClose:
			if _, err := p.pool.Exec(ctx, `UPDATE copied_trades SET current_open_volume_lots=$1 WHERE id=$2`, drift.actual.VolumeLots, drift.believed.copiedTradeID); err != nil {
				slog.Default().Error("pipeline: follower ledger correction (partial close) failed", "followerBrokerAccountId", followerAccountID, "copiedTradeId", drift.believed.copiedTradeID, "error", err)
				continue
			}
			detail := fmt.Sprintf("actual volume %.2f vs ledger volume %.2f -- corrected current_open_volume_lots", drift.actual.VolumeLots, drift.believed.currentOpenVolumeLots)
			p.publishReconciliationDriftLogged(ctx, followerAccountID.String(), drift.brokerPositionID, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_FOLLOWER_LEDGER_CORRECTED, detail)

		case followerDriftUnsupportedIncrease:
			detail := fmt.Sprintf("actual volume %.2f vs ledger volume %.2f (growth not supported, no action taken)", drift.actual.VolumeLots, drift.believed.currentOpenVolumeLots)
			p.publishReconciliationDriftLogged(ctx, followerAccountID.String(), drift.brokerPositionID, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_UNSUPPORTED_VOLUME_INCREASE, detail)

		case followerDriftUnmatchedPosition:
			unmatchedActual = append(unmatchedActual, *drift.actual)
		}
	}

	if len(unmatchedActual) > 0 {
		if err := p.reconcileUnmatchedFollowerPositions(ctx, followerAccountID, unmatchedActual); err != nil {
			return fmt.Errorf("reconcile unmatched follower positions: %w", err)
		}
	}

	if err := p.timeoutStalePendingFollowerRows(ctx, followerAccountID); err != nil {
		return fmt.Errorf("timeout stale pending follower rows: %w", err)
	}

	return nil
}

// reconcileUnmatchedFollowerPositions is TICKET-106's own explicitly-flagged
// crash-between-claim-and-finalize gap, closed for real: a PENDING
// copied_trades row whose PlaceOrder actually succeeded broker-side, but
// the process crashed before the finalize UPDATE landed.
func (p *Pipeline) reconcileUnmatchedFollowerPositions(ctx context.Context, followerAccountID uuid.UUID, unmatchedActual []domain.NormalizedPosition) error {
	candidates, err := p.loadStalePendingCandidates(ctx, followerAccountID)
	if err != nil {
		return fmt.Errorf("load stale pending candidates: %w", err)
	}

	matches := matchUnmatchedFollowerPositions(unmatchedActual, candidates)

	for _, pos := range unmatchedActual {
		candidate, matched := matches[pos.BrokerPositionID]
		if !matched {
			detail := fmt.Sprintf("actual position (volume %.2f) matches no believed row and no unique stale PENDING claim -- manual investigation required", pos.VolumeLots)
			p.publishReconciliationDriftLogged(ctx, followerAccountID.String(), pos.BrokerPositionID, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_UNRECONCILABLE_FOLLOWER_POSITION, detail)
			continue
		}

		if _, err := p.pool.Exec(ctx, `
			UPDATE copied_trades SET status='FILLED', follower_broker_position_id=$1, filled_price=$2, current_open_volume_lots=$3
			WHERE id=$4`,
			pos.BrokerPositionID, pos.OpenPrice, pos.VolumeLots, candidate.copiedTradeID,
		); err != nil {
			slog.Default().Error("pipeline: finalize stuck PENDING row failed", "followerBrokerAccountId", followerAccountID, "copiedTradeId", candidate.copiedTradeID, "error", err)
			continue
		}
		detail := fmt.Sprintf("actual position (volume %.2f) uniquely matched a stale PENDING claim -- finalized as FILLED (TICKET-106's claim/finalize crash gap)", pos.VolumeLots)
		p.publishReconciliationDriftLogged(ctx, followerAccountID.String(), pos.BrokerPositionID, eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_FOLLOWER_LEDGER_CORRECTED, detail)
	}
	return nil
}

// loadStalePendingCandidates: symbol/direction come from sizing_method_snapshot
// JSON (buildSizingMethodSnapshot, dispatch.go) -- masterPosition.symbol.canonicalCode
// and the top-level appliedDirection key -- computed_volume_lots is already
// a plain column. The staleness gate (2 minutes, well past any realistic
// in-flight PlaceOrder round trip at this job's 30s cadence) avoids racing
// a genuinely in-flight (not crashed) dispatch.
func (p *Pipeline) loadStalePendingCandidates(ctx context.Context, followerAccountID uuid.UUID) ([]pendingCandidate, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT ct.id, ct.computed_volume_lots,
		       ct.sizing_method_snapshot->'masterPosition'->'symbol'->>'canonicalCode',
		       ct.sizing_method_snapshot->>'appliedDirection'
		FROM copied_trades ct
		JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
		WHERE cr.follower_broker_account_id = $1 AND ct.status = 'PENDING' AND ct.created_at < now() - interval '2 minutes'`,
		followerAccountID)
	if err != nil {
		return nil, fmt.Errorf("query pending copied_trades: %w", err)
	}
	defer rows.Close()

	var candidates []pendingCandidate
	for rows.Next() {
		var c pendingCandidate
		if err := rows.Scan(&c.copiedTradeID, &c.computedVolumeLots, &c.symbol, &c.direction); err != nil {
			return nil, fmt.Errorf("scan pending copied_trades row: %w", err)
		}
		candidates = append(candidates, c)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate pending copied_trades rows: %w", err)
	}
	return candidates, nil
}

// timeoutStalePendingFollowerRows closes the OTHER half of TICKET-106's
// crash window: reconcileUnmatchedFollowerPositions only fires when an
// actual position exists to match against (PlaceOrder succeeded
// broker-side). The mirror case -- PlaceOrder genuinely failed/rejected
// broker-side AND the process also crashed before writing the FAILED
// finalize -- leaves a PENDING row with nothing to ever match. A longer
// timeout (10 minutes, well past the 2-minute match-candidate gate) marks
// it FAILED directly.
func (p *Pipeline) timeoutStalePendingFollowerRows(ctx context.Context, followerAccountID uuid.UUID) error {
	rows, err := p.pool.Query(ctx, `
		SELECT ct.id FROM copied_trades ct
		JOIN copy_relationships cr ON cr.id = ct.copy_relationship_id
		WHERE cr.follower_broker_account_id = $1 AND ct.status = 'PENDING' AND ct.created_at < now() - interval '10 minutes'`,
		followerAccountID)
	if err != nil {
		return fmt.Errorf("query stale pending copied_trades: %w", err)
	}
	var staleIDs []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return fmt.Errorf("scan stale pending copied_trades row: %w", err)
		}
		staleIDs = append(staleIDs, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return fmt.Errorf("iterate stale pending copied_trades rows: %w", err)
	}

	for _, id := range staleIDs {
		if _, err := p.pool.Exec(ctx, `UPDATE copied_trades SET status='FAILED', reject_reason='RECONCILIATION_TIMEOUT' WHERE id=$1 AND status='PENDING'`, id); err != nil {
			slog.Default().Error("pipeline: timeout stale pending row failed", "followerBrokerAccountId", followerAccountID, "copiedTradeId", id, "error", err)
			continue
		}
		detail := "PENDING row exceeded the reconciliation timeout with no matching actual position -- marked FAILED (TICKET-106's claim/finalize crash gap, PlaceOrder-never-succeeded case)"
		p.publishReconciliationDriftLogged(ctx, followerAccountID.String(), "", eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_FOLLOWER_PENDING_TIMEOUT, detail)
	}
	return nil
}

// ==================== SWEEP + LOOP ====================

// loadActiveMasterAccountIDs/loadActiveFollowerAccountIDs: only accounts
// actually involved in an active relationship have anything to reconcile
// against -- mirrors drawdown.go's own sweep philosophy (not appendix-A's
// looser "every broker_accounts row" wording), avoiding pointless
// GetOpenPositions calls for accounts with nothing configured.
func (p *Pipeline) loadActiveMasterAccountIDs(ctx context.Context) ([]uuid.UUID, error) {
	rows, err := p.pool.Query(ctx, `SELECT DISTINCT master_broker_account_id FROM copy_relationships WHERE status = 'ACTIVE'`)
	if err != nil {
		return nil, fmt.Errorf("query active master accounts: %w", err)
	}
	defer rows.Close()

	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("scan master account id: %w", err)
		}
		ids = append(ids, id)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate master account ids: %w", err)
	}
	return ids, nil
}

func (p *Pipeline) loadActiveFollowerAccountIDs(ctx context.Context) ([]uuid.UUID, error) {
	rows, err := p.pool.Query(ctx, `SELECT DISTINCT follower_broker_account_id FROM copy_relationships WHERE status = 'ACTIVE'`)
	if err != nil {
		return nil, fmt.Errorf("query active follower accounts: %w", err)
	}
	defer rows.Close()

	var ids []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("scan follower account id: %w", err)
		}
		ids = append(ids, id)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate follower account ids: %w", err)
	}
	return ids, nil
}

// RunReconciliation polls every interval until ctx is cancelled -- mirrors
// drawdown.go's RunDrawdownMonitor's own immediate-tick-then-ticker shape.
func (p *Pipeline) RunReconciliation(ctx context.Context, interval time.Duration) {
	p.runReconciliationCheckLogged(ctx)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.runReconciliationCheckLogged(ctx)
		}
	}
}

func (p *Pipeline) runReconciliationCheckLogged(ctx context.Context) {
	if err := p.CheckReconciliationOnce(ctx); err != nil {
		slog.Default().Error("pipeline: reconciliation tick failed", "error", err)
	}
}

// CheckReconciliationOnce is exported for the same reason drawdown.go's
// CheckDrawdownOnce is -- integration tests live in package main and can
// only call exported Pipeline methods, calling this directly (not waiting
// on the real ticker) for deterministic, synchronous checks. A
// per-account failure is logged and the sweep continues to the next one,
// mirroring CheckDrawdownOnce's/reconcile.reconcileOnce's own established
// one-bad-account-never-blocks-others precedent.
func (p *Pipeline) CheckReconciliationOnce(ctx context.Context) error {
	masterIDs, err := p.loadActiveMasterAccountIDs(ctx)
	if err != nil {
		return fmt.Errorf("load active master accounts for reconciliation: %w", err)
	}
	for _, id := range masterIDs {
		if err := p.reconcileMasterAccount(ctx, id); err != nil {
			slog.Default().Error("pipeline: master reconciliation failed", "masterBrokerAccountId", id, "error", err)
		}
	}

	followerIDs, err := p.loadActiveFollowerAccountIDs(ctx)
	if err != nil {
		return fmt.Errorf("load active follower accounts for reconciliation: %w", err)
	}
	for _, id := range followerIDs {
		if err := p.reconcileFollowerAccount(ctx, id); err != nil {
			slog.Default().Error("pipeline: follower reconciliation failed", "followerBrokerAccountId", id, "error", err)
		}
	}

	relationshipCount, err := p.countActiveRelationships(ctx)
	if err != nil {
		return fmt.Errorf("count active relationships for reconciliation: %w", err)
	}

	// Feature — the Engine Control page's own "stale vs connected" signal, same
	// convention as apps/broker-adapters' reconcile.Loop: set unconditionally at the
	// end of every cycle that completed successfully, whether or not any drift was
	// actually found.
	p.selfStatusMu.Lock()
	p.lastReconcileAt = time.Now()
	p.activeRelationshipCount = relationshipCount
	p.selfStatusMu.Unlock()

	return nil
}

func (p *Pipeline) countActiveRelationships(ctx context.Context) (int, error) {
	var count int
	if err := p.pool.QueryRow(ctx, `SELECT COUNT(*) FROM copy_relationships WHERE status = 'ACTIVE'`).Scan(&count); err != nil {
		return 0, fmt.Errorf("query active relationship count: %w", err)
	}
	return count, nil
}

// Status is the Engine Control page's own self-reported snapshot — served
// via internal/httpapi's GET /internal/self/status route.
type Status struct {
	ActiveRelationshipCount int
	LastReconcileAt         time.Time
}

func (p *Pipeline) Status() Status {
	p.selfStatusMu.Lock()
	defer p.selfStatusMu.Unlock()
	return Status{ActiveRelationshipCount: p.activeRelationshipCount, LastReconcileAt: p.lastReconcileAt}
}

// ==================== PUBLISH ====================

// publishReconciliationDriftLogged is the convenience wrapper almost every
// call site in this file uses -- a failed Kafka publish is logged, never
// treated as a reason to abort processing other drifts in the same tick
// (matches writeAccountSnapshotBestEffort's own established best-effort
// precedent).
func (p *Pipeline) publishReconciliationDriftLogged(ctx context.Context, brokerAccountID, brokerPositionID string, driftType eventsv1.ReconciliationDriftType, detail string) {
	if err := p.publishReconciliationDrift(ctx, brokerAccountID, brokerPositionID, driftType, detail); err != nil {
		slog.Default().Error("pipeline: publish reconciliation drift failed", "brokerAccountId", brokerAccountID, "brokerPositionId", brokerPositionID, "driftType", driftType, "error", err)
	}
}

func (p *Pipeline) publishReconciliationDrift(ctx context.Context, brokerAccountID, brokerPositionID string, driftType eventsv1.ReconciliationDriftType, detail string) error {
	_, span := observability.Tracer().Start(ctx, "pipeline.publish")
	err := p.doPublishReconciliationDrift(ctx, brokerAccountID, brokerPositionID, driftType, detail)
	finishSpan(span, err)
	return err
}

func (p *Pipeline) doPublishReconciliationDrift(ctx context.Context, brokerAccountID, brokerPositionID string, driftType eventsv1.ReconciliationDriftType, detail string) error {
	msg := &eventsv1.ReconciliationDriftDetected{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().UTC().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		BrokerAccountId:  brokerAccountID,
		BrokerPositionId: brokerPositionID,
		DriftType:        driftType,
		Detail:           detail,
	}
	value, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal ReconciliationDriftDetected: %w", err)
	}
	return p.reconciliationEventWriter.WriteMessages(ctx, kafka.Message{Key: []byte(brokerAccountID), Value: value})
}
