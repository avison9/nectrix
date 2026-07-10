package mtadapter

import (
	"context"
	"fmt"
	"strings"
	"sync"

	domain "github.com/avison9/nectrix/go-domain"
)

// cachedSymbol pairs a resolved canonical identity with the full spec the EA
// reported for it — populated together from one symbol_spec_request round
// trip (ResolveSymbol), so a later GetSymbolSpecification call (which takes
// no broker-symbol-name, only the already-canonical NormalizedSymbol) can be
// answered from cache instead of needing a second wire protocol.
type cachedSymbol struct {
	normalized domain.NormalizedSymbol
	spec       domain.SymbolSpec
}

// symbolCache is adapter-wide (not per-account) for the same reason
// internal/ctrader's own symbolCache is: ResolveSymbol/GetSymbolSpecification
// take no domain.ConnectionHandle, so there's nowhere to scope it more
// narrowly without changing the shared interface — a given broker's symbol
// set is identical across accounts on the same server in practice anyway.
type symbolCache struct {
	mu          sync.RWMutex
	byCanonical map[string]cachedSymbol
}

func newSymbolCache() *symbolCache {
	return &symbolCache{byCanonical: make(map[string]cachedSymbol)}
}

func (c *symbolCache) get(canonical string) (cachedSymbol, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	s, ok := c.byCanonical[canonical]
	return s, ok
}

func (c *symbolCache) put(canonical string, s cachedSymbol) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.byCanonical[canonical] = s
}

// normalizeSymbolName mirrors internal/ctrader's own identical heuristic
// (and the stub adapter's before it) — brokers commonly append suffixes to
// a raw symbol name (EURUSD.a, EURUSDm, EURUSD_i, ...); the platform's
// canonical code is always the bare uppercase pair. Kept package-local
// rather than shared, matching how internal/ctrader didn't extract it
// either — a 5-line heuristic isn't worth a shared package's coupling.
func normalizeSymbolName(raw string) string {
	name := strings.ToUpper(raw)
	for _, suffix := range []string{".A", "M", "_I"} {
		name = strings.TrimSuffix(name, suffix)
	}
	return name
}

// guessAssetClass is the same best-effort compromise internal/ctrader's
// ResolveSymbol documents: FX for anything that looks like a 6-letter
// currency pair, COMMODITY otherwise. Refining this needs a real
// asset-category lookup neither adapter builds out yet — Money Management
// (TICKET-104) is the first consumer that actually branches on AssetClass.
func guessAssetClass(canonical string) domain.AssetClass {
	if len(canonical) == 6 {
		return domain.AssetClassFX
	}
	return domain.AssetClassCommodity
}

// ResolveSymbol asks any live session of this adapter's platform to resolve
// brokerSymbol (e.g. "EURUSD.a") via a real symbol_spec_request, caching
// both the canonical identity and the full spec for GetSymbolSpecification.
func (a *Adapter) ResolveSymbol(ctx context.Context, brokerSymbol string) (domain.NormalizedSymbol, error) {
	canonical := normalizeSymbolName(brokerSymbol)
	if cached, ok := a.symbols.get(canonical); ok {
		return cached.normalized, nil
	}

	sess, ok := a.server.AnySession(a.brokerType)
	if !ok {
		return domain.NormalizedSymbol{}, fmt.Errorf("mtadapter(%s): no live EA session available to resolve symbol %q", a.brokerType, brokerSymbol)
	}

	result, err := sess.RequestSymbolSpec(ctx, brokerSymbol)
	if err != nil {
		return domain.NormalizedSymbol{}, fmt.Errorf("mtadapter(%s): resolve symbol %q: %w", a.brokerType, brokerSymbol, err)
	}

	normalized := domain.NormalizedSymbol{CanonicalCode: canonical, AssetClass: guessAssetClass(canonical)}
	spec := domain.SymbolSpec{
		Symbol:           normalized,
		BrokerSymbolName: result.BrokerSymbolName,
		ContractSize:     result.ContractSize,
		LotStep:          result.LotStep,
		MinLot:           result.MinLot,
		MaxLot:           result.MaxLot,
		PipSize:          result.PipSize,
		Digits:           result.Digits,
		MarginCurrency:   result.MarginCurrency,
	}
	a.symbols.put(canonical, cachedSymbol{normalized: normalized, spec: spec})
	return normalized, nil
}

// GetSymbolSpecification is answered from the cache ResolveSymbol
// populates — it's always called after ResolveSymbol in the real symbol
// mapping flow (docs/08-copy-trading-engine.md §8.4: the platform learns a
// broker's raw symbol names before it ever needs their specs), so an
// unresolved canonical code here means a caller bug, not a legitimate gap.
func (a *Adapter) GetSymbolSpecification(ctx context.Context, symbol domain.NormalizedSymbol) (domain.SymbolSpec, error) {
	if cached, ok := a.symbols.get(symbol.CanonicalCode); ok {
		return cached.spec, nil
	}
	return domain.SymbolSpec{}, fmt.Errorf("mtadapter(%s): no cached spec for %q (call ResolveSymbol with the broker's own symbol name first)", a.brokerType, symbol.CanonicalCode)
}
