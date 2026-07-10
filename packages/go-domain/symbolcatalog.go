package domain

import (
	"context"
	"strings"
	"sync"
)

// CanonicalSymbol is one entry in the platform's curated catalog of
// canonical symbols (TICKET-103) -- the fixed universe auto-suggestion
// resolves broker symbol names against. Deliberately rules-based (suffix/
// prefix stripping + this fixed list), no AI/ML matching (TICKET-103's own
// explicit out-of-scope note).
type CanonicalSymbol struct {
	CanonicalCode string
	AssetClass    AssetClass
}

// SymbolCatalog is intentionally small and curated, not exhaustive --
// broker symbols outside this list still resolve via AssetClassOf's
// pre-existing crude fallback, so adding entries here is purely additive,
// never a regression. nectrix_plan/docs/05-domain-model.md §5.3 defines the
// NormalizedSymbol/AssetClass shapes but no seed list -- this is new data,
// not a transcription.
var SymbolCatalog = []CanonicalSymbol{
	// FX majors
	{"EURUSD", AssetClassFX}, {"GBPUSD", AssetClassFX}, {"USDJPY", AssetClassFX},
	{"USDCHF", AssetClassFX}, {"AUDUSD", AssetClassFX}, {"USDCAD", AssetClassFX}, {"NZDUSD", AssetClassFX},
	// FX minors/crosses
	{"EURGBP", AssetClassFX}, {"EURJPY", AssetClassFX}, {"GBPJPY", AssetClassFX},
	{"EURCHF", AssetClassFX}, {"AUDJPY", AssetClassFX}, {"EURAUD", AssetClassFX}, {"GBPCHF", AssetClassFX},
	// Indices (CFD)
	{"US30", AssetClassIndex}, {"US500", AssetClassIndex}, {"NAS100", AssetClassIndex},
	{"GER40", AssetClassIndex}, {"UK100", AssetClassIndex}, {"JPN225", AssetClassIndex},
	// Commodities
	{"XAUUSD", AssetClassCommodity}, {"XAGUSD", AssetClassCommodity},
	{"USOIL", AssetClassCommodity}, {"UKOIL", AssetClassCommodity},
	// Crypto CFDs
	{"BTCUSD", AssetClassCrypto}, {"ETHUSD", AssetClassCrypto},
	// Stock CFDs
	{"AAPL", AssetClassStockCFD}, {"TSLA", AssetClassStockCFD}, {"AMZN", AssetClassStockCFD},
}

// NormalizeSymbolName replaces the identical 5-line heuristic previously
// duplicated in apps/broker-adapters/internal/ctrader and
// apps/mt5-bridge-gateway/internal/mtadapter -- widened to also strip a
// leading "#" (nectrix_plan/docs/08-copy-trading-engine.md §8.4's own
// example symbol list includes "#EURUSD", which neither adapter previously
// handled).
func NormalizeSymbolName(raw string) string {
	name := strings.ToUpper(raw)
	name = strings.TrimPrefix(name, "#")
	for _, suffix := range []string{".A", "M", "_I"} {
		name = strings.TrimSuffix(name, suffix)
	}
	return name
}

// CandidateBrokerSymbolNames generates broker-name guesses for a canonical
// code, in priority order (bare code first) -- the inverse of
// NormalizeSymbolName, used to PROBE a broker/EA session for a symbol it
// doesn't yet know the name of (see SuggestSymbolMappings). Bare-code-first
// matters for MT5/MT4: the EA-bridge wire protocol's RequestSymbolSpec asks
// the real terminal for an exact symbol name (no server-side normalization),
// so trying the plainest, most common form first minimizes wasted round
// trips against a real broker/terminal.
func CandidateBrokerSymbolNames(canonicalCode string) []string {
	return []string{
		canonicalCode,
		canonicalCode + ".a", canonicalCode + ".A",
		canonicalCode + "m", canonicalCode + "M",
		canonicalCode + "_i", canonicalCode + "_I",
		"#" + canonicalCode,
	}
}

// AssetClassOf looks up a real, catalog-backed AssetClass -- replaces both
// adapters' previous crude guessAssetClass (6-letter code => FX, else
// COMMODITY; never INDEX/CRYPTO/STOCK_CFD). Falls back to the exact prior
// heuristic for codes outside the curated catalog, so this is purely
// additive, never a regression for symbols the catalog doesn't cover.
func AssetClassOf(canonicalCode string) AssetClass {
	for _, c := range SymbolCatalog {
		if c.CanonicalCode == canonicalCode {
			return c.AssetClass
		}
	}
	if len(canonicalCode) == 6 {
		return AssetClassFX
	}
	return AssetClassCommodity
}

// SymbolResolver is the ResolveSymbol/GetSymbolSpecification subset of
// BrokerAdapter needed to auto-suggest symbol_mappings -- a narrow
// interface (not the full BrokerAdapter) so callers only need to satisfy
// what SuggestSymbolMappings actually uses.
type SymbolResolver interface {
	ResolveSymbol(ctx context.Context, brokerSymbol string) (NormalizedSymbol, error)
	GetSymbolSpecification(ctx context.Context, symbol NormalizedSymbol) (SymbolSpec, error)
}

// SuggestSymbolMappings probes SymbolCatalog against resolver with bounded
// concurrency, trying each entry's CandidateBrokerSymbolNames in order and
// keeping the first the broker/terminal recognizes. Shared by
// apps/broker-adapters' reconcile.Loop and apps/mt5-bridge-gateway's
// pairing.StatusHandler -- identical logic, two different just-became-
// CONNECTED hook points. Best-effort per entry: a catalog symbol the broker
// doesn't have is silently skipped, not an error -- most brokers won't
// offer every catalog entry (a crypto-CFD broker may lack stock CFDs, etc.).
func SuggestSymbolMappings(ctx context.Context, resolver SymbolResolver, concurrency int) []SymbolSpec {
	sem := make(chan struct{}, concurrency)
	results := make(chan SymbolSpec, len(SymbolCatalog))
	var wg sync.WaitGroup
	for _, entry := range SymbolCatalog {
		wg.Add(1)
		go func(entry CanonicalSymbol) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if spec, ok := resolveOne(ctx, resolver, entry); ok {
				results <- spec
			}
		}(entry)
	}
	go func() { wg.Wait(); close(results) }()

	var specs []SymbolSpec
	for s := range results {
		specs = append(specs, s)
	}
	return specs
}

func resolveOne(ctx context.Context, resolver SymbolResolver, entry CanonicalSymbol) (SymbolSpec, bool) {
	for _, candidate := range CandidateBrokerSymbolNames(entry.CanonicalCode) {
		normalized, err := resolver.ResolveSymbol(ctx, candidate)
		if err != nil {
			continue
		}
		spec, err := resolver.GetSymbolSpecification(ctx, normalized)
		if err != nil {
			continue
		}
		return spec, true
	}
	return SymbolSpec{}, false
}
