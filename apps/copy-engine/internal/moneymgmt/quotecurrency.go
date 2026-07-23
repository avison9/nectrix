package moneymgmt

import domain "github.com/avison9/nectrix/go-domain"

// QuoteCurrencyOf approximates the quote currency of a symbol for the pip
// value conversion docs/09 §9.2.4 needs (fx_rate(quote_ccy, follower_ccy)).
// Exported (was quoteCurrencyOf) — realized P&L computation (pipeline
// package) needs the exact same approximation, not a second copy of it.
//
// packages/go-domain.SymbolSpec has no explicit quote-currency field (a real
// gap -- see this ticket's plan "Open risks" #1; not addressed by widening
// domain.go in this ticket). For FX pairs the canonical code's last 3
// characters ARE the quote currency (EURUSD -> USD, exact and correct).
//
// Bugfix — COMMODITY/CRYPTO canonical codes (XAUUSD, XAGUSD, BTCUSD, ETHUSD)
// are the exact same "XXXYYY" shape and just as exact to parse this way; this
// was previously restricted to AssetClassFX alone, so every one of these fell
// through to spec.MarginCurrency, which broker-adapters' own ctrader/symbols.go
// hardcodes to "" (a documented, still-open TODO -- no assetId->currency-code
// lookup built yet) -- meaning realized/unrealized P&L silently came back nil
// for every non-FX position, confirmed live (a real XAUUSD trade). INDEX/
// STOCK_CFD codes (US500, AAPL, ...) have no embedded currency and must keep
// falling back to margin_currency -- that gap is real and still open.
func QuoteCurrencyOf(symbol domain.NormalizedSymbol, spec domain.SymbolSpec) string {
	parsable := symbol.AssetClass == domain.AssetClassFX ||
		symbol.AssetClass == domain.AssetClassCommodity ||
		symbol.AssetClass == domain.AssetClassCrypto
	if parsable && len(symbol.CanonicalCode) == 6 {
		return symbol.CanonicalCode[3:]
	}
	return spec.MarginCurrency
}
