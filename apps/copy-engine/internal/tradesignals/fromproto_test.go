package tradesignals_test

import (
	"testing"

	"github.com/avison9/nectrix/copy-engine/internal/tradesignals"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
)

func TestFromProto_FullEvent_MapsEveryField(t *testing.T) {
	slPrice := 1.0950
	tpPrice := 1.1100
	closedVolume := 0.5
	fillPrice := 1.10005

	proto := &eventsv1.NormalizedTradeEvent{
		EventId:               "evt-1",
		MasterBrokerAccountId: "master-acct-1",
		EventType:             eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_OPENED,
		Position: &eventsv1.NormalizedPosition{
			BrokerPositionId: "pos-1",
			Symbol:           &eventsv1.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: eventsv1.AssetClass_ASSET_CLASS_FX},
			Direction:        eventsv1.TradeDirection_TRADE_DIRECTION_BUY,
			VolumeLots:       2.5,
			OpenPrice:        1.1000,
			CurrentSlPrice:   &slPrice,
			CurrentTpPrice:   &tpPrice,
			OpenedAt:         "2026-07-13T00:00:00Z",
		},
		ClosedVolumeLots:  &closedVolume,
		FillPrice:         &fillPrice,
		ServerTimestamp:   "2026-07-13T00:00:01Z",
		ReceivedAtGateway: "2026-07-13T00:00:02Z",
	}

	got := tradesignals.FromProto(proto)

	want := domain.NormalizedTradeEvent{
		EventID:               "evt-1",
		MasterBrokerAccountID: "master-acct-1",
		EventType:             domain.TradeEventPositionOpened,
		Position: domain.NormalizedPosition{
			BrokerPositionID: "pos-1",
			Symbol:           domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
			Direction:        domain.TradeDirectionBuy,
			VolumeLots:       2.5,
			OpenPrice:        1.1000,
			CurrentSLPrice:   &slPrice,
			CurrentTPPrice:   &tpPrice,
			OpenedAt:         "2026-07-13T00:00:00Z",
		},
		ClosedVolumeLots:  &closedVolume,
		FillPrice:         &fillPrice,
		ServerTimestamp:   "2026-07-13T00:00:01Z",
		ReceivedAtGateway: "2026-07-13T00:00:02Z",
	}

	if got.EventID != want.EventID || got.MasterBrokerAccountID != want.MasterBrokerAccountID || got.EventType != want.EventType {
		t.Fatalf("got %+v, want %+v", got, want)
	}
	if got.Position != want.Position {
		t.Fatalf("got Position %+v, want %+v", got.Position, want.Position)
	}
	if *got.ClosedVolumeLots != *want.ClosedVolumeLots || *got.FillPrice != *want.FillPrice {
		t.Fatalf("got ClosedVolumeLots/FillPrice %v/%v, want %v/%v", got.ClosedVolumeLots, got.FillPrice, want.ClosedVolumeLots, want.FillPrice)
	}
	if got.ServerTimestamp != want.ServerTimestamp || got.ReceivedAtGateway != want.ReceivedAtGateway {
		t.Fatalf("got timestamps %q/%q, want %q/%q", got.ServerTimestamp, got.ReceivedAtGateway, want.ServerTimestamp, want.ReceivedAtGateway)
	}
}

func TestFromProto_NilOptionalFields_NoPanic(t *testing.T) {
	proto := &eventsv1.NormalizedTradeEvent{
		EventId:               "evt-2",
		MasterBrokerAccountId: "master-acct-2",
		EventType:             eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_CLOSED,
		Position: &eventsv1.NormalizedPosition{
			Symbol: &eventsv1.NormalizedSymbol{CanonicalCode: "GBPUSD", AssetClass: eventsv1.AssetClass_ASSET_CLASS_FX},
		},
	}

	got := tradesignals.FromProto(proto)
	if got.Position.CurrentSLPrice != nil || got.Position.CurrentTPPrice != nil {
		t.Fatalf("expected nil SL/TP prices to stay nil, got %+v", got.Position)
	}
	if got.ClosedVolumeLots != nil || got.FillPrice != nil {
		t.Fatalf("expected nil ClosedVolumeLots/FillPrice to stay nil, got %+v", got)
	}
	if got.EventType != domain.TradeEventPositionClosed {
		t.Fatalf("EventType = %q, want POSITION_CLOSED", got.EventType)
	}
}

func TestFromProto_UnrecognizedEnums_MapToZeroValue(t *testing.T) {
	proto := &eventsv1.NormalizedTradeEvent{
		EventType: eventsv1.TradeEventType_TRADE_EVENT_TYPE_UNSPECIFIED,
		Position: &eventsv1.NormalizedPosition{
			Symbol:    &eventsv1.NormalizedSymbol{AssetClass: eventsv1.AssetClass_ASSET_CLASS_UNSPECIFIED},
			Direction: eventsv1.TradeDirection_TRADE_DIRECTION_UNSPECIFIED,
		},
	}

	got := tradesignals.FromProto(proto)
	if got.EventType != "" || got.Position.Symbol.AssetClass != "" || got.Position.Direction != "" {
		t.Fatalf("expected unrecognized enums to map to empty string, got EventType=%q AssetClass=%q Direction=%q",
			got.EventType, got.Position.Symbol.AssetClass, got.Position.Direction)
	}
}
