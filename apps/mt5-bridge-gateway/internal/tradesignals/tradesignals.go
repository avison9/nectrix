// Package tradesignals turns a domain.BrokerAdapter's StreamTradeEvents
// callback into a real publish to the trade-signals Kafka topic — the exact
// counterpart of apps/broker-adapters/internal/tradesignals (TICKET-101).
//
// Duplicated rather than imported: Go's own internal-package visibility
// rule scopes an internal/ package to importers rooted at its parent
// directory (here, apps/broker-adapters/... only) — the two are separate Go
// modules/binaries in this monorepo, same as internal/coreappclient's own
// documented reason for not being shared. The logic itself is already
// broker-agnostic (see the original's doc comment) and kept field-for-field
// identical here on purpose, so the two stay trivially diffable.
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
// topic catalog.
const Topic = "trade-signals"

// NewWriter builds a *kafka.Writer targeting Topic, keyed by
// master_broker_account_id (Hash balancer) so every event for the same
// master account lands on the same partition.
func NewWriter(brokerAddr string) *kafka.Writer {
	return &kafka.Writer{
		Addr:     kafka.TCP(brokerAddr),
		Topic:    Topic,
		Balancer: &kafka.Hash{},
	}
}

// Publisher adapts a *kafka.Writer into the
// func(context.Context, domain.NormalizedTradeEvent) error shape
// domain.BrokerAdapter.StreamTradeEvents expects as its onEvent callback —
// and, for this service, the shape internal/eabridge.Server's onTradeEvent
// constructor argument expects too.
type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(writer *kafka.Writer) *Publisher {
	return &Publisher{writer: writer}
}

// OnEvent publishes event to Topic, keyed by its MasterBrokerAccountID. A
// single failed publish never crashes the caller — the error just
// propagates back for logging.
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
		return eventsv1.TradeEventType_TRADE_EVENT_TYPE_UNSPECIFIED, fmt.Errorf("tradesignals: unknown TradeEventType %q", t)
	}
}
