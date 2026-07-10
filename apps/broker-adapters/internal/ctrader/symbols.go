package ctrader

import (
	"context"
	"fmt"
	"strings"
	"sync"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
)

// symbolCache holds cTrader's symbol metadata, keyed both by name and by
// cTrader's own numeric symbolId. domain.BrokerAdapter's ResolveSymbol/
// GetSymbolSpecification methods take no ConnectionHandle (TICKET-009's
// interface predates any real adapter needing account-scoped symbol data),
// so this cache is adapter-wide, not per-connection — populated
// opportunistically the first time any connection successfully lists
// symbols (see populateSymbolCache, called from Connect). In practice a
// given broker's symbol set (EURUSD, etc.) is identical across accounts on
// the same server, so this is a reasonable fit for the existing interface,
// not a real limitation — flagged here since it's a compromise forced by an
// interface shape decided before this ticket existed.
type symbolCache struct {
	mu       sync.RWMutex
	byName   map[string]*openapi.ProtoOALightSymbol
	byID     map[int64]*openapi.ProtoOALightSymbol
	fullByID map[int64]*openapi.ProtoOASymbol // populated lazily by GetSymbolSpecification
}

func newSymbolCache() *symbolCache {
	return &symbolCache{
		byName:   make(map[string]*openapi.ProtoOALightSymbol),
		byID:     make(map[int64]*openapi.ProtoOALightSymbol),
		fullByID: make(map[int64]*openapi.ProtoOASymbol),
	}
}

func (c *symbolCache) put(symbols []*openapi.ProtoOALightSymbol) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, s := range symbols {
		c.byName[normalizeSymbolName(s.GetSymbolName())] = s
		c.byID[s.GetSymbolId()] = s
	}
}

func (c *symbolCache) byBrokerName(name string) (*openapi.ProtoOALightSymbol, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	s, ok := c.byName[normalizeSymbolName(name)]
	return s, ok
}

// normalizeSymbolName mirrors the stub adapter's own heuristic
// (apps/copy-engine/internal/stubadapter) — brokers commonly append
// suffixes to a raw symbol name (EURUSD.a, EURUSDm, EURUSD_i, ...); the
// platform's canonical code is always the bare uppercase pair.
func normalizeSymbolName(raw string) string {
	name := strings.ToUpper(raw)
	for _, suffix := range []string{".A", "M", "_I"} {
		name = strings.TrimSuffix(name, suffix)
	}
	return name
}

// ResolveSymbol maps a raw broker symbol name onto this platform's
// canonical NormalizedSymbol. AssetClass is best-effort FX for anything
// that looks like a currency pair (6 letters) — cTrader's own asset-class
// taxonomy (via baseAssetId/quoteAssetId/symbolCategoryId) needs a further
// asset/category lookup this ticket doesn't build out; Money Management
// (TICKET-104) is the first consumer that actually branches on AssetClass,
// so refining this is deferred there rather than guessed precisely now.
func (a *CTraderAdapter) ResolveSymbol(ctx context.Context, brokerSymbol string) (domain.NormalizedSymbol, error) {
	light, ok := a.symbols.byBrokerName(brokerSymbol)
	if !ok {
		return domain.NormalizedSymbol{}, fmt.Errorf("ctrader: unknown symbol %q (no connection has populated the symbol cache yet, or this broker doesn't list it)", brokerSymbol)
	}
	canonical := normalizeSymbolName(light.GetSymbolName())
	assetClass := domain.AssetClassFX
	if len(canonical) != 6 {
		assetClass = domain.AssetClassCommodity // best-effort — see doc-comment above
	}
	return domain.NormalizedSymbol{CanonicalCode: canonical, AssetClass: assetClass}, nil
}

// GetSymbolSpecification fetches (and caches) the full trading spec for a
// symbol. Requires at least one live connection to issue the request
// against — see symbolCache's doc-comment for why this can't be scoped to
// a specific ConnectionHandle.
func (a *CTraderAdapter) GetSymbolSpecification(ctx context.Context, symbol domain.NormalizedSymbol) (domain.SymbolSpec, error) {
	light, ok := a.symbols.byBrokerName(symbol.CanonicalCode)
	if !ok {
		return domain.SymbolSpec{}, fmt.Errorf("ctrader: unknown symbol %q", symbol.CanonicalCode)
	}

	a.symbols.mu.RLock()
	full, cached := a.symbols.fullByID[light.GetSymbolId()]
	a.symbols.mu.RUnlock()
	if !cached {
		conn, err := a.anyConnection()
		if err != nil {
			return domain.SymbolSpec{}, err
		}
		req := &openapi.ProtoOASymbolByIdReq{
			CtidTraderAccountId: &conn.credential.CtidTraderAccountID,
			SymbolId:            []int64{light.GetSymbolId()},
		}
		resp := &openapi.ProtoOASymbolByIdRes{}
		conn.mu.Lock()
		client := conn.client
		conn.mu.Unlock()
		if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOL_BY_ID_REQ), req, resp); err != nil {
			return domain.SymbolSpec{}, fmt.Errorf("ctrader: fetch symbol spec: %w", err)
		}
		if len(resp.GetSymbol()) == 0 {
			return domain.SymbolSpec{}, fmt.Errorf("ctrader: broker returned no spec for symbol %q", symbol.CanonicalCode)
		}
		full = resp.GetSymbol()[0]
		a.symbols.mu.Lock()
		a.symbols.fullByID[light.GetSymbolId()] = full
		a.symbols.mu.Unlock()
	}

	return domain.SymbolSpec{
		Symbol:           symbol,
		BrokerSymbolName: light.GetSymbolName(),
		ContractSize:     float64(full.GetLotSize()) / 100,
		LotStep:          volumeToLots(full.GetStepVolume(), full.GetLotSize()),
		MinLot:           volumeToLots(full.GetMinVolume(), full.GetLotSize()),
		MaxLot:           volumeToLots(full.GetMaxVolume(), full.GetLotSize()),
		PipSize:          pipSize(full.GetPipPosition()),
		Digits:           int(full.GetDigits()),
		MarginCurrency:   "", // needs an assetId->currency-code lookup this ticket doesn't build; confirm during live verification
	}, nil
}

// anyConnection returns an arbitrary healthy connection — used only for
// account-agnostic-in-practice metadata calls (symbol list/spec) that the
// domain.BrokerAdapter interface doesn't scope to a specific handle.
func (a *CTraderAdapter) anyConnection() (*connection, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	for _, conn := range a.connections {
		conn.mu.Lock()
		healthy := conn.healthy
		conn.mu.Unlock()
		if healthy {
			return conn, nil
		}
	}
	return nil, fmt.Errorf("ctrader: no healthy connection available to resolve symbol metadata")
}
