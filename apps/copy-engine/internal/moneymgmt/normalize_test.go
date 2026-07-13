package moneymgmt

import (
	"math"
	"testing"

	domain "github.com/avison9/nectrix/go-domain"
)

// TICKET-104 AC2: "normalize_lot unit tests cover: rounding down to zero
// (skip+flag), clamping to max_lot, clamping below min_lot (skip+flag, not
// bump)."
func TestNormalizeLot_RoundsDownToZero_SkipAndFlag(t *testing.T) {
	spec := domain.SymbolSpec{LotStep: 0.01, MinLot: 0.01, MaxLot: 50}
	// 0.004 / 0.01 = 0.4 steps -> floor to 0 steps -> normalized 0, below min_lot.
	normalized, rejected, reason := NormalizeLot(0.004, spec, RoundingDown)
	if !rejected {
		t.Fatalf("expected rejection for a raw size that rounds to zero, got normalized=%v", normalized)
	}
	if reason != RejectBelowMinLot {
		t.Fatalf("reason = %q, want %q", reason, RejectBelowMinLot)
	}
}

func TestNormalizeLot_AboveMax_ClampedDown(t *testing.T) {
	spec := domain.SymbolSpec{LotStep: 0.01, MinLot: 0.01, MaxLot: 5}
	normalized, rejected, reason := NormalizeLot(12.345, spec, RoundingDown)
	if rejected {
		t.Fatalf("did not expect rejection, got reason=%q", reason)
	}
	if normalized != 5 {
		t.Fatalf("normalized = %v, want clamped to max_lot 5", normalized)
	}
}

// The AC's explicit "not bump" requirement: a normalized value that lands
// strictly between 0 and min_lot must be rejected, and MUST NOT be silently
// raised to min_lot.
func TestNormalizeLot_BelowMinLot_SkipAndFlag_NotBumped(t *testing.T) {
	spec := domain.SymbolSpec{LotStep: 0.01, MinLot: 0.05, MaxLot: 50}
	// 0.023 / 0.01 = 2.3 steps -> floor to 2 steps -> normalized 0.02, which
	// is > 0 but still < min_lot (0.05).
	normalized, rejected, reason := NormalizeLot(0.023, spec, RoundingDown)
	if !rejected {
		t.Fatalf("expected rejection for a size below min_lot, got normalized=%v", normalized)
	}
	if reason != RejectBelowMinLot {
		t.Fatalf("reason = %q, want %q", reason, RejectBelowMinLot)
	}
	if normalized == spec.MinLot {
		t.Fatalf("normalized lot must not be silently bumped to min_lot (%v) — got exactly that", spec.MinLot)
	}
}

func TestNormalizeLot_RoundingModes(t *testing.T) {
	spec := domain.SymbolSpec{LotStep: 0.1, MinLot: 0.01, MaxLot: 50}
	tests := []struct {
		name string
		mode RoundingMode
		want float64
	}{
		{"down", RoundingDown, 1.2},
		{"up", RoundingUp, 1.3},
		{"nearest_rounds_down", RoundingNearest, 1.2}, // 1.24 -> 12.4 steps -> nearest 12 -> 1.2
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, rejected, reason := NormalizeLot(1.24, spec, tt.mode)
			if rejected {
				t.Fatalf("did not expect rejection, got reason=%q", reason)
			}
			if math.Abs(got-tt.want) > 1e-9 {
				t.Fatalf("normalized = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestNormalizeLot_EmptyRoundingMode_DefaultsToDown(t *testing.T) {
	spec := domain.SymbolSpec{LotStep: 0.1, MinLot: 0.01, MaxLot: 50}
	got, rejected, _ := NormalizeLot(1.29, spec, "")
	if rejected {
		t.Fatal("did not expect rejection")
	}
	if math.Abs(got-1.2) > 1e-9 {
		t.Fatalf("normalized = %v, want 1.2 (DOWN default)", got)
	}
}
