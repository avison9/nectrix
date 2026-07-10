// Package tradesignals turns a domain.BrokerAdapter's StreamTradeEvents
// callback into a real publish to the trade-signals Kafka topic — this
// service's first real business producer (packages/event-contracts/README.md:
// topic `trade-signals`, key `master_broker_account_id`, message
// `NormalizedTradeEvent`). It knows nothing about cTrader specifically; any
// domain.BrokerAdapter's onEvent callback can use it.
package tradesignals

import (
	"context"
	"fmt"

	"github.com/avison9/nectrix/event-contracts/go/eventconsumer"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/segmentio/kafka-go"
)

// Topic is the Kafka topic name from packages/event-contracts/README.md's
// topic catalog and infra/kafka/create-topics.sh's topic list.
const Topic = "trade-signals"

// NewWriter builds a *kafka.Writer targeting Topic, keyed by
// master_broker_account_id (Hash balancer) so every event for the same
// master account lands on the same partition — the per-key ordering
// infra/kafka/create-topics.sh's multi-partition layout is meant to prove.
func NewWriter(brokerAddr string) *kafka.Writer {
	return &kafka.Writer{
		Addr:     kafka.TCP(brokerAddr),
		Topic:    Topic,
		Balancer: &kafka.Hash{},
	}
}

// Publisher adapts a *kafka.Writer into the
// func(context.Context, domain.NormalizedTradeEvent) error shape
// domain.BrokerAdapter.StreamTradeEvents expects as its onEvent callback.
type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(writer *kafka.Writer) *Publisher {
	return &Publisher{writer: writer}
}

// OnEvent publishes event to Topic, keyed by its MasterBrokerAccountID. Any
// error here propagates back through StreamTradeEvents' callback contract to
// the calling adapter, which logs it — a single failed publish never crashes
// the read loop.
func (p *Publisher) OnEvent(ctx context.Context, event domain.NormalizedTradeEvent) error {
	msg, err := toProto(event)
	if err != nil {
		return fmt.Errorf("tradesignals: map event: %w", err)
	}
	if err := eventconsumer.Publish(ctx, p.writer, event.MasterBrokerAccountID, msg); err != nil {
		return fmt.Errorf("tradesignals: publish: %w", err)
	}
	return nil
}

func toProto(event domain.NormalizedTradeEvent) (*eventsv1.NormalizedTradeEvent, error) {
	eventType, err := toProtoEventType(event.EventType)
	if err != nil {
		return nil, err
	}

	return &eventsv1.NormalizedTradeEvent{
		EventId:               event.EventID,
		MasterBrokerAccountId: event.MasterBrokerAccountID,
		EventType:             eventType,
		Position:              toProtoPosition(event.Position),
		ClosedVolumeLots:      event.ClosedVolumeLots,
		FillPrice:             event.FillPrice,
		ServerTimestamp:       event.ServerTimestamp,
		ReceivedAtGateway:     event.ReceivedAtGateway,
	}, nil
}

func toProtoPosition(p domain.NormalizedPosition) *eventsv1.NormalizedPosition {
	return &eventsv1.NormalizedPosition{
		BrokerPositionId: p.BrokerPositionID,
		Symbol: &eventsv1.NormalizedSymbol{
			CanonicalCode: p.Symbol.CanonicalCode,
			AssetClass:    toProtoAssetClass(p.Symbol.AssetClass),
		},
		Direction:      toProtoDirection(p.Direction),
		VolumeLots:     p.VolumeLots,
		OpenPrice:      p.OpenPrice,
		CurrentSlPrice: p.CurrentSLPrice,
		CurrentTpPrice: p.CurrentTPPrice,
		OpenedAt:       p.OpenedAt,
	}
}

func toProtoAssetClass(a domain.AssetClass) eventsv1.AssetClass {
	switch a {
	case domain.AssetClassFX:
		return eventsv1.AssetClass_ASSET_CLASS_FX
	case domain.AssetClassIndex:
		return eventsv1.AssetClass_ASSET_CLASS_INDEX
	case domain.AssetClassCommodity:
		return eventsv1.AssetClass_ASSET_CLASS_COMMODITY
	case domain.AssetClassCrypto:
		return eventsv1.AssetClass_ASSET_CLASS_CRYPTO
	case domain.AssetClassStockCFD:
		return eventsv1.AssetClass_ASSET_CLASS_STOCK_CFD
	default:
		return eventsv1.AssetClass_ASSET_CLASS_UNSPECIFIED
	}
}

func toProtoDirection(d domain.TradeDirection) eventsv1.TradeDirection {
	switch d {
	case domain.TradeDirectionBuy:
		return eventsv1.TradeDirection_TRADE_DIRECTION_BUY
	case domain.TradeDirectionSell:
		return eventsv1.TradeDirection_TRADE_DIRECTION_SELL
	default:
		return eventsv1.TradeDirection_TRADE_DIRECTION_UNSPECIFIED
	}
}

func toProtoEventType(t domain.TradeEventType) (eventsv1.TradeEventType, error) {
	switch t {
	case domain.TradeEventPositionOpened:
		return eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_OPENED, nil
	case domain.TradeEventPositionModified:
		return eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_MODIFIED, nil
	case domain.TradeEventPositionPartiallyClosed:
		return eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_PARTIALLY_CLOSED, nil
	case domain.TradeEventPositionClosed:
		return eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_CLOSED, nil
	default:
		// Unlike the other enums (which have a sensible UNSPECIFIED
		// fallback), an unrecognized event type here means an adapter bug
		// upstream -- silently publishing TRADE_EVENT_TYPE_UNSPECIFIED would
		// hide it from the Copy Engine as a malformed, silently-dropped
		// signal instead of a loud failure here.
		return eventsv1.TradeEventType_TRADE_EVENT_TYPE_UNSPECIFIED, fmt.Errorf("tradesignals: unknown TradeEventType %q", t)
	}
}
