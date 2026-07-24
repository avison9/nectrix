package moneymgmt

import (
	"testing"

	domain "github.com/avison9/nectrix/go-domain"
)

func TestQuoteCurrencyOf(t *testing.T) {
	cases := []struct {
		name       string
		symbol     domain.NormalizedSymbol
		marginCcy  string
		wantQuote  string
	}{
		{"FX pair parses from code", domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX}, "", "USD"},
		{"commodity parses from code", domain.NormalizedSymbol{CanonicalCode: "XAUUSD", AssetClass: domain.AssetClassCommodity}, "", "USD"},
		{"crypto parses from code", domain.NormalizedSymbol{CanonicalCode: "BTCUSD", AssetClass: domain.AssetClassCrypto}, "", "USD"},
		{"index falls back to margin currency", domain.NormalizedSymbol{CanonicalCode: "US500", AssetClass: domain.AssetClassIndex}, "USD", "USD"},
		{"index with no margin currency stays blank", domain.NormalizedSymbol{CanonicalCode: "US500", AssetClass: domain.AssetClassIndex}, "", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			spec := domain.SymbolSpec{Symbol: tc.symbol, MarginCurrency: tc.marginCcy}
			got := QuoteCurrencyOf(tc.symbol, spec)
			if got != tc.wantQuote {
				t.Errorf("QuoteCurrencyOf() = %q, want %q", got, tc.wantQuote)
			}
		})
	}
}
