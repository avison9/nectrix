package moneymgmt

import (
	"github.com/shopspring/decimal"

	domain "github.com/avison9/nectrix/go-domain"
)

// NormalizeLot is docs/09-money-management-risk-formulas.md §9.3, verbatim
// for the DOWN/NEAREST/UP step-rounding, but implements the doc's PROSE for
// the below-min case (skip and flag, reject_reason='BELOW_MIN_LOT'), not its
// pseudocode's literal generic clamp(normalized, min_lot, max_lot) call --
// read literally that clamp would bump a too-small value UP to min_lot,
// directly contradicting the doc's own very next line and this ticket's AC2
// ("clamping below min_lot (skip+flag, not bump)"). The AC text is
// unambiguous and authoritative; the pseudocode line appears to be a
// documentation bug worth fixing upstream.
//
// An empty/zero RoundingMode defaults to DOWN, matching the DB column's own
// DEFAULT 'DOWN' -- callers reading a real money_management_profiles row
// never need their own zero-value handling.
func NormalizeLot(rawLots float64, spec domain.SymbolSpec, mode RoundingMode) (normalized float64, rejected bool, rejectReason string) {
	if mode == "" {
		mode = RoundingDown
	}

	lotStep := decimal.NewFromFloat(spec.LotStep)
	if lotStep.IsZero() {
		// A real SymbolSpec always has a positive lot_step; this is a
		// corrupt-input defensive case, kept distinct from
		// RejectBelowMinLot so it is never confused with a legitimate
		// business-rule rejection.
		return 0, true, RejectInvalidLotStep
	}

	stepCount := decimal.NewFromFloat(rawLots).Div(lotStep)

	var rounded decimal.Decimal
	switch mode {
	case RoundingUp:
		rounded = stepCount.Ceil()
	case RoundingNearest:
		rounded = stepCount.Round(0)
	default: // RoundingDown
		rounded = stepCount.Floor()
	}

	normalizedDec := rounded.Mul(lotStep)

	minLot := decimal.NewFromFloat(spec.MinLot)
	if normalizedDec.LessThan(minLot) {
		return 0, true, RejectBelowMinLot
	}

	maxLot := decimal.NewFromFloat(spec.MaxLot)
	if normalizedDec.GreaterThan(maxLot) {
		normalizedDec = maxLot
	}

	result, _ := normalizedDec.Float64()
	return result, false, ""
}
