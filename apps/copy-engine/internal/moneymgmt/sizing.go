// Package moneymgmt implements TICKET-104's lot-sizing engine --
// docs/09-money-management-risk-formulas.md §9.2/§9.3's six sizing methods
// plus normalization, exactly. This package does no DB/broker I/O itself
// (all inputs are passed in already-resolved); it lives under
// apps/copy-engine/internal because copy-engine is its only consumer (same
// reasoning internal/stubadapter's own doc comment gives for the same
// choice), not packages/go-domain.
//
// Not yet wired into the live dispatch pipeline -- that is TICKET-106's job
// ("connecting TICKET-103/104/105 stages to the real adapters"). This
// package is designed so that wiring is a small, mechanical change: build an
// Input from already-fetched AccountSnapshots/SymbolSpec, call
// ComputeLotSize, use Result in place of dispatch.go's current
// STUB_1_TO_1_COPY stub.
package moneymgmt

import (
	"context"
	"fmt"

	"github.com/shopspring/decimal"

	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt/formula"
	domain "github.com/avison9/nectrix/go-domain"
)

// Method mirrors money_management_profiles.method's CHECK constraint values
// exactly (apps/core-app/db/.../006-copy-trading.sql).
type Method string

const (
	MethodFixedLot            Method = "FIXED_LOT"
	MethodProportionalEquity  Method = "PROPORTIONAL_EQUITY"
	MethodProportionalBalance Method = "PROPORTIONAL_BALANCE"
	MethodRiskPercent         Method = "RISK_PERCENT"
	MethodMultiplier          Method = "MULTIPLIER"
	MethodCustomFormula       Method = "CUSTOM_FORMULA"
)

// RoundingMode mirrors money_management_profiles.rounding_mode's CHECK
// constraint values exactly. RoundingDown is the DB column's own DEFAULT.
type RoundingMode string

const (
	RoundingDown    RoundingMode = "DOWN"
	RoundingNearest RoundingMode = "NEAREST"
	RoundingUp      RoundingMode = "UP"
)

// Reject reasons -- docs/09 §9.2.4/§9.3's own vocabulary, plus one
// (RejectInvalidLotStep) for a corrupt-input case the docs don't name.
const (
	RejectRiskPercentRequiresSL         = "RISK_PERCENT_REQUIRES_SL"
	RejectBelowMinLot                   = "BELOW_MIN_LOT"
	RejectCustomFormulaEvaluationFailed = "CUSTOM_FORMULA_EVALUATION_FAILED"
	RejectInvalidLotStep                = "INVALID_LOT_STEP"
)

// Profile is the Go read model of one money_management_profiles row --
// nullable columns as pointers, same convention as
// domain.AccountSnapshot.MarginLevelPct.
type Profile struct {
	Method            Method
	FixedLotSize      *float64
	Multiplier        *float64
	RiskPercent       *float64
	CustomFormulaExpr *string
	RoundingMode      RoundingMode

	// AllowAssumedSLDistanceFallback/AssumedSLDistancePips implement docs/09
	// §9.2.4's configured-fallback branch for a master position with no SL.
	// Not backed by any money_management_profiles column today (no
	// migration in TICKET-104) -- always false/nil in every real row, but
	// this branch is fully implemented and tested so it is ready the day a
	// column exists, per the doc's "must be a configured, not implicit,
	// choice" requirement.
	AllowAssumedSLDistanceFallback bool
	AssumedSLDistancePips          *float64
}

// Input bundles everything docs/09 §9.1's formulas read. Resolution of
// which account snapshot / which SymbolSpec to pass happens in the caller
// (TICKET-106) -- this package trusts its inputs are already correct.
type Input struct {
	Profile Profile
	// MasterPosition is the master's NormalizedPosition at signal time --
	// VolumeLots, OpenPrice, CurrentSLPrice, and Symbol are all read.
	MasterPosition  domain.NormalizedPosition
	MasterAccount   domain.AccountSnapshot
	FollowerAccount domain.AccountSnapshot
	// SymbolSpec is the FOLLOWER's broker/symbol spec (contract size, pip
	// size, lot step/min/max) -- sizing is always computed against what the
	// follower's own broker will actually accept.
	SymbolSpec domain.SymbolSpec
}

// Result mirrors domain.NormalizedOrderResult{Success, RejectReason}'s
// existing sibling pattern deliberately -- a business-rule rejection is
// Result{Rejected:true, RejectReason:"..."} with a nil error from
// ComputeLotSize, never an error itself. See ComputeLotSize's doc comment.
type Result struct {
	RawLots        float64
	NormalizedLots float64
	Rejected       bool
	RejectReason   string
}

// computeFn is the uniform per-method signature every one of the six
// sizing methods implements, even ones that don't need ctx/fx (Go permits
// unused named parameters) -- keeps ComputeLotSize's dispatch a plain
// switch with no per-case signature gymnastics.
type computeFn func(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error)

// ComputeLotSize is docs/appendix-a-copy-engine-pseudocode.md §A.4's
// computeLotSize, plus the normalize_lot step from §9.3 folded in as the
// final stage (matching appendix-a's handleOpen calling normalizeLot
// immediately after computeLotSize with no other step between them).
//
// A non-nil error is reserved for genuine infrastructure/config failures:
// FX provider failure with no cached fallback, an unrecognized Method, or a
// required nullable Profile field being nil for its method (a corrupt row).
// It is NEVER used for an expected business-rule rejection -- those are
// always a nil error with Result{Rejected:true, RejectReason:"..."}.
func ComputeLotSize(ctx context.Context, in Input, fx FXRateProvider) (Result, error) {
	fn, ok := methodTable[in.Profile.Method]
	if !ok {
		return Result{}, fmt.Errorf("moneymgmt: unrecognized Method %q", in.Profile.Method)
	}

	rawLots, rejected, reason, err := fn(ctx, in, fx)
	if err != nil {
		return Result{}, err
	}
	rawLotsFloat, _ := rawLots.Float64()
	if rejected {
		return Result{RawLots: rawLotsFloat, Rejected: true, RejectReason: reason}, nil
	}

	normalized, normRejected, normReason := NormalizeLot(rawLotsFloat, in.SymbolSpec, in.Profile.RoundingMode)
	if normRejected {
		return Result{RawLots: rawLotsFloat, Rejected: true, RejectReason: normReason}, nil
	}
	return Result{RawLots: rawLotsFloat, NormalizedLots: normalized}, nil
}

var methodTable = map[Method]computeFn{
	MethodFixedLot:            computeFixedLot,
	MethodProportionalEquity:  computeProportionalEquity,
	MethodProportionalBalance: computeProportionalBalance,
	MethodRiskPercent:         computeRiskPercent,
	MethodMultiplier:          computeMultiplier,
	MethodCustomFormula:       computeCustomFormula,
}

// 9.2.1 Fixed Lot -- follower_volume_lots = profile.fixed_lot_size
func computeFixedLot(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error) {
	if in.Profile.FixedLotSize == nil {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: FIXED_LOT profile missing fixed_lot_size")
	}
	return decimal.NewFromFloat(*in.Profile.FixedLotSize), false, "", nil
}

// 9.2.2 Proportional to Equity -- equity_ratio = follower_equity / master_equity
// (converted to master's currency first when they differ), then
// follower_volume_lots = master_open_volume_lots * equity_ratio.
func computeProportionalEquity(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error) {
	masterEquity := decimal.NewFromFloat(in.MasterAccount.Equity)
	if masterEquity.IsZero() {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: master account equity is zero")
	}

	followerEquity := decimal.NewFromFloat(in.FollowerAccount.Equity)
	if in.FollowerAccount.Currency != in.MasterAccount.Currency {
		rate, err := fx.Rate(ctx, in.FollowerAccount.Currency, in.MasterAccount.Currency)
		if err != nil {
			return decimal.Zero, false, "", fmt.Errorf("moneymgmt: fx rate lookup: %w", err)
		}
		followerEquity = followerEquity.Mul(decimal.NewFromFloat(rate))
	}

	equityRatio := followerEquity.Div(masterEquity)
	return decimal.NewFromFloat(in.MasterPosition.VolumeLots).Mul(equityRatio), false, "", nil
}

// 9.2.3 Proportional to Balance -- literal per the doc, no fx conversion
// documented even cross-currency (see this ticket's plan "Open risks" #2:
// very likely a doc omission given §9.2.2's symmetry, but AC1 requires
// reproducing the exact formulas, so this is intentionally NOT "fixed" by
// silently adding a conversion the source-of-truth doc doesn't specify).
func computeProportionalBalance(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error) {
	masterBalance := decimal.NewFromFloat(in.MasterAccount.Balance)
	if masterBalance.IsZero() {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: master account balance is zero")
	}
	balanceRatio := decimal.NewFromFloat(in.FollowerAccount.Balance).Div(masterBalance)
	return decimal.NewFromFloat(in.MasterPosition.VolumeLots).Mul(balanceRatio), false, "", nil
}

// 9.2.4 Risk-Percent -- sizes so a stop-loss hit costs exactly risk_percent
// of follower equity. No-SL edge case: reject-and-flag is the safe default;
// the assumed-SL-distance fallback only runs if explicitly configured (see
// Profile's doc comment -- always off today, no DB column exists yet).
func computeRiskPercent(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error) {
	if in.Profile.RiskPercent == nil {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: RISK_PERCENT profile missing risk_percent")
	}

	var slDistancePips decimal.Decimal
	if in.MasterPosition.CurrentSLPrice != nil {
		pipSize := decimal.NewFromFloat(in.SymbolSpec.PipSize)
		if pipSize.IsZero() {
			return decimal.Zero, false, "", fmt.Errorf("moneymgmt: symbol spec pip_size is zero")
		}
		slDistancePrice := decimal.NewFromFloat(in.MasterPosition.OpenPrice).
			Sub(decimal.NewFromFloat(*in.MasterPosition.CurrentSLPrice)).Abs()
		slDistancePips = slDistancePrice.Div(pipSize)
	} else if in.Profile.AllowAssumedSLDistanceFallback && in.Profile.AssumedSLDistancePips != nil {
		slDistancePips = decimal.NewFromFloat(*in.Profile.AssumedSLDistancePips)
	} else {
		return decimal.Zero, true, RejectRiskPercentRequiresSL, nil
	}
	if slDistancePips.IsZero() {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: sl distance is zero, cannot size by risk percent")
	}

	riskAmount := decimal.NewFromFloat(in.FollowerAccount.Equity).
		Mul(decimal.NewFromFloat(*in.Profile.RiskPercent).Div(decimal.NewFromInt(100)))

	quoteCcy := quoteCurrencyOf(in.MasterPosition.Symbol, in.SymbolSpec)
	rate := decimal.NewFromInt(1)
	if quoteCcy != in.FollowerAccount.Currency {
		r, err := fx.Rate(ctx, quoteCcy, in.FollowerAccount.Currency)
		if err != nil {
			return decimal.Zero, false, "", fmt.Errorf("moneymgmt: fx rate lookup: %w", err)
		}
		rate = decimal.NewFromFloat(r)
	}

	pipValuePerLot := decimal.NewFromFloat(in.SymbolSpec.ContractSize).
		Mul(decimal.NewFromFloat(in.SymbolSpec.PipSize)).Mul(rate)
	if pipValuePerLot.IsZero() {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: pip value per lot computed as zero")
	}

	return riskAmount.Div(slDistancePips.Mul(pipValuePerLot)), false, "", nil
}

// 9.2.5 Multiplier -- follower_volume_lots = master_open_volume_lots * multiplier
func computeMultiplier(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error) {
	if in.Profile.Multiplier == nil {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: MULTIPLIER profile missing multiplier")
	}
	return decimal.NewFromFloat(in.MasterPosition.VolumeLots).Mul(decimal.NewFromFloat(*in.Profile.Multiplier)), false, "", nil
}

// 9.2.6 Custom formula -- evaluated by the sandboxed interpreter in
// ./formula. A parse/eval failure (malformed expression, disallowed
// function, pathological input) is a business rejection
// (RejectCustomFormulaEvaluationFailed), never a Go error -- a bad
// user-authored formula is deterministic and profile-specific, exactly like
// every other "flag and skip" outcome.
func computeCustomFormula(ctx context.Context, in Input, fx FXRateProvider) (decimal.Decimal, bool, string, error) {
	if in.Profile.CustomFormulaExpr == nil {
		return decimal.Zero, false, "", fmt.Errorf("moneymgmt: CUSTOM_FORMULA profile missing custom_formula_expr")
	}

	result, err := formula.Evaluate(ctx, *in.Profile.CustomFormulaExpr, buildFormulaContext(in))
	if err != nil {
		return decimal.Zero, true, RejectCustomFormulaEvaluationFailed, nil
	}
	return decimal.NewFromFloat(result), false, "", nil
}

// buildFormulaContext supplies exactly the variable set docs/09 §9.2.6
// names (master_open_volume_lots, master_account_equity,
// master_account_balance, follower_account_equity, follower_account_balance,
// sl_distance_pips) -- no ambient access to anything else, per
// docs/17-security-architecture.md §17.4.
func buildFormulaContext(in Input) formula.Context {
	slDistancePips := 0.0
	if in.MasterPosition.CurrentSLPrice != nil && in.SymbolSpec.PipSize != 0 {
		slDistancePips = decimal.NewFromFloat(in.MasterPosition.OpenPrice).
			Sub(decimal.NewFromFloat(*in.MasterPosition.CurrentSLPrice)).Abs().
			Div(decimal.NewFromFloat(in.SymbolSpec.PipSize)).InexactFloat64()
	}
	return formula.Context{
		"master_open_volume_lots":  in.MasterPosition.VolumeLots,
		"master_account_equity":    in.MasterAccount.Equity,
		"master_account_balance":   in.MasterAccount.Balance,
		"follower_account_equity":  in.FollowerAccount.Equity,
		"follower_account_balance": in.FollowerAccount.Balance,
		"sl_distance_pips":         slDistancePips,
	}
}
