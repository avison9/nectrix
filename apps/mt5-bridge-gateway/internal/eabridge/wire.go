package eabridge

import "encoding/json"

// Wire protocol: one JSON object per message, discriminated by a "type"
// field. Both MT5's and MT4's Expert Advisor sides speak the exact same
// message shapes — MQL5/MQL4 differ in how each EA reads its own
// platform's native trade model, not in what goes over the wire (see this
// package's README section in apps/mt5-bridge-gateway/README.md) — AND,
// as of TICKET-121, in which of two transports actually carries them:
//
//   - MT5 (server.go, handleWS): a persistent WebSocket the EA dials into,
//     one JSON object per text frame, both directions multiplexed over the
//     one connection. This is the "native" shape every message struct
//     below is named for.
//   - MT4 (httphandler.go): MQL4 has no native Socket*() functions (a real
//     platform gap, not a bug — see NectrixBridgeMT4.mq4's own header), so
//     its EA instead POSTs a hello to /ea/hello (in place of the WebSocket
//     handshake), long-polls /ea/poll for pending gateway->EA messages
//     (hello_ack/*_request/order_command/ping), and POSTs each EA->gateway
//     message (trade_event/*_result/pong) individually to /ea/events. The
//     exact same JSON message shapes below are reused verbatim — only how
//     they're carried differs — and httpPollConn (httpconn.go) makes the
//     two transports genuinely indistinguishable to Session/readLoop/call:
//     everything from this point down in the package has no idea which
//     transport a given session is actually using.
//
// EA -> Gateway:
//
//	hello             — handshake, opens the session (pairingToken + platform + login/server)
//	trade_event       — a real trade lifecycle event, pushed the moment it happens
//	snapshot_result   — response to a snapshot_request
//	positions_result  — response to a positions_request
//	symbol_spec_result— response to a symbol_spec_request
//	order_result      — response to an order_command
//	pong              — heartbeat reply
//
// Gateway -> EA:
//
//	hello_ack          — accept/reject the handshake
//	snapshot_request    — "call AccountInfoDouble(...) and send me the numbers"
//	positions_request   — "call PositionsTotal()/PositionGetX(...) and send me the list"
//	symbol_spec_request — "call SymbolInfoX(...) for this broker symbol name"
//	order_command       — PlaceOrder/ModifyPosition/ClosePosition, one unified shape
//	ping                — heartbeat
const (
	msgTypeHello            = "hello"
	msgTypeHelloAck         = "hello_ack"
	msgTypeTradeEvent       = "trade_event"
	msgTypeSnapshotRequest  = "snapshot_request"
	msgTypeSnapshotResult   = "snapshot_result"
	msgTypePositionsRequest = "positions_request"
	msgTypePositionsResult  = "positions_result"
	msgTypeSymbolSpecReq    = "symbol_spec_request"
	msgTypeSymbolSpecRes    = "symbol_spec_result"
	msgTypeOrderCommand     = "order_command"
	msgTypeOrderResult      = "order_result"
	msgTypePing             = "ping"
	msgTypePong             = "pong"
)

// Order actions carried by orderCommandMessage.Action, mapping 1:1 onto
// domain.BrokerAdapter's PlaceOrder/ModifyPosition/ClosePosition methods.
const (
	OrderActionPlace  = "PLACE"
	OrderActionModify = "MODIFY"
	OrderActionClose  = "CLOSE"
)

// envelope is decoded first, on every inbound frame, to learn just enough
// (type + correlation id) to route it — either to a pending request's
// waiting channel, or to the session's trade-event subscribers.
type envelope struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId,omitempty"`
}

type helloMessage struct {
	Type         string `json:"type"`
	PairingToken string `json:"pairingToken"`
	Platform     string `json:"platform"` // "MT5" or "MT4"
	Login        string `json:"login"`
	Server       string `json:"server"`
	Currency     string `json:"currency,omitempty"`
	EAVersion    string `json:"eaVersion,omitempty"`
}

type helloAckMessage struct {
	Type            string `json:"type"`
	Accepted        bool   `json:"accepted"`
	BrokerAccountID string `json:"brokerAccountId,omitempty"`
	Reason          string `json:"reason,omitempty"`
}

// wirePosition is the broker-agnostic position shape shared by trade_event
// and positions_result — field-for-field domain.NormalizedPosition, with
// AssetClass carried as its string form so the wire format never depends on
// Go's own enum ordering.
type wirePosition struct {
	BrokerPositionID string   `json:"brokerPositionId"`
	CanonicalSymbol  string   `json:"canonicalSymbol"`
	AssetClass       string   `json:"assetClass"`
	Direction        string   `json:"direction"`
	VolumeLots       float64  `json:"volumeLots"`
	OpenPrice        float64  `json:"openPrice"`
	CurrentSLPrice   *float64 `json:"currentSlPrice,omitempty"`
	CurrentTPPrice   *float64 `json:"currentTpPrice,omitempty"`
	OpenedAt         string   `json:"openedAt"`
}

type tradeEventMessage struct {
	Type              string       `json:"type"`
	EventID           string       `json:"eventId"`
	EventType         string       `json:"eventType"`
	Position          wirePosition `json:"position"`
	ClosedVolumeLots  *float64     `json:"closedVolumeLots,omitempty"`
	FillPrice         *float64     `json:"fillPrice,omitempty"`
	ServerTimestamp   string       `json:"serverTimestamp"`
	ReceivedAtGateway string       `json:"receivedAtGateway,omitempty"`
}

type snapshotRequestMessage struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
}

type snapshotResultMessage struct {
	Type           string   `json:"type"`
	RequestID      string   `json:"requestId"`
	Currency       string   `json:"currency"`
	Balance        float64  `json:"balance"`
	Equity         float64  `json:"equity"`
	UsedMargin     float64  `json:"usedMargin"`
	FreeMargin     float64  `json:"freeMargin"`
	MarginLevelPct *float64 `json:"marginLevelPct,omitempty"`
	AsOf           string   `json:"asOf"`
	Error          string   `json:"error,omitempty"`
}

type positionsRequestMessage struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
}

type positionsResultMessage struct {
	Type      string         `json:"type"`
	RequestID string         `json:"requestId"`
	Positions []wirePosition `json:"positions"`
	Error     string         `json:"error,omitempty"`
}

type symbolSpecRequestMessage struct {
	Type             string `json:"type"`
	RequestID        string `json:"requestId"`
	BrokerSymbolName string `json:"brokerSymbolName"`
}

type symbolSpecResultMessage struct {
	Type             string  `json:"type"`
	RequestID        string  `json:"requestId"`
	BrokerSymbolName string  `json:"brokerSymbolName"`
	ContractSize     float64 `json:"contractSize"`
	LotStep          float64 `json:"lotStep"`
	MinLot           float64 `json:"minLot"`
	MaxLot           float64 `json:"maxLot"`
	PipSize          float64 `json:"pipSize"`
	Digits           int     `json:"digits"`
	MarginCurrency   string  `json:"marginCurrency"`
	Error            string  `json:"error,omitempty"`
}

type orderCommandMessage struct {
	Type             string   `json:"type"`
	RequestID        string   `json:"requestId"`
	Action           string   `json:"action"`
	CanonicalSymbol  string   `json:"canonicalSymbol,omitempty"`
	AssetClass       string   `json:"assetClass,omitempty"`
	Direction        string   `json:"direction,omitempty"`
	VolumeLots       float64  `json:"volumeLots,omitempty"`
	SLPrice          *float64 `json:"slPrice,omitempty"`
	TPPrice          *float64 `json:"tpPrice,omitempty"`
	MaxSlippagePips  float64  `json:"maxSlippagePips,omitempty"`
	ClientOrderTag   string   `json:"clientOrderTag,omitempty"`
	PositionID       string   `json:"positionId,omitempty"`      // MODIFY / CLOSE
	CloseVolumeLots  *float64 `json:"closeVolumeLots,omitempty"` // CLOSE only; nil = close in full
}

type orderResultMessage struct {
	Type              string          `json:"type"`
	RequestID         string          `json:"requestId"`
	Success           bool            `json:"success"`
	BrokerPositionID  string          `json:"brokerPositionId,omitempty"`
	FilledPrice       *float64        `json:"filledPrice,omitempty"`
	RejectReason      string          `json:"rejectReason,omitempty"`
	RawBrokerResponse json.RawMessage `json:"rawBrokerResponse,omitempty"`
}

type pingMessage struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
}

type pongMessage struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
}
