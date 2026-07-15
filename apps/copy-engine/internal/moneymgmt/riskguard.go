package moneymgmt

import (
	"github.com/shopspring/decimal"

	domain "github.com/avison9/nectrix/go-domain"
)

// RiskProfile mirrors risk_profiles' risk-guard-relevant columns
// (006-copy-trading.sql) -- nullable columns as pointers, same convention
// as Profile. drawdown_pause_pct/drawdown_close_all_pct are deliberately
// NOT here: TICKET-108's own account-level mechanism, never read by
// ApplyRiskGuard's per-trade caps.
type RiskProfile struct {
	MaxLotPerTrade           *float64
	MaxOpenPositions         *int
	MaxExposurePerSymbolLots *float64
	MaxTotalExposureLots     *float64
}

// Exposure bundles the three already-queried exposure numbers ApplyRiskGuard
// needs. Computing them is the CALLER's job (TICKET-106, via
// pipeline.SumOpenVolumeForSymbol/SumOpenVolumeAllSymbols/CountOpenPositions)
// -- this package stays DB-free, per its own existing package doc.
type Exposure struct {
	CurrentSymbolExposureLots float64
	CurrentTotalExposureLots  float64
	OpenPositionCount         int
}

const (
	RejectMaxOpenPositionsReached = "MAX_OPEN_POSITIONS_REACHED"
	RejectCappedToZero            = "CAPPED_TO_ZERO"
)

// RiskGuardResult is this stage's own result shape -- not moneymgmt.Result,
// which carries sizing-stage-specific fields (RawLots) with no meaning
// here. A business rejection is Rejected:true + RejectReason, exactly like
// Result, never a Go error.
type RiskGuardResult struct {
	Volume       float64
	Rejected     bool
	RejectReason string
}

// ApplyRiskGuard is docs/09 §9.4 / appendix-a §A.7's applyRiskGuard, with
// the pseudocode's caller-side "if volume <= 0: reject(CAPPED_TO_ZERO)"
// folded in as this function's own last step.
//
// volume must already be ComputeLotSize's NormalizedLots (NormalizeLot has
// already run) -- this function only ever reduces it further or rejects
// it, never re-normalizes or increases it.
//
// CONTRACT: must ONLY ever be invoked for an opening or increasing signal
// (appendix-a's handleOpen path). A close/partial-close signal
// (handleClose/handlePartialClose, TICKET-107, not yet implemented) MUST
// bypass this function entirely -- closing reduces exposure by moving a
// copied_trades row to CLOSED/PARTIALLY_CLOSED status (see
// pipeline.SumOpenVolume*/CountOpenPositions, which then exclude/shrink for
// it), not by being run through cap-shrinking logic. Nothing in this
// function's signature enforces that call-site discipline -- it is
// TICKET-107's responsibility. See apps/copy-engine/exposure_integration_test.go
// for the regression test proving the exclusion mechanism this depends on.
func ApplyRiskGuard(volume float64, profile RiskProfile, symbolSpec domain.SymbolSpec, exposure Exposure) RiskGuardResult {
	vol := decimal.NewFromFloat(volume)

	if profile.MaxLotPerTrade != nil {
		// NormalizeLot(profile.maxLotPerTrade, symbolSpec, "DOWN") per §A.7.
		// If maxLotPerTrade itself normalizes to 0 (a misconfigured profile
		// set below min_lot), NormalizeLot's own rejected/reason is
		// intentionally ignored here -- the resulting numeric 0 correctly
		// cascades into this function's own CAPPED_TO_ZERO below, which is
		// the right outcome for a profile that can never allow a trade
		// through.
		capped, _, _ := NormalizeLot(*profile.MaxLotPerTrade, symbolSpec, RoundingDown)
		vol = decimal.Min(vol, decimal.NewFromFloat(capped))
	}

	if profile.MaxExposurePerSymbolLots != nil {
		remaining := decimal.NewFromFloat(*profile.MaxExposurePerSymbolLots).
			Sub(decimal.NewFromFloat(exposure.CurrentSymbolExposureLots))
		vol = decimal.Min(vol, decimal.Max(decimal.Zero, remaining))
	}

	if profile.MaxTotalExposureLots != nil {
		remaining := decimal.NewFromFloat(*profile.MaxTotalExposureLots).
			Sub(decimal.NewFromFloat(exposure.CurrentTotalExposureLots))
		vol = decimal.Min(vol, decimal.Max(decimal.Zero, remaining))
	}

	if profile.MaxOpenPositions != nil && exposure.OpenPositionCount >= *profile.MaxOpenPositions {
		return RiskGuardResult{Rejected: true, RejectReason: RejectMaxOpenPositionsReached}
	}

	volFloat, _ := vol.Float64()
	if vol.LessThanOrEqual(decimal.Zero) {
		return RiskGuardResult{Rejected: true, RejectReason: RejectCappedToZero}
	}
	return RiskGuardResult{Volume: volFloat}
}
