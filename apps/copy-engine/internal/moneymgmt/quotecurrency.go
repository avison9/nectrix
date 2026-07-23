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
// characters ARE the quote currency (EURUSD -> USD, exact and correct). For
// every other asset class (index/commodity/crypto/stock CFDs) this falls
// back to the symbol spec's margin currency as the best available proxy --
// a real approximation, not a true quote-currency lookup, worth a proper
// SymbolSpec.QuoteCurrency field in a later ticket.
func QuoteCurrencyOf(symbol domain.NormalizedSymbol, spec domain.SymbolSpec) string {
	if symbol.AssetClass == domain.AssetClassFX && len(symbol.CanonicalCode) == 6 {
		return symbol.CanonicalCode[3:]
	}
	return spec.MarginCurrency
}
