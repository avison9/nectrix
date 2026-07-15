// Package tradesignals is the exact mirror image of
// apps/broker-adapters/internal/tradesignals and
// apps/mt5-bridge-gateway/internal/tradesignals's own toProto -- those
// services convert domain.NormalizedTradeEvent into
// *eventsv1.NormalizedTradeEvent to publish onto the trade-signals Kafka
// topic; Copy Engine is the consumer, so it needs the reverse conversion.
// Unrecognized enum values map to their domain "UNSPECIFIED"-equivalent
// zero value here (never an error) -- unlike the producer side, a consumer
// rejecting a message outright over an enum it doesn't recognize would be a
// worse failure mode than processing it with a best-effort mapping; downstream
// pipeline logic (e.g. dispatch.go's own event-type switch) is what actually
// decides whether an unrecognized value is fatal for that signal.
package tradesignals

import (
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
)

// Topic mirrors apps/broker-adapters/internal/tradesignals's and
// apps/mt5-bridge-gateway/internal/tradesignals's own identical constant --
// the one real Kafka topic both publish NormalizedTradeEvent onto.
const Topic = "trade-signals"

// FromProto converts a real trade-signals Kafka message into this
// platform's own domain.NormalizedTradeEvent, ready for Pipeline.HandleEvent.
func FromProto(e *eventsv1.NormalizedTradeEvent) domain.NormalizedTradeEvent {
	return domain.NormalizedTradeEvent{
		EventID:               e.GetEventId(),
		MasterBrokerAccountID: e.GetMasterBrokerAccountId(),
		EventType:             fromProtoEventType(e.GetEventType()),
		Position:              fromProtoPosition(e.GetPosition()),
		ClosedVolumeLots:      e.ClosedVolumeLots,
		FillPrice:             e.FillPrice,
		ServerTimestamp:       e.GetServerTimestamp(),
		ReceivedAtGateway:     e.GetReceivedAtGateway(),
	}
}

func fromProtoPosition(p *eventsv1.NormalizedPosition) domain.NormalizedPosition {
	return domain.NormalizedPosition{
		BrokerPositionID: p.GetBrokerPositionId(),
		Symbol: domain.NormalizedSymbol{
			CanonicalCode: p.GetSymbol().GetCanonicalCode(),
			AssetClass:    fromProtoAssetClass(p.GetSymbol().GetAssetClass()),
		},
		Direction:      fromProtoDirection(p.GetDirection()),
		VolumeLots:     p.GetVolumeLots(),
		OpenPrice:      p.GetOpenPrice(),
		CurrentSLPrice: p.CurrentSlPrice,
		CurrentTPPrice: p.CurrentTpPrice,
		OpenedAt:       p.GetOpenedAt(),
	}
}

func fromProtoAssetClass(a eventsv1.AssetClass) domain.AssetClass {
	switch a {
	case eventsv1.AssetClass_ASSET_CLASS_FX:
		return domain.AssetClassFX
	case eventsv1.AssetClass_ASSET_CLASS_INDEX:
		return domain.AssetClassIndex
	case eventsv1.AssetClass_ASSET_CLASS_COMMODITY:
		return domain.AssetClassCommodity
	case eventsv1.AssetClass_ASSET_CLASS_CRYPTO:
		return domain.AssetClassCrypto
	case eventsv1.AssetClass_ASSET_CLASS_STOCK_CFD:
		return domain.AssetClassStockCFD
	default:
		return ""
	}
}

func fromProtoDirection(d eventsv1.TradeDirection) domain.TradeDirection {
	switch d {
	case eventsv1.TradeDirection_TRADE_DIRECTION_BUY:
		return domain.TradeDirectionBuy
	case eventsv1.TradeDirection_TRADE_DIRECTION_SELL:
		return domain.TradeDirectionSell
	default:
		return ""
	}
}

func fromProtoEventType(t eventsv1.TradeEventType) domain.TradeEventType {
	switch t {
	case eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_OPENED:
		return domain.TradeEventPositionOpened
	case eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_MODIFIED:
		return domain.TradeEventPositionModified
	case eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_PARTIALLY_CLOSED:
		return domain.TradeEventPositionPartiallyClosed
	case eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_CLOSED:
		return domain.TradeEventPositionClosed
	default:
		return ""
	}
}
