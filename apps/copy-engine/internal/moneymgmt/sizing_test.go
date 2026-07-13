package moneymgmt

import (
	"context"
	"errors"
	"math"
	"testing"

	domain "github.com/avison9/nectrix/go-domain"
)

// fakeFXRateProvider mirrors packages/go-domain/symbolcatalog_test.go's
// fakeResolver pattern (a hand-rolled fake implementing the interface, no
// HTTP mocking library) -- map-backed, keyed "from|to".
type fakeFXRateProvider struct {
	rates map[string]float64
}

func newFakeFXRateProvider(rates map[string]float64) *fakeFXRateProvider {
	return &fakeFXRateProvider{rates: rates}
}

func (f *fakeFXRateProvider) Rate(ctx context.Context, from, to string) (float64, error) {
	if from == to {
		return 1, nil
	}
	rate, ok := f.rates[from+"|"+to]
	if !ok {
		return 0, errors.New("fakeFXRateProvider: no rate configured for " + from + "->" + to)
	}
	return rate, nil
}

// noOpNormalizeSpec has a lot_step of 1 and a huge max_lot, so
// NormalizeLot's own rounding/clamping never affects the raw computed
// value -- lets AC1's tests isolate formula correctness from normalization.
var noOpNormalizeSpec = domain.SymbolSpec{
	Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
	ContractSize:   100000,
	LotStep:        0.0000001,
	MinLot:         0.0000001,
	MaxLot:         1000000,
	PipSize:        0.0001,
	Digits:         5,
	MarginCurrency: "USD",
}

func approxEqual(a, b float64) bool { return math.Abs(a-b) < 1e-6 }

// AC1: FIXED_LOT — every copy uses a constant lot size, currency-irrelevant.
func TestComputeLotSize_FixedLot(t *testing.T) {
	fixedLotSize := 0.5
	profile := Profile{Method: MethodFixedLot, FixedLotSize: &fixedLotSize, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 10, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000, Balance: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "EUR", Equity: 500, Balance: 500}, // different currency, must not matter
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	if result.Rejected {
		t.Fatalf("unexpected rejection: %s", result.RejectReason)
	}
	if !approxEqual(result.NormalizedLots, fixedLotSize) {
		t.Fatalf("NormalizedLots = %v, want %v", result.NormalizedLots, fixedLotSize)
	}
}

// AC1: PROPORTIONAL_EQUITY, same currency —
// equity_ratio = follower_equity / master_equity; volume = master_volume * ratio.
func TestComputeLotSize_ProportionalEquity_SameCurrency(t *testing.T) {
	profile := Profile{Method: MethodProportionalEquity, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 2.0, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Equity: 2500},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	want := 2.0 * (2500.0 / 10000.0) // = 0.5
	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v", result.NormalizedLots, want)
	}
}

// AC1: PROPORTIONAL_EQUITY, cross-currency — follower equity is converted
// to master's currency before the ratio, per docs/09 §9.2.2.
func TestComputeLotSize_ProportionalEquity_CrossCurrency(t *testing.T) {
	profile := Profile{Method: MethodProportionalEquity, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 4.0, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 20000},
		FollowerAccount: domain.AccountSnapshot{Currency: "EUR", Equity: 5000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	// fx_rate(EUR, USD) = 1.08: 5000 EUR -> 5400 USD
	fx := newFakeFXRateProvider(map[string]float64{"EUR|USD": 1.08})
	result, err := ComputeLotSize(context.Background(), in, fx)
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	followerEquityInMasterCcy := 5000.0 * 1.08
	want := 4.0 * (followerEquityInMasterCcy / 20000.0)
	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v (hand-calculated: 4.0 * (5400/20000) = 1.08)", result.NormalizedLots, want)
	}
}

// AC1: PROPORTIONAL_BALANCE — same as equity but uses balance, and per
// docs/09 §9.2.3 has NO documented cross-currency conversion (implemented
// literally, see this ticket's plan "Open risks" #2).
func TestComputeLotSize_ProportionalBalance(t *testing.T) {
	profile := Profile{Method: MethodProportionalBalance, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 3.0, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Balance: 15000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Balance: 3000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	want := 3.0 * (3000.0 / 15000.0) // = 0.6
	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v", result.NormalizedLots, want)
	}
}

// AC1 + AC3: RISK_PERCENT — sizes so a stop-loss hit costs exactly
// risk_percent of follower equity. Hand-calculated per docs/09 §9.2.4.
func TestComputeLotSize_RiskPercent_HandCalculated(t *testing.T) {
	riskPercent := 2.0 // 2%
	profile := Profile{Method: MethodRiskPercent, RiskPercent: &riskPercent, RoundingMode: RoundingDown}
	slPrice := 1.0950
	in := Input{
		Profile: profile,
		MasterPosition: domain.NormalizedPosition{
			VolumeLots:     1.0,
			OpenPrice:      1.1000,
			CurrentSLPrice: &slPrice,
			Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}

	// Hand-calculated per docs/09 §9.2.4:
	riskAmount := 10000.0 * (2.0 / 100) // 200
	slDistancePrice := math.Abs(1.1000 - 1.0950) // 0.0050
	slDistancePips := slDistancePrice / 0.0001 // 50
	pipValuePerLot := 100000.0 * 0.0001 * 1.0 // 10 (USD quote, USD follower ccy, rate=1)
	want := riskAmount / (slDistancePips * pipValuePerLot) // 200 / (50*10) = 0.4

	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v (hand-calculated)", result.NormalizedLots, want)
	}
}

// AC1: RISK_PERCENT cross-currency — quote currency (USD for EURUSD) !=
// follower account currency (GBP), so fx_rate(quote_ccy, follower_ccy) is
// applied to the pip value.
func TestComputeLotSize_RiskPercent_CrossCurrency(t *testing.T) {
	riskPercent := 1.0
	profile := Profile{Method: MethodRiskPercent, RiskPercent: &riskPercent, RoundingMode: RoundingDown}
	slPrice := 1.0900
	in := Input{
		Profile: profile,
		MasterPosition: domain.NormalizedPosition{
			VolumeLots:     1.0,
			OpenPrice:      1.1000,
			CurrentSLPrice: &slPrice,
			Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "GBP", Equity: 8000},
		SymbolSpec:      noOpNormalizeSpec, // MarginCurrency "USD", quote ccy derived as "USD" from EURUSD
	}
	fx := newFakeFXRateProvider(map[string]float64{"USD|GBP": 0.79})
	result, err := ComputeLotSize(context.Background(), in, fx)
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}

	riskAmount := 8000.0 * (1.0 / 100) // 80
	slDistancePips := math.Abs(1.1000-1.0900) / 0.0001 // 100
	pipValuePerLot := 100000.0 * 0.0001 * 0.79 // 7.9
	want := riskAmount / (slDistancePips * pipValuePerLot) // 80 / 790

	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v (hand-calculated)", result.NormalizedLots, want)
	}
}

// AC3: "Risk-Percent method rejects with RISK_PERCENT_REQUIRES_SL when the
// master position has no SL and the fallback option is disabled (default)."
func TestComputeLotSize_RiskPercent_NoSL_RejectsByDefault(t *testing.T) {
	riskPercent := 1.0
	profile := Profile{Method: MethodRiskPercent, RiskPercent: &riskPercent, RoundingMode: RoundingDown}
	// AllowAssumedSLDistanceFallback left at its zero value (false) —
	// there is no DB column to set it true today, matching the doc's
	// "safe default" requirement structurally, not just by convention.
	in := Input{
		Profile: profile,
		MasterPosition: domain.NormalizedPosition{
			VolumeLots:     1.0,
			OpenPrice:      1.1000,
			CurrentSLPrice: nil,
			Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	if !result.Rejected {
		t.Fatal("expected rejection for RISK_PERCENT with no SL and fallback disabled")
	}
	if result.RejectReason != RejectRiskPercentRequiresSL {
		t.Fatalf("RejectReason = %q, want %q", result.RejectReason, RejectRiskPercentRequiresSL)
	}
}

// Proves the alternate assumed-SL-distance branch is implemented and
// reachable even though no DB column exists yet to persist it as true.
func TestComputeLotSize_RiskPercent_NoSL_FallbackEnabled_Sizes(t *testing.T) {
	riskPercent := 1.0
	assumedPips := 50.0
	profile := Profile{
		Method: MethodRiskPercent, RiskPercent: &riskPercent, RoundingMode: RoundingDown,
		AllowAssumedSLDistanceFallback: true, AssumedSLDistancePips: &assumedPips,
	}
	in := Input{
		Profile: profile,
		MasterPosition: domain.NormalizedPosition{
			VolumeLots:     1.0,
			OpenPrice:      1.1000,
			CurrentSLPrice: nil,
			Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	if result.Rejected {
		t.Fatalf("unexpected rejection with fallback enabled: %s", result.RejectReason)
	}
	riskAmount := 10000.0 * (1.0 / 100)
	pipValuePerLot := 100000.0 * 0.0001 * 1.0
	want := riskAmount / (assumedPips * pipValuePerLot)
	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v", result.NormalizedLots, want)
	}
}

// AC1: MULTIPLIER — direct scalar on master's own lot size.
func TestComputeLotSize_Multiplier(t *testing.T) {
	multiplier := 1.5
	profile := Profile{Method: MethodMultiplier, Multiplier: &multiplier, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 2.0, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD"},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD"},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	want := 2.0 * 1.5
	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v", result.NormalizedLots, want)
	}
}

// AC1: CUSTOM_FORMULA — evaluated by the sandboxed interpreter against the
// exact docs/09 §9.2.6 context variable set.
func TestComputeLotSize_CustomFormula(t *testing.T) {
	expr := "min(master_open_volume_lots * 2, follower_account_equity / master_account_equity)"
	profile := Profile{Method: MethodCustomFormula, CustomFormulaExpr: &expr, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 1.0, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Equity: 4000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("ComputeLotSize returned error: %v", err)
	}
	want := math.Min(1.0*2, 4000.0/10000.0) // min(2.0, 0.4) = 0.4
	if !approxEqual(result.NormalizedLots, want) {
		t.Fatalf("NormalizedLots = %v, want %v", result.NormalizedLots, want)
	}
}

// A malformed/malicious custom formula is a business rejection, never a Go
// error — see sizing.go's computeCustomFormula doc comment.
func TestComputeLotSize_CustomFormula_InvalidExpression_Rejected(t *testing.T) {
	expr := "exec(1)"
	profile := Profile{Method: MethodCustomFormula, CustomFormulaExpr: &expr, RoundingMode: RoundingDown}
	in := Input{
		Profile:         profile,
		MasterPosition:  domain.NormalizedPosition{VolumeLots: 1.0, Symbol: noOpNormalizeSpec.Symbol},
		MasterAccount:   domain.AccountSnapshot{Currency: "USD", Equity: 10000},
		FollowerAccount: domain.AccountSnapshot{Currency: "USD", Equity: 4000},
		SymbolSpec:      noOpNormalizeSpec,
	}
	result, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err != nil {
		t.Fatalf("expected a business rejection, not a Go error: %v", err)
	}
	if !result.Rejected || result.RejectReason != RejectCustomFormulaEvaluationFailed {
		t.Fatalf("expected Rejected=true, RejectReason=%q, got Rejected=%v, RejectReason=%q",
			RejectCustomFormulaEvaluationFailed, result.Rejected, result.RejectReason)
	}
}

func TestComputeLotSize_UnrecognizedMethod_ReturnsError(t *testing.T) {
	profile := Profile{Method: "NOT_A_REAL_METHOD"}
	in := Input{Profile: profile, SymbolSpec: noOpNormalizeSpec}
	_, err := ComputeLotSize(context.Background(), in, newFakeFXRateProvider(nil))
	if err == nil {
		t.Fatal("expected an error for an unrecognized Method")
	}
}
