package ctrader

import (
	"math"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
)

// scaleMoney converts a raw monetary integer (balance, profit, commission,
// margin, ...) into its real decimal value. Every cTrader monetary field is
// carried as an integer alongside a moneyDigits exponent on the same
// message — "moneyDigits = 8 must be interpret as business value multiplied
// by 10^8" (ProtoOATrader's own doc-comment). Price fields (execution
// price, SL/TP, ...) are NOT scaled this way — they're already real floats
// on the wire (fixed64).
func scaleMoney(raw int64, moneyDigits uint32) float64 {
	if moneyDigits == 0 {
		moneyDigits = 2 // cTrader's own documented default when unset
	}
	return float64(raw) / math.Pow10(int(moneyDigits))
}

// volumeToLots converts cTrader's raw order/position volume (an integer "in
// cents of units" — e.g. 1000 means 10.00 units, ProtoOANewOrderReq's own
// doc-comment) into lots, given the symbol's LotSize (also "in cents",
// ProtoOASymbol's own doc-comment). Both /100 factors cancel, so this is
// exactly rawVolume/rawLotSize — see this package's README-equivalent
// comment on SymbolSpec below for the full derivation.
func volumeToLots(rawVolume, rawLotSize int64) float64 {
	if rawLotSize == 0 {
		return 0
	}
	return float64(rawVolume) / float64(rawLotSize)
}

// lotsToVolume is volumeToLots' inverse — used when placing an order, where
// the Copy Engine already gives us lots and cTrader wants raw cents-of-units.
func lotsToVolume(lots float64, rawLotSize int64) int64 {
	return int64(math.Round(lots * float64(rawLotSize)))
}

// tradeDirection maps cTrader's ProtoOATradeSide onto this platform's own
// TradeDirection — same two-value enum, different names.
func tradeDirection(side openapi.ProtoOATradeSide) domain.TradeDirection {
	if side == openapi.ProtoOATradeSide_SELL {
		return domain.TradeDirectionSell
	}
	return domain.TradeDirectionBuy
}

func protoTradeSide(direction domain.TradeDirection) openapi.ProtoOATradeSide {
	if direction == domain.TradeDirectionSell {
		return openapi.ProtoOATradeSide_SELL
	}
	return openapi.ProtoOATradeSide_BUY
}

// pipSize follows the standard "pip position" convention used across
// MT4/MT5-style and cTrader symbol specs alike: the pip is the digit at
// position PipPosition counting from the decimal point (e.g. Digits=5,
// PipPosition=4 for EURUSD means the pip is the 4th decimal, 0.0001).
// Confirmed against real symbol data during this ticket's live verification
// pass (see the runbook) — flagged here as the one derived (not directly
// wire-documented) conversion in this file.
func pipSize(pipPosition int32) float64 {
	return math.Pow10(-int(pipPosition))
}
