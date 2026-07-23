// Package domain holds the canonical, broker-agnostic types shared by the
// Copy Engine, Broker Adapter workers, and MT5 Bridge Gateway.
//
// These are the Go equivalents of the TypeScript interfaces in
// nectrix_plan/docs/05-domain-model.md §5.3 — kept field-for-field identical
// so the two are trivially auditable against each other.
package domain

// BrokerType identifies which broker a symbol/account/position belongs to.
type BrokerType string

const (
	BrokerTypeCTrader BrokerType = "CTRADER"
	BrokerTypeMT5     BrokerType = "MT5"
	// BrokerTypeMT4 — TICKET-102, pulled forward from its original Phase-3
	// scope (TICKET-311) at the user's request, alongside MT5. Both use the
	// same EA-bridge strategy (apps/mt5-bridge-gateway), sharing nearly all
	// Go-side code — see that app's README for the shared protocol design.
	BrokerTypeMT4 BrokerType = "MT4"
)

// AssetClass categorizes a NormalizedSymbol.
type AssetClass string

const (
	AssetClassFX        AssetClass = "FX"
	AssetClassIndex     AssetClass = "INDEX"
	AssetClassCommodity AssetClass = "COMMODITY"
	AssetClassCrypto    AssetClass = "CRYPTO"
	AssetClassStockCFD  AssetClass = "STOCK_CFD"
)

// TradeDirection is the side of a position or order.
type TradeDirection string

const (
	TradeDirectionBuy  TradeDirection = "BUY"
	TradeDirectionSell TradeDirection = "SELL"
)

// TradeEventType enumerates the lifecycle events a BrokerAdapter emits.
type TradeEventType string

const (
	TradeEventPositionOpened          TradeEventType = "POSITION_OPENED"
	TradeEventPositionModified        TradeEventType = "POSITION_MODIFIED"
	TradeEventPositionPartiallyClosed TradeEventType = "POSITION_PARTIALLY_CLOSED"
	TradeEventPositionClosed          TradeEventType = "POSITION_CLOSED"
)

// NormalizedSymbol is the platform's own broker-agnostic symbol identity.
type NormalizedSymbol struct {
	CanonicalCode string     `json:"canonicalCode"` // platform's own symbol code, e.g. "EURUSD"
	AssetClass    AssetClass `json:"assetClass"`
}

// SymbolSpec carries the broker-specific trading parameters for a symbol.
type SymbolSpec struct {
	Symbol           NormalizedSymbol `json:"symbol"`
	BrokerSymbolName string           `json:"brokerSymbolName"` // e.g. "EURUSD.a"
	ContractSize     float64          `json:"contractSize"`     // e.g. 100000 for 1.0 lot FX
	LotStep          float64          `json:"lotStep"`          // e.g. 0.01
	MinLot           float64          `json:"minLot"`
	MaxLot           float64          `json:"maxLot"`
	PipSize          float64          `json:"pipSize"` // e.g. 0.0001
	Digits           int              `json:"digits"`
	MarginCurrency   string           `json:"marginCurrency"`
}

// AccountSnapshot is a point-in-time read of a broker account's financial state.
type AccountSnapshot struct {
	BrokerAccountID string   `json:"brokerAccountId"`
	Currency        string   `json:"currency"` // account denomination, e.g. "USD"
	Balance         float64  `json:"balance"`
	Equity          float64  `json:"equity"`
	UsedMargin      float64  `json:"usedMargin"`
	FreeMargin      float64  `json:"freeMargin"`
	MarginLevelPct  *float64 `json:"marginLevelPct"` // nullable
	AsOf            string   `json:"asOf"`           // ISO-8601 timestamp
	// Leverage is a pre-formatted ratio string (e.g. "1:500"), empty when not available (MT5/MT4's
	// own wire protocol carries no leverage field at all yet — would need an EA-side change, not
	// just Go plumbing; cTrader's own ProtoOATrader.leverageInCents populates this for real).
	Leverage string `json:"leverage"`
}

// NormalizedPosition is a broker-agnostic open position.
type NormalizedPosition struct {
	BrokerPositionID string           `json:"brokerPositionId"`
	Symbol           NormalizedSymbol `json:"symbol"`
	Direction        TradeDirection   `json:"direction"`
	VolumeLots       float64          `json:"volumeLots"`
	OpenPrice        float64          `json:"openPrice"`
	CurrentSLPrice   *float64         `json:"currentSlPrice"` // nullable
	CurrentTPPrice   *float64         `json:"currentTpPrice"` // nullable
	OpenedAt         string           `json:"openedAt"`
	// CurrentPrice (TICKET-124) is the price this position would realize at if closed right
	// now -- bid for BUY, ask for SELL, matching ctrader/accounts.go's own unrealizedPnL
	// convention exactly. Nullable: nil means "no live tick cached yet for this symbol,"
	// never a fabricated 0 -- callers must render this as unknown, not flat. Adapters with no
	// live spot-tick plumbing yet (MT4/MT5) leave this nil for every position.
	CurrentPrice *float64 `json:"currentPrice"`
}

// NormalizedTradeEvent is the event a master's BrokerAdapter emits; input to the Copy Engine.
type NormalizedTradeEvent struct {
	EventID               string             `json:"eventId"` // unique, used as idempotency source
	MasterBrokerAccountID string             `json:"masterBrokerAccountId"`
	EventType             TradeEventType     `json:"eventType"`
	Position              NormalizedPosition `json:"position"`
	ClosedVolumeLots      *float64           `json:"closedVolumeLots,omitempty"` // for PARTIALLY_CLOSED / CLOSED
	FillPrice             *float64           `json:"fillPrice,omitempty"`
	ServerTimestamp       string             `json:"serverTimestamp"`
	ReceivedAtGateway     string             `json:"receivedAtGateway"` // when platform observed it
}

// NormalizedOrderRequest is what the Copy Engine computes and hands to a follower's BrokerAdapter.
type NormalizedOrderRequest struct {
	IdempotencyKey          string           `json:"idempotencyKey"` // derived from (eventId, copyRelationshipId)
	FollowerBrokerAccountID string           `json:"followerBrokerAccountId"`
	Symbol                  NormalizedSymbol `json:"symbol"`
	Direction               TradeDirection   `json:"direction"`
	VolumeLots              float64          `json:"volumeLots"`
	SLPrice                 *float64         `json:"slPrice"` // nullable
	TPPrice                 *float64         `json:"tpPrice"` // nullable
	MaxSlippagePips         float64          `json:"maxSlippagePips"`
	ClientOrderTag          string           `json:"clientOrderTag"` // links back to originating master position
}

// NormalizedOrderResult is the outcome of placing/modifying/closing an order via a BrokerAdapter.
type NormalizedOrderResult struct {
	Success           bool        `json:"success"`
	BrokerPositionID  string      `json:"brokerPositionId,omitempty"`
	FilledPrice       *float64    `json:"filledPrice,omitempty"`
	RejectReason      string      `json:"rejectReason,omitempty"`
	RawBrokerResponse interface{} `json:"rawBrokerResponse"` // preserved for audit, never parsed by upstream logic
}
