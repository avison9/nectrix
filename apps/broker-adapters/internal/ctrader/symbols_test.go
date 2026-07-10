package ctrader

import (
	"context"
	"testing"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
	"google.golang.org/protobuf/proto"
)

// TestResolveSymbol_UsesCuratedCatalogAssetClass proves ResolveSymbol now
// returns a real, catalog-backed AssetClass (TICKET-103) for instruments
// the old 6-letter-code-only heuristic could never classify correctly
// (indices, crypto, stock CFDs) — no live connection needed, since
// ResolveSymbol only reads the already-populated symbolCache.
func TestResolveSymbol_UsesCuratedCatalogAssetClass(t *testing.T) {
	a := New("client-id", "client-secret")
	a.symbols.put([]*openapi.ProtoOALightSymbol{
		{SymbolId: proto.Int64(1), SymbolName: proto.String("EURUSD")},
		{SymbolId: proto.Int64(2), SymbolName: proto.String("US500")},
		{SymbolId: proto.Int64(3), SymbolName: proto.String("BTCUSD")},
		{SymbolId: proto.Int64(4), SymbolName: proto.String("AAPL")},
	})

	cases := []struct {
		brokerSymbol string
		wantClass    domain.AssetClass
	}{
		{"EURUSD", domain.AssetClassFX},
		{"US500", domain.AssetClassIndex},   // impossible under the old heuristic (not 6 letters -> COMMODITY)
		{"BTCUSD", domain.AssetClassCrypto}, // impossible under the old heuristic (6 letters -> FX, wrong)
		{"AAPL", domain.AssetClassStockCFD},
	}
	for _, c := range cases {
		got, err := a.ResolveSymbol(context.Background(), c.brokerSymbol)
		if err != nil {
			t.Fatalf("ResolveSymbol(%q): %v", c.brokerSymbol, err)
		}
		if got.AssetClass != c.wantClass {
			t.Errorf("ResolveSymbol(%q).AssetClass = %q, want %q", c.brokerSymbol, got.AssetClass, c.wantClass)
		}
	}
}

// TestResolveSymbol_SuffixedBrokerNameStillResolves proves the cache
// lookup's normalization (via domain.NormalizeSymbolName, moved from a
// local heuristic in TICKET-103) still handles a real broker-side suffixed
// name.
func TestResolveSymbol_SuffixedBrokerNameStillResolves(t *testing.T) {
	a := New("client-id", "client-secret")
	a.symbols.put([]*openapi.ProtoOALightSymbol{
		{SymbolId: proto.Int64(1), SymbolName: proto.String("EURUSD.a")},
	})

	got, err := a.ResolveSymbol(context.Background(), "EURUSD.a")
	if err != nil {
		t.Fatalf("ResolveSymbol: %v", err)
	}
	if got.CanonicalCode != "EURUSD" {
		t.Errorf("CanonicalCode = %q, want EURUSD", got.CanonicalCode)
	}
	if got.AssetClass != domain.AssetClassFX {
		t.Errorf("AssetClass = %q, want FX", got.AssetClass)
	}
}
