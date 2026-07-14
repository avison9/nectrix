package pipeline

import (
	"context"
	"encoding/json"
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
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/protobuf/proto"
)

// relationship is TICKET-106's widened row shape -- money_management_profile_id/
// risk_profile_id/copy_direction are all real, already-migrated
// copy_relationships columns nothing selected until now.
type relationship struct {
	id                       uuid.UUID
	masterBrokerAccountID    uuid.UUID
	followerBrokerAccountID  uuid.UUID
	moneyManagementProfileID uuid.UUID
	riskProfileID            uuid.UUID
	copyDirection            string // "SAME" | "REVERSE"
	// createdAt (TICKET-108) is only populated by loadActiveRelationshipsForDrawdownCheck
	// (drawdown.go) -- matchRelationships' own SELECT/Scan below is
	// untouched, leaving this zero-valued for the event-driven dispatch path,
	// which never reads it.
	createdAt time.Time
}

// processSignalForAllRelationships is the Relationship Matcher (Appendix
// A.2) -- "stub" only in that it skips the Redis-cache-with-invalidation
// refinement docs/08 §8.2 point 3 describes; this direct query is real,
// not a hardcoded fake, and is honest about the ACTIVE-only fan-out.
//
// Known limitation, flagged not fixed (TICKET-109): the loop below aborts
// on the FIRST relationship's error. If a master has 3 followers and
// dispatch errors transiently for follower #2 (e.g. a network blip calling
// PlaceOrder/ClosePosition), follower #3 is never attempted for this
// event -- live or reconciliation-replayed alike. Once this event's
// trade_signals row is committed (already done, before this function is
// even called -- see dedupStage), master-side reconciliation can never
// re-detect it as missing (the master's own belief is already correct),
// and follower-side reconciliation won't catch it either (no actual
// position exists for relationship #3 to diff against, no believed row
// either -- both sides agree on "nothing"). This is a real blind spot
// neither side of TICKET-109's reconciliation design touches, since it's a
// partial-fan-out failure, not a broker-vs-ledger diff. A future ticket's
// job (continue-on-error + aggregate), not this one's.
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
		SELECT id, master_broker_account_id, follower_broker_account_id,
		       money_management_profile_id, risk_profile_id, copy_direction
		FROM copy_relationships
		WHERE master_broker_account_id = $1 AND status = 'ACTIVE'`, masterAccountID)
	if err != nil {
		return nil, fmt.Errorf("query copy_relationships: %w", err)
	}
	defer rows.Close()

	var relationships []relationship
	for rows.Next() {
		var r relationship
		if err := rows.Scan(&r.id, &r.masterBrokerAccountID, &r.followerBrokerAccountID, &r.moneyManagementProfileID, &r.riskProfileID, &r.copyDirection); err != nil {
			return nil, fmt.Errorf("scan copy_relationships row: %w", err)
		}
		relationships = append(relationships, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate copy_relationships rows: %w", err)
	}
	return relationships, nil
}

// dispatchOrder is the Order Dispatcher's entry point -- TICKET-107 fixed a
// real, confirmed bug here: this used to call doDispatchOrder (Appendix
// A.3's handleOpen) for EVERY event regardless of event.EventType, since
// nothing in this package ever branched on it (grep confirmed EventType was
// only read for tracing/dedup-key-building/DB storage). That meant a
// PARTIALLY_CLOSED/CLOSED/MODIFIED event incorrectly re-ran the full
// open-dispatch flow, including a second live PlaceOrder call. This switch
// is the fix -- each event type now reaches the handler
// appendix-a-copy-engine-pseudocode.md actually specifies for it (§A.3
// handleOpen, §A.5 handlePartialClose, §A.6 handleClose, and handleModify
// per docs/08-copy-trading-engine.md §8.7, which appendix-A itself doesn't
// give separate pseudocode for).
func (p *Pipeline) dispatchOrder(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	ctx, span := observability.Tracer().Start(ctx, "pipeline.dispatch_order", trace.WithAttributes(
		attribute.String("nectrix.copy_relationship_id", rel.id.String()),
		attribute.String("nectrix.event_type", string(event.EventType)),
	))
	var err error
	defer func() { finishSpan(span, err) }()

	switch event.EventType {
	case domain.TradeEventPositionOpened:
		err = p.doDispatchOrder(ctx, rel, signalID, event)
	case domain.TradeEventPositionPartiallyClosed:
		err = p.handlePartialClose(ctx, rel, signalID, event)
	case domain.TradeEventPositionClosed:
		err = p.handleClose(ctx, rel, signalID, event)
	case domain.TradeEventPositionModified:
		err = p.handleModify(ctx, rel, signalID, event)
	default:
		// Includes the empty string tradesignals.FromProto maps an
		// unrecognized proto enum value to -- never fatal for the whole
		// signal, just nothing this relationship can act on.
		slog.Default().Warn("pipeline: unrecognized event type, skipping dispatch",
			"copyRelationshipId", rel.id, "eventType", event.EventType)
	}
	return err
}

// doDispatchOrder is appendix-a-copy-engine-pseudocode.md §A.3's handleOpen,
// in full: symbol mapping (both sides) -> account snapshots (both sides,
// live remote calls) -> money-management sizing -> risk guard -> SL/TP
// pip-distance translation -> a durable claim -> PlaceOrder -> finalize.
//
// TICKET-106: the claim (INSERT ... status='PENDING' ... ON CONFLICT DO
// NOTHING RETURNING id) happens BEFORE PlaceOrder is ever called -- not
// after, as the earlier STUB_1_TO_1_COPY version did. That earlier ordering
// relied entirely on dedupadapter's Redis-backed dedup (TTL-limited) to
// prevent a duplicate real broker-side order; a signal reprocessed after
// that TTL expires would have placed a genuine second position, with the
// Postgres unique constraint only ever catching the second bookkeeping row
// afterward. Claiming first makes the Postgres constraint itself the
// durable, non-expiring guard: if no row comes back, this signal was
// already claimed/processed and PlaceOrder is never called at all. A crash
// between claim and finalize leaves a row stuck at PENDING forever --
// TICKET-109's Reconciliation Job (not yet built) is what eventually
// catches and corrects that, not this function's job.
func (p *Pipeline) doDispatchOrder(ctx context.Context, rel relationship, signalID uuid.UUID, event domain.NormalizedTradeEvent) error {
	idempotencyKey := signalID.String()

	// docs/08 §8.4 / TICKET-103: an unmapped symbol on EITHER side is
	// skipped and flagged, never guessed. The master's own pip_size/
	// contract_size are direct inputs to §9.6's SL/TP translation and to
	// RISK_PERCENT sizing -- an unconfirmed auto-suggestion is exactly the
	// "confidently wrong guess" TICKET-103's confirmation gate exists to
	// prevent, on whichever side reads it.
	masterSpec, masterMapped, err := p.loadConfirmedSymbolSpec(ctx, rel.masterBrokerAccountID, event.Position.Symbol)
	if err != nil {
		return fmt.Errorf("load master symbol spec: %w", err)
	}
	if !masterMapped {
		return p.recordUnmappedSymbolFailure(ctx, rel, signalID, idempotencyKey, event)
	}
	followerSpec, followerMapped, err := p.loadConfirmedSymbolSpec(ctx, rel.followerBrokerAccountID, event.Position.Symbol)
	if err != nil {
		return fmt.Errorf("load follower symbol spec: %w", err)
	}
	if !followerMapped {
		return p.recordUnmappedSymbolFailure(ctx, rel, signalID, idempotencyKey, event)
	}

	masterBrokerType, err := p.loadBrokerType(ctx, rel.masterBrokerAccountID)
	if err != nil {
		return fmt.Errorf("load master broker type: %w", err)
	}
	followerBrokerType, err := p.loadBrokerType(ctx, rel.followerBrokerAccountID)
	if err != nil {
		return fmt.Errorf("load follower broker type: %w", err)
	}
	masterRemote, err := p.router.For(masterBrokerType)
	if err != nil {
		return fmt.Errorf("resolve master remote adapter: %w", err)
	}
	followerRemote, err := p.router.For(followerBrokerType)
	if err != nil {
		return fmt.Errorf("resolve follower remote adapter: %w", err)
	}

	masterAccount, err := masterRemote.GetAccountSnapshot(ctx, rel.masterBrokerAccountID.String())
	if err != nil {
		return fmt.Errorf("get master account snapshot: %w", err)
	}
	followerAccount, err := followerRemote.GetAccountSnapshot(ctx, rel.followerBrokerAccountID.String())
	if err != nil {
		return fmt.Errorf("get follower account snapshot: %w", err)
	}
	p.writeAccountSnapshotBestEffort(ctx, rel.masterBrokerAccountID, masterAccount)
	p.writeAccountSnapshotBestEffort(ctx, rel.followerBrokerAccountID, followerAccount)

	mmProfile, err := p.loadMoneyManagementProfile(ctx, rel.moneyManagementProfileID)
	if err != nil {
		return fmt.Errorf("load money management profile: %w", err)
	}
	riskProfile, err := p.loadRiskProfile(ctx, rel.riskProfileID)
	if err != nil {
		return fmt.Errorf("load risk profile: %w", err)
	}

	fxCapture := &capturingFXRateProvider{inner: p.fx}
	sizingResult, err := moneymgmt.ComputeLotSize(ctx, moneymgmt.Input{
		Profile:         mmProfile,
		MasterPosition:  event.Position,
		MasterAccount:   masterAccount,
		FollowerAccount: followerAccount,
		SymbolSpec:      followerSpec,
	}, fxCapture)
	if err != nil {
		return fmt.Errorf("compute lot size: %w", err)
	}
	if sizingResult.Rejected {
		return p.recordSizingFailure(ctx, rel, signalID, idempotencyKey, sizingResult.RejectReason, event)
	}

	symbolExposure, err := p.SumOpenVolumeForSymbol(ctx, rel.id, event.Position.Symbol.CanonicalCode)
	if err != nil {
		return fmt.Errorf("sum open volume for symbol: %w", err)
	}
	totalExposure, err := p.SumOpenVolumeAllSymbols(ctx, rel.id)
	if err != nil {
		return fmt.Errorf("sum open volume all symbols: %w", err)
	}
	openCount, err := p.CountOpenPositions(ctx, rel.id)
	if err != nil {
		return fmt.Errorf("count open positions: %w", err)
	}
	guardResult := moneymgmt.ApplyRiskGuard(sizingResult.NormalizedLots, riskProfile.RiskProfile, followerSpec, moneymgmt.Exposure{
		CurrentSymbolExposureLots: symbolExposure,
		CurrentTotalExposureLots:  totalExposure,
		OpenPositionCount:         openCount,
	})
	if guardResult.Rejected {
		return p.recordSizingFailure(ctx, rel, signalID, idempotencyKey, guardResult.RejectReason, event)
	}

	followerDirection := applyCopyDirection(event.Position.Direction, rel.copyDirection)
	// The follower's real fill price isn't known yet at this point (PlaceOrder
	// hasn't been called) -- event.Position.OpenPrice (the master's own open
	// price) is passed as the followerOpenPrice approximation, per this
	// function's own doc comment. TICKET-107's handleModify passes the real,
	// already-known follower fill price instead (stored in copied_trades.filled_price).
	slPrice := translateSlTp(event.Position.CurrentSLPrice, event.Position.OpenPrice, event.Position.OpenPrice, event.Position.Direction, followerDirection, masterSpec.PipSize, followerSpec.PipSize, "SL")
	tpPrice := translateSlTp(event.Position.CurrentTPPrice, event.Position.OpenPrice, event.Position.OpenPrice, event.Position.Direction, followerDirection, masterSpec.PipSize, followerSpec.PipSize, "TP")

	snapshot, err := buildSizingMethodSnapshot(mmProfile, masterAccount, followerAccount, event.Position, followerSpec,
		fxCapture.calls, sizingResult, guardResult, rel.copyDirection, followerDirection, masterSpec.PipSize, followerSpec.PipSize, slPrice, tpPrice)
	if err != nil {
		return fmt.Errorf("marshal sizing_method_snapshot: %w", err)
	}

	// CLAIM -- durable, non-expiring guard, checked BEFORE any broker call.
	var copiedTradeID uuid.UUID
	err = p.pool.QueryRow(ctx, `
		INSERT INTO copied_trades (
			copy_relationship_id, trade_signal_id, idempotency_key, status,
			computed_volume_lots, sizing_method_snapshot, requested_price
		) VALUES ($1,$2,$3,'PENDING',$4,$5,$6)
		ON CONFLICT (copy_relationship_id, idempotency_key) DO NOTHING
		RETURNING id`,
		rel.id, signalID, idempotencyKey, guardResult.Volume, snapshot, event.Position.OpenPrice,
	).Scan(&copiedTradeID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// Already claimed/processed for this signal+relationship --
			// PlaceOrder must never be called.
			return nil
		}
		return fmt.Errorf("claim copied_trades row: %w", err)
	}

	// PLACE -- only ever reached once, for this signal+relationship, ever.
	orderRequest := domain.NormalizedOrderRequest{
		IdempotencyKey:          idempotencyKey,
		FollowerBrokerAccountID: rel.followerBrokerAccountID.String(),
		Symbol:                  event.Position.Symbol,
		Direction:               followerDirection,
		VolumeLots:              guardResult.Volume,
		SLPrice:                 slPrice,
		TPPrice:                 tpPrice,
		MaxSlippagePips:         riskProfile.MaxSlippagePips,
		ClientOrderTag:          rel.id.String() + ":" + event.Position.BrokerPositionID,
	}
	result, placeErr := followerRemote.PlaceOrder(ctx, rel.followerBrokerAccountID.String(), orderRequest)

	// FINALIZE.
	status := "FILLED"
	if placeErr != nil || !result.Success {
		status = "REJECTED"
	}
	var slippagePips *float64
	if placeErr == nil && result.Success && result.FilledPrice != nil && followerSpec.PipSize != 0 {
		sp := math.Abs(*result.FilledPrice-event.Position.OpenPrice) / followerSpec.PipSize
		slippagePips = &sp
	}
	// current_open_volume_lots (TICKET-107) only matters once status=FILLED --
	// a REJECTED row leaves it at the column's own DEFAULT 0, same as
	// recordSizingFailure's FAILED path, since neither ever opens a real
	// follower position.
	currentOpenVolumeLots := 0.0
	if status == "FILLED" {
		currentOpenVolumeLots = guardResult.Volume
	}
	_, updateErr := p.pool.Exec(ctx, `
		UPDATE copied_trades
		SET status=$1, follower_broker_position_id=$2, filled_price=$3, slippage_pips=$4, reject_reason=$5, opened_at=$6, current_open_volume_lots=$7
		WHERE id=$8`,
		status, nullIfEmpty(result.BrokerPositionID), result.FilledPrice, slippagePips, nullIfEmpty(result.RejectReason), time.Now().UTC(), currentOpenVolumeLots, copiedTradeID,
	)
	if placeErr != nil {
		return fmt.Errorf("PlaceOrder: %w", placeErr)
	}
	if updateErr != nil {
		return fmt.Errorf("finalize copied_trades row: %w", updateErr)
	}

	if result.Success {
		return p.publishCopiedTradeOpened(ctx, rel.id, result, event)
	}
	return p.publishCopiedTradeFailed(ctx, rel.id, result.RejectReason, event)
}

// loadConfirmedSymbolSpec widens the earlier hasConfirmedSymbolMapping into
// a full SymbolSpec read, used identically for BOTH master and follower
// accounts. symbol is passed in (not rebuilt from the row) since
// symbol_mappings has no asset_class column -- rebuilding it from the DB row
// would silently lose AssetClass; threading the event's own already-correct
// NormalizedSymbol through avoids that.
func (p *Pipeline) loadConfirmedSymbolSpec(ctx context.Context, brokerAccountID uuid.UUID, symbol domain.NormalizedSymbol) (domain.SymbolSpec, bool, error) {
	spec := domain.SymbolSpec{Symbol: symbol}
	err := p.pool.QueryRow(ctx, `
		SELECT broker_symbol_name, contract_size, lot_step, min_lot, max_lot, pip_size, digits, margin_currency
		FROM symbol_mappings
		WHERE broker_account_id = $1 AND canonical_symbol = $2 AND is_confirmed = TRUE`,
		brokerAccountID, symbol.CanonicalCode,
	).Scan(&spec.BrokerSymbolName, &spec.ContractSize, &spec.LotStep, &spec.MinLot, &spec.MaxLot, &spec.PipSize, &spec.Digits, &spec.MarginCurrency)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.SymbolSpec{}, false, nil
	}
	if err != nil {
		return domain.SymbolSpec{}, false, fmt.Errorf("query symbol_mappings: %w", err)
	}
	return spec, true, nil
}

// loadBrokerType resolves which real service (broker-adapters vs
// mt5-bridge-gateway) a broker_accounts row lives on, so the Router knows
// which RemoteAdapter to use.
func (p *Pipeline) loadBrokerType(ctx context.Context, brokerAccountID uuid.UUID) (domain.BrokerType, error) {
	var brokerType string
	err := p.pool.QueryRow(ctx, `SELECT broker_type FROM broker_accounts WHERE id = $1`, brokerAccountID).Scan(&brokerType)
	if err != nil {
		return "", fmt.Errorf("query broker_accounts: %w", err)
	}
	return domain.BrokerType(brokerType), nil
}

// loadMoneyManagementProfile reads a money_management_profiles row directly
// -- Copy Engine and Core App (Java) both read the same Postgres tables
// independently, mirroring the established multi-language shared-database
// pattern already used for symbol_mappings/copied_trades.
func (p *Pipeline) loadMoneyManagementProfile(ctx context.Context, id uuid.UUID) (moneymgmt.Profile, error) {
	var profile moneymgmt.Profile
	var method, roundingMode string
	var fixedLotSize, multiplier, riskPercent *float64
	var customFormulaExpr *string
	err := p.pool.QueryRow(ctx, `
		SELECT method, fixed_lot_size, multiplier, risk_percent, custom_formula_expr, rounding_mode
		FROM money_management_profiles WHERE id = $1`, id,
	).Scan(&method, &fixedLotSize, &multiplier, &riskPercent, &customFormulaExpr, &roundingMode)
	if err != nil {
		return moneymgmt.Profile{}, fmt.Errorf("query money_management_profiles: %w", err)
	}
	profile.Method = moneymgmt.Method(method)
	profile.FixedLotSize = fixedLotSize
	profile.Multiplier = multiplier
	profile.RiskPercent = riskPercent
	profile.CustomFormulaExpr = customFormulaExpr
	profile.RoundingMode = moneymgmt.RoundingMode(roundingMode)
	return profile, nil
}

// dispatchRiskProfile bundles moneymgmt.RiskProfile (the caps ApplyRiskGuard
// reads) with max_slippage_pips (which ApplyRiskGuard itself has no use for
// but the order request does), PinFollowerSLTP -- TICKET-107 / FR-3.7: when
// true, handleModify logs/publishes a master SL/TP change but never calls
// ModifyPosition against the follower's own position -- and
// DrawdownPausePct/DrawdownCloseAllPct -- TICKET-108 / docs/09 §9.7's
// two-tier drawdown model, both independently nullable (either, both, or
// neither may be configured for a given relationship).
type dispatchRiskProfile struct {
	moneymgmt.RiskProfile
	MaxSlippagePips     float64
	PinFollowerSLTP     bool
	DrawdownPausePct    *float64
	DrawdownCloseAllPct *float64
}

func (p *Pipeline) loadRiskProfile(ctx context.Context, id uuid.UUID) (dispatchRiskProfile, error) {
	var profile dispatchRiskProfile
	err := p.pool.QueryRow(ctx, `
		SELECT max_lot_per_trade, max_open_positions, max_exposure_per_symbol_lots, max_total_exposure_lots, max_slippage_pips, pin_follower_sl_tp, drawdown_pause_pct, drawdown_close_all_pct
		FROM risk_profiles WHERE id = $1`, id,
	).Scan(&profile.MaxLotPerTrade, &profile.MaxOpenPositions, &profile.MaxExposurePerSymbolLots, &profile.MaxTotalExposureLots, &profile.MaxSlippagePips, &profile.PinFollowerSLTP, &profile.DrawdownPausePct, &profile.DrawdownCloseAllPct)
	if err != nil {
		return dispatchRiskProfile{}, fmt.Errorf("query risk_profiles: %w", err)
	}
	return profile, nil
}

// applyCopyDirection is appendix-a's applyCopyDirection, using the real,
// already-migrated copy_relationships.copy_direction column no code had
// read until now.
func applyCopyDirection(masterDirection domain.TradeDirection, copyDirection string) domain.TradeDirection {
	if copyDirection != "REVERSE" {
		return masterDirection
	}
	if masterDirection == domain.TradeDirectionBuy {
		return domain.TradeDirectionSell
	}
	return domain.TradeDirectionBuy
}

func signOf(direction domain.TradeDirection) float64 {
	if direction == domain.TradeDirectionSell {
		return -1
	}
	return 1
}

// translateSlTp is docs/09-money-management-risk-formulas.md §9.6's SL/TP
// pip-distance translation. One real design decision beyond the literal doc
// text:
//
//   - REVERSE relationships: uses the master's own direction to extract a
//     directionless distance magnitude, but the follower's own
//     (post-applyCopyDirection) direction to reconstruct the follower's
//     SL/TP -- required so a REVERSE follower's stop lands on the correct
//     side of its own (opposite) position. The doc doesn't address this;
//     this is a considered extension, not a silent assumption.
//
// followerOpenPrice is an explicit caller-supplied parameter (TICKET-107),
// not an internal approximation -- at OPEN time (dispatch.go's
// doDispatchOrder) the follower's real fill price isn't known yet (PlaceOrder
// hasn't been called), so that call site passes the master's own OpenPrice
// as an approximation, consistent with Market mode's own philosophy (submit
// immediately, record actual divergence as slippage after the fact). At
// MODIFY time (handleModify) the follower's real fill price IS already known
// (copied_trades.filled_price, set by doDispatchOrder's own finalize step),
// so that call site passes the real value instead of an approximation.
func translateSlTp(masterPrice *float64, masterOpenPrice, followerOpenPrice float64, masterDirection, followerDirection domain.TradeDirection, masterPipSize, followerPipSize float64, side string) *float64 {
	if masterPrice == nil || masterPipSize == 0 || followerPipSize == 0 {
		return nil
	}
	masterSign := signOf(masterDirection)
	followerSign := signOf(followerDirection)

	var masterDistancePrice float64
	if side == "SL" {
		masterDistancePrice = masterSign * (masterOpenPrice - *masterPrice)
	} else {
		masterDistancePrice = masterSign * (*masterPrice - masterOpenPrice)
	}
	masterDistancePips := masterDistancePrice / masterPipSize

	var followerPrice float64
	if side == "SL" {
		followerPrice = followerOpenPrice - followerSign*(masterDistancePips*followerPipSize)
	} else {
		followerPrice = followerOpenPrice + followerSign*(masterDistancePips*followerPipSize)
	}
	return &followerPrice
}

// capturingFXRateProvider records every (from, to, rate) lookup
// moneymgmt.ComputeLotSize actually makes, purely so sizing_method_snapshot
// can include the real FX rate(s) used (AC2's "reproduce by hand" needs
// this) -- a dispatch.go-local decorator, does not touch moneymgmt's own
// already-committed public API.
type fxRateCall struct {
	From, To string
	Rate     float64
}

type capturingFXRateProvider struct {
	inner moneymgmt.FXRateProvider
	calls []fxRateCall
}

func (c *capturingFXRateProvider) Rate(ctx context.Context, from, to string) (float64, error) {
	rate, err := c.inner.Rate(ctx, from, to)
	if err == nil {
		c.calls = append(c.calls, fxRateCall{From: from, To: to, Rate: rate})
	}
	return rate, err
}

// buildSizingMethodSnapshot is TICKET-106 AC2's exact requirement: enough
// detail to reproduce the computed volume by hand from the stored JSON
// alone.
func buildSizingMethodSnapshot(
	profile moneymgmt.Profile,
	masterAccount, followerAccount domain.AccountSnapshot,
	masterPosition domain.NormalizedPosition,
	followerSymbolSpec domain.SymbolSpec,
	fxRatesUsed []fxRateCall,
	sizingResult moneymgmt.Result,
	guardResult moneymgmt.RiskGuardResult,
	copyDirection string,
	appliedDirection domain.TradeDirection,
	masterPipSize, followerPipSize float64,
	slPrice, tpPrice *float64,
) ([]byte, error) {
	snapshot := map[string]any{
		"method":                  profile.Method,
		"profile":                 profile,
		"masterAccountSnapshot":   masterAccount,
		"followerAccountSnapshot": followerAccount,
		"masterPosition":          masterPosition,
		"followerSymbolSpec":      followerSymbolSpec,
		"fxRatesUsed":             fxRatesUsed,
		"rawLots":                 sizingResult.RawLots,
		"normalizedLots":          sizingResult.NormalizedLots,
		"riskGuard": map[string]any{
			"finalVolume":  guardResult.Volume,
			"rejected":     guardResult.Rejected,
			"rejectReason": guardResult.RejectReason,
		},
		"copyDirection":    copyDirection,
		"appliedDirection": appliedDirection,
		"slTpTranslation": map[string]any{
			"masterPipSize":   masterPipSize,
			"followerPipSize": followerPipSize,
			"slPrice":         slPrice,
			"tpPrice":         tpPrice,
		},
	}
	return json.Marshal(snapshot)
}

// writeAccountSnapshotBestEffort gives account_snapshots (migrated since
// TICKET-004, never written to by any code until now) a real writer -- free
// reuse of data already fetched for sizing. Best-effort, logged on failure,
// never blocks or reverses the dispatch outcome, mirroring
// reconcile.Loop.reportStatus's own established precedent.
func (p *Pipeline) writeAccountSnapshotBestEffort(ctx context.Context, brokerAccountID uuid.UUID, snapshot domain.AccountSnapshot) {
	_, err := p.pool.Exec(ctx, `
		INSERT INTO account_snapshots (broker_account_id, balance, equity, used_margin, free_margin, margin_level_pct, captured_at)
		VALUES ($1,$2,$3,$4,$5,$6,now())`,
		brokerAccountID, snapshot.Balance, snapshot.Equity, snapshot.UsedMargin, snapshot.FreeMargin, snapshot.MarginLevelPct,
	)
	if err != nil {
		slog.Default().Error("pipeline: best-effort account_snapshots write failed", "brokerAccountId", brokerAccountID, "error", err)
	}
}

// recordUnmappedSymbolFailure is appendix-a-copy-engine-pseudocode.md's
// handleOpen: "return recordCopiedTrade(relationship, event, status=FAILED,
// reason='UNMAPPED_SYMBOL')" -- never calls PlaceOrder. Same idempotency-key
// convention as the real dispatch path (ON CONFLICT DO NOTHING), so a
// redelivered signal doesn't record (or publish) the same failure twice.
func (p *Pipeline) recordUnmappedSymbolFailure(ctx context.Context, rel relationship, signalID uuid.UUID, idempotencyKey string, event domain.NormalizedTradeEvent) error {
	return p.recordSizingFailure(ctx, rel, signalID, idempotencyKey, "UNMAPPED_SYMBOL", event)
}

// recordSizingFailure is the shared "flag and skip" path for every
// pre-PlaceOrder rejection (unmapped symbol, ComputeLotSize rejection,
// ApplyRiskGuard rejection) -- never calls PlaceOrder.
func (p *Pipeline) recordSizingFailure(ctx context.Context, rel relationship, signalID uuid.UUID, idempotencyKey string, rejectReason string, event domain.NormalizedTradeEvent) error {
	sizingSnapshot, err := json.Marshal(map[string]any{"method": rejectReason})
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
		return fmt.Errorf("insert copied_trades (%s): %w", rejectReason, err)
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
