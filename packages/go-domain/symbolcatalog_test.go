package domain

import (
	"context"
	"fmt"
	"sync"
	"testing"
)

func TestNormalizeSymbolName(t *testing.T) {
	cases := []struct {
		raw  string
		want string
	}{
		{"EURUSD", "EURUSD"},
		{"eurusd", "EURUSD"},
		{"EURUSD.a", "EURUSD"},
		{"EURUSD.A", "EURUSD"},
		{"EURUSDm", "EURUSD"},
		{"EURUSDM", "EURUSD"},
		{"EURUSD_i", "EURUSD"},
		{"EURUSD_I", "EURUSD"},
		{"#EURUSD", "EURUSD"},
	}
	for _, c := range cases {
		if got := NormalizeSymbolName(c.raw); got != c.want {
			t.Errorf("NormalizeSymbolName(%q) = %q, want %q", c.raw, got, c.want)
		}
	}
}

func TestAssetClassOf(t *testing.T) {
	cases := []struct {
		code string
		want AssetClass
	}{
		{"EURUSD", AssetClassFX},   // catalog hit
		{"US500", AssetClassIndex}, // catalog hit -- impossible under the old 6-letter-code heuristic
		{"BTCUSD", AssetClassCrypto},
		{"AAPL", AssetClassStockCFD},
		{"GBPNZD", AssetClassFX},            // not in catalog, 6-letter fallback
		{"UNKNOWNSYM", AssetClassCommodity}, // not in catalog, non-6-letter fallback
	}
	for _, c := range cases {
		if got := AssetClassOf(c.code); got != c.want {
			t.Errorf("AssetClassOf(%q) = %q, want %q", c.code, got, c.want)
		}
	}
}

func TestCandidateBrokerSymbolNames_BareCodeFirst(t *testing.T) {
	candidates := CandidateBrokerSymbolNames("EURUSD")
	if len(candidates) == 0 || candidates[0] != "EURUSD" {
		t.Fatalf("CandidateBrokerSymbolNames(\"EURUSD\")[0] = %q, want bare code first", candidates[0])
	}
}

// fakeResolver simulates a broker/EA session that only recognizes a fixed
// set of its own broker-side symbol names, tracking every ResolveSymbol
// call made against it -- proves SuggestSymbolMappings tries candidates in
// order and stops at the first hit, rather than blindly trying every
// candidate.
type fakeResolver struct {
	mu      sync.Mutex
	known   map[string]NormalizedSymbol // broker symbol name -> normalized
	calls   []string
	failGet map[string]bool // canonical codes whose GetSymbolSpecification should error
}

func (f *fakeResolver) ResolveSymbol(ctx context.Context, brokerSymbol string) (NormalizedSymbol, error) {
	f.mu.Lock()
	f.calls = append(f.calls, brokerSymbol)
	f.mu.Unlock()
	n, ok := f.known[brokerSymbol]
	if !ok {
		return NormalizedSymbol{}, fmt.Errorf("fakeResolver: unknown symbol %q", brokerSymbol)
	}
	return n, nil
}

func (f *fakeResolver) GetSymbolSpecification(ctx context.Context, symbol NormalizedSymbol) (SymbolSpec, error) {
	if f.failGet[symbol.CanonicalCode] {
		return SymbolSpec{}, fmt.Errorf("fakeResolver: spec fetch failed for %q", symbol.CanonicalCode)
	}
	return SymbolSpec{Symbol: symbol, BrokerSymbolName: symbol.CanonicalCode}, nil
}

func TestSuggestSymbolMappings_ProbesCandidatesAndSkipsUnavailable(t *testing.T) {
	resolver := &fakeResolver{
		known: map[string]NormalizedSymbol{
			// EURUSD only resolves via its ".a" suffix variant -- proves
			// candidate probing (not just the bare code) is exercised.
			"EURUSD.a": {CanonicalCode: "EURUSD", AssetClass: AssetClassFX},
			// GBPUSD resolves via the bare code -- should stop there,
			// never trying ".a"/"m"/etc for it.
			"GBPUSD": {CanonicalCode: "GBPUSD", AssetClass: AssetClassFX},
			// XAUUSD resolves but its spec fetch fails -- must be skipped,
			// not returned and not fatal to the whole batch.
			"XAUUSD": {CanonicalCode: "XAUUSD", AssetClass: AssetClassCommodity},
		},
		failGet: map[string]bool{"XAUUSD": true},
	}

	specs := SuggestSymbolMappings(context.Background(), resolver, 4)

	gotCodes := map[string]bool{}
	for _, s := range specs {
		gotCodes[s.Symbol.CanonicalCode] = true
	}
	if !gotCodes["EURUSD"] {
		t.Error("expected EURUSD to be suggested via its .a candidate")
	}
	if !gotCodes["GBPUSD"] {
		t.Error("expected GBPUSD to be suggested via its bare-code candidate")
	}
	if gotCodes["XAUUSD"] {
		t.Error("expected XAUUSD to be skipped (GetSymbolSpecification failure), not returned")
	}
	// Every other catalog entry has no known broker name at all -- silently
	// skipped, not an error, not present in specs.
	if len(specs) != 2 {
		t.Errorf("expected exactly 2 suggested specs (EURUSD, GBPUSD), got %d: %+v", len(specs), specs)
	}

	resolver.mu.Lock()
	defer resolver.mu.Unlock()
	sawGBPUSDSuffix := false
	for _, c := range resolver.calls {
		if c == "GBPUSD.a" || c == "GBPUSD.A" || c == "GBPUSDm" {
			sawGBPUSDSuffix = true
		}
	}
	if sawGBPUSDSuffix {
		t.Error("GBPUSD resolved via its bare-code candidate; should not have tried suffix variants after that hit")
	}
}
