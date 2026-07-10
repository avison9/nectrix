package eabridge

import (
	"fmt"

	domain "github.com/avison9/nectrix/go-domain"
)

func directionToWire(d domain.TradeDirection) string { return string(d) }

func directionFromWire(s string) (domain.TradeDirection, error) {
	switch domain.TradeDirection(s) {
	case domain.TradeDirectionBuy:
		return domain.TradeDirectionBuy, nil
	case domain.TradeDirectionSell:
		return domain.TradeDirectionSell, nil
	default:
		return "", fmt.Errorf("eabridge: unknown trade direction %q", s)
	}
}

func assetClassToWire(a domain.AssetClass) string { return string(a) }

func assetClassFromWire(s string) domain.AssetClass {
	switch domain.AssetClass(s) {
	case domain.AssetClassFX, domain.AssetClassIndex, domain.AssetClassCommodity, domain.AssetClassCrypto, domain.AssetClassStockCFD:
		return domain.AssetClass(s)
	default:
		return domain.AssetClassFX // best-effort default, mirrors internal/ctrader's own compromise
	}
}

func eventTypeFromWire(s string) (domain.TradeEventType, error) {
	switch domain.TradeEventType(s) {
	case domain.TradeEventPositionOpened, domain.TradeEventPositionModified,
		domain.TradeEventPositionPartiallyClosed, domain.TradeEventPositionClosed:
		return domain.TradeEventType(s), nil
	default:
		return "", fmt.Errorf("eabridge: unknown trade event type %q", s)
	}
}

func positionFromWire(w wirePosition) (domain.NormalizedPosition, error) {
	direction, err := directionFromWire(w.Direction)
	if err != nil {
		return domain.NormalizedPosition{}, err
	}
	return domain.NormalizedPosition{
		BrokerPositionID: w.BrokerPositionID,
		Symbol:           domain.NormalizedSymbol{CanonicalCode: w.CanonicalSymbol, AssetClass: assetClassFromWire(w.AssetClass)},
		Direction:        direction,
		VolumeLots:       w.VolumeLots,
		OpenPrice:        w.OpenPrice,
		CurrentSLPrice:   w.CurrentSLPrice,
		CurrentTPPrice:   w.CurrentTPPrice,
		OpenedAt:         w.OpenedAt,
	}, nil
}

func tradeEventFromWire(masterBrokerAccountID string, msg tradeEventMessage) (domain.NormalizedTradeEvent, error) {
	eventType, err := eventTypeFromWire(msg.EventType)
	if err != nil {
		return domain.NormalizedTradeEvent{}, err
	}
	position, err := positionFromWire(msg.Position)
	if err != nil {
		return domain.NormalizedTradeEvent{}, err
	}
	return domain.NormalizedTradeEvent{
		EventID:               msg.EventID,
		MasterBrokerAccountID: masterBrokerAccountID,
		EventType:             eventType,
		Position:              position,
		ClosedVolumeLots:      msg.ClosedVolumeLots,
		FillPrice:             msg.FillPrice,
		ServerTimestamp:       msg.ServerTimestamp,
		ReceivedAtGateway:     msg.ReceivedAtGateway,
	}, nil
}

// OrderCommand is the domain-shaped request Session.SendOrderCommand takes —
// keeps orderCommandMessage's wire shape private to this package, matching
// how tradesignals keeps eventsv1's proto types out of its own callers.
type OrderCommand struct {
	Action          string // OrderActionPlace / OrderActionModify / OrderActionClose
	Symbol          domain.NormalizedSymbol
	Direction       domain.TradeDirection
	VolumeLots      float64
	SLPrice         *float64
	TPPrice         *float64
	MaxSlippagePips float64
	ClientOrderTag  string
	PositionID      string
	CloseVolumeLots *float64
}

func (c OrderCommand) toWire(requestID string) orderCommandMessage {
	return orderCommandMessage{
		Type:            msgTypeOrderCommand,
		RequestID:       requestID,
		Action:          c.Action,
		CanonicalSymbol: c.Symbol.CanonicalCode,
		AssetClass:      assetClassToWire(c.Symbol.AssetClass),
		Direction:       directionToWire(c.Direction),
		VolumeLots:      c.VolumeLots,
		SLPrice:         c.SLPrice,
		TPPrice:         c.TPPrice,
		MaxSlippagePips: c.MaxSlippagePips,
		ClientOrderTag:  c.ClientOrderTag,
		PositionID:      c.PositionID,
		CloseVolumeLots: c.CloseVolumeLots,
	}
}

func orderResultFromWire(msg orderResultMessage) domain.NormalizedOrderResult {
	var raw interface{}
	if len(msg.RawBrokerResponse) > 0 {
		raw = msg.RawBrokerResponse
	}
	return domain.NormalizedOrderResult{
		Success:           msg.Success,
		BrokerPositionID:  msg.BrokerPositionID,
		FilledPrice:       msg.FilledPrice,
		RejectReason:      msg.RejectReason,
		RawBrokerResponse: raw,
	}
}
