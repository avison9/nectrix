package moneymgmt

import (
	"testing"
)

// TICKET-105 AC1: "Unit tests for each cap individually and in combination
// (e.g., a trade that would exceed both max_lot_per_trade and
// max_exposure_per_symbol_lots is capped to the more restrictive of the
// two)."

func TestApplyRiskGuard_NoCapsConfigured_PassesThrough(t *testing.T) {
	result := ApplyRiskGuard(2.5, RiskProfile{}, noOpNormalizeSpec, Exposure{})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 2.5) {
		t.Fatalf("Volume = %v, want 2.5 (unchanged, no caps configured)", result.Volume)
	}
}

func TestApplyRiskGuard_MaxLotPerTrade_Caps(t *testing.T) {
	maxLot := 1.0
	profile := RiskProfile{MaxLotPerTrade: &maxLot}
	result := ApplyRiskGuard(2.5, profile, noOpNormalizeSpec, Exposure{})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 1.0) {
		t.Fatalf("Volume = %v, want 1.0 (capped to max_lot_per_trade)", result.Volume)
	}
}

func TestApplyRiskGuard_MaxLotPerTrade_DoesNotIncreaseBelowLimit(t *testing.T) {
	maxLot := 5.0
	profile := RiskProfile{MaxLotPerTrade: &maxLot}
	result := ApplyRiskGuard(1.0, profile, noOpNormalizeSpec, Exposure{})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 1.0) {
		t.Fatalf("Volume = %v, want 1.0 (already below cap, must not be raised)", result.Volume)
	}
}

func TestApplyRiskGuard_MaxExposurePerSymbol_Caps(t *testing.T) {
	maxExposure := 3.0
	profile := RiskProfile{MaxExposurePerSymbolLots: &maxExposure}
	// current symbol exposure is 2.0, so only 1.0 lots of budget remain.
	result := ApplyRiskGuard(2.5, profile, noOpNormalizeSpec, Exposure{CurrentSymbolExposureLots: 2.0})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 1.0) {
		t.Fatalf("Volume = %v, want 1.0 (3.0 - 2.0 remaining budget)", result.Volume)
	}
}

func TestApplyRiskGuard_MaxTotalExposure_Caps(t *testing.T) {
	maxTotal := 4.0
	profile := RiskProfile{MaxTotalExposureLots: &maxTotal}
	result := ApplyRiskGuard(2.0, profile, noOpNormalizeSpec, Exposure{CurrentTotalExposureLots: 3.0})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 1.0) {
		t.Fatalf("Volume = %v, want 1.0 (4.0 - 3.0 remaining budget)", result.Volume)
	}
}

func TestApplyRiskGuard_MaxOpenPositions_RejectsAtLimit(t *testing.T) {
	maxOpen := 5
	profile := RiskProfile{MaxOpenPositions: &maxOpen}
	result := ApplyRiskGuard(1.0, profile, noOpNormalizeSpec, Exposure{OpenPositionCount: 5})
	if !result.Rejected {
		t.Fatalf("expected rejection at open-position limit, got Volume=%v", result.Volume)
	}
	if result.RejectReason != RejectMaxOpenPositionsReached {
		t.Fatalf("RejectReason = %q, want %q", result.RejectReason, RejectMaxOpenPositionsReached)
	}
}

func TestApplyRiskGuard_MaxOpenPositions_PassesBelowLimit(t *testing.T) {
	maxOpen := 5
	profile := RiskProfile{MaxOpenPositions: &maxOpen}
	result := ApplyRiskGuard(1.0, profile, noOpNormalizeSpec, Exposure{OpenPositionCount: 4})
	if result.Rejected {
		t.Fatalf("unexpected rejection below open-position limit: %s", result.RejectReason)
	}
}

// The ticket's own explicit example: a trade that would exceed both
// max_lot_per_trade and max_exposure_per_symbol_lots is capped to the more
// restrictive of the two.
func TestApplyRiskGuard_CombinedCaps_MostRestrictiveWins(t *testing.T) {
	maxLot := 3.0                                // less restrictive
	maxExposure := 4.0                            // combined with current exposure, more restrictive
	profile := RiskProfile{MaxLotPerTrade: &maxLot, MaxExposurePerSymbolLots: &maxExposure}
	// remaining symbol budget = 4.0 - 3.5 = 0.5, more restrictive than the 3.0 max-lot cap.
	result := ApplyRiskGuard(5.0, profile, noOpNormalizeSpec, Exposure{CurrentSymbolExposureLots: 3.5})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 0.5) {
		t.Fatalf("Volume = %v, want 0.5 (the more restrictive of the two caps)", result.Volume)
	}
}

func TestApplyRiskGuard_AllFourCapsCombined(t *testing.T) {
	maxLot := 10.0
	maxExposure := 8.0
	maxTotal := 2.0 // most restrictive of all four
	maxOpen := 10
	profile := RiskProfile{
		MaxLotPerTrade:           &maxLot,
		MaxExposurePerSymbolLots: &maxExposure,
		MaxTotalExposureLots:     &maxTotal,
		MaxOpenPositions:         &maxOpen,
	}
	result := ApplyRiskGuard(5.0, profile, noOpNormalizeSpec, Exposure{
		CurrentSymbolExposureLots: 1.0,
		CurrentTotalExposureLots:  1.5, // remaining total budget = 2.0 - 1.5 = 0.5, most restrictive
		OpenPositionCount:         3,
	})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 0.5) {
		t.Fatalf("Volume = %v, want 0.5 (max_total_exposure_lots is the most restrictive cap)", result.Volume)
	}
}

func TestApplyRiskGuard_NeverIncreasesVolume(t *testing.T) {
	maxLot := 100.0
	maxExposure := 100.0
	maxTotal := 100.0
	profile := RiskProfile{MaxLotPerTrade: &maxLot, MaxExposurePerSymbolLots: &maxExposure, MaxTotalExposureLots: &maxTotal}
	result := ApplyRiskGuard(0.3, profile, noOpNormalizeSpec, Exposure{})
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.Volume, 0.3) {
		t.Fatalf("Volume = %v, want 0.3 unchanged -- caps must never increase volume", result.Volume)
	}
}

// TICKET-105 AC2: "A trade capped to exactly zero results in status=FAILED,
// reject_reason='CAPPED_TO_ZERO'."
func TestApplyRiskGuard_CappedToExactlyZero_Rejected(t *testing.T) {
	maxExposure := 2.0
	profile := RiskProfile{MaxExposurePerSymbolLots: &maxExposure}
	// current exposure already exactly at the cap -- remaining budget is exactly 0.
	result := ApplyRiskGuard(1.0, profile, noOpNormalizeSpec, Exposure{CurrentSymbolExposureLots: 2.0})
	if !result.Rejected {
		t.Fatalf("expected rejection for exactly-zero remaining budget, got Volume=%v", result.Volume)
	}
	if result.RejectReason != RejectCappedToZero {
		t.Fatalf("RejectReason = %q, want %q", result.RejectReason, RejectCappedToZero)
	}
}

func TestApplyRiskGuard_CappedBelowZero_RejectedCappedToZero(t *testing.T) {
	maxExposure := 2.0
	profile := RiskProfile{MaxExposurePerSymbolLots: &maxExposure}
	// current exposure already OVER the cap (e.g. a stale/misconfigured
	// profile change) -- remaining budget is clamped to 0, not negative.
	result := ApplyRiskGuard(1.0, profile, noOpNormalizeSpec, Exposure{CurrentSymbolExposureLots: 3.0})
	if !result.Rejected || result.RejectReason != RejectCappedToZero {
		t.Fatalf("expected Rejected=true RejectReason=%q, got Rejected=%v RejectReason=%q",
			RejectCappedToZero, result.Rejected, result.RejectReason)
	}
}

func TestApplyRiskGuard_MaxLotPerTrade_BelowMinLot_CascadesToCappedToZero(t *testing.T) {
	// A misconfigured profile: max_lot_per_trade set below the symbol's own
	// min_lot. NormalizeLot's own rejection is ignored (see ApplyRiskGuard's
	// doc comment) -- the resulting numeric 0 correctly cascades into
	// CAPPED_TO_ZERO, never silently allowing a trade through a nonsensical
	// cap.
	maxLot := 0.001 // below noOpNormalizeSpec's MinLot of 0.0000001... use a coarser spec instead
	spec := noOpNormalizeSpec
	spec.MinLot = 0.01
	profile := RiskProfile{MaxLotPerTrade: &maxLot}
	result := ApplyRiskGuard(1.0, profile, spec, Exposure{})
	if !result.Rejected || result.RejectReason != RejectCappedToZero {
		t.Fatalf("expected Rejected=true RejectReason=%q, got Rejected=%v RejectReason=%q",
			RejectCappedToZero, result.Rejected, result.RejectReason)
	}
}
