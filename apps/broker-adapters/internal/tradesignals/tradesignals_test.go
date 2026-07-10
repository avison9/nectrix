package tradesignals

import (
	"testing"

	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
)

func TestToProto_MapsEveryFieldAndEnum(t *testing.T) {
	closedVolume := 0.5
	fillPrice := 1.0851
	sl := 1.08
	tp := 1.09

	event := domain.NormalizedTradeEvent{
		EventID:               "evt-1",
		MasterBrokerAccountID: "master-1",
		EventType:             domain.TradeEventPositionPartiallyClosed,
		Position: domain.NormalizedPosition{
			BrokerPositionID: "pos-777",
			Symbol:           domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
			Direction:        domain.TradeDirectionBuy,
			VolumeLots:       1.5,
			OpenPrice:        1.08,
			CurrentSLPrice:   &sl,
			CurrentTPPrice:   &tp,
			OpenedAt:         "2026-07-06T00:00:00Z",
		},
		ClosedVolumeLots:  &closedVolume,
		FillPrice:         &fillPrice,
		ServerTimestamp:   "2026-07-06T00:00:01Z",
		ReceivedAtGateway: "2026-07-06T00:00:02Z",
	}

	got, err := toProto(event)
	if err != nil {
		t.Fatalf("toProto() error = %v", err)
	}

	want := &eventsv1.NormalizedTradeEvent{
		EventId:               "evt-1",
		MasterBrokerAccountId: "master-1",
		EventType:             eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_PARTIALLY_CLOSED,
		Position: &eventsv1.NormalizedPosition{
			BrokerPositionId: "pos-777",
			Symbol:           &eventsv1.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: eventsv1.AssetClass_ASSET_CLASS_FX},
			Direction:        eventsv1.TradeDirection_TRADE_DIRECTION_BUY,
			VolumeLots:       1.5,
			OpenPrice:        1.08,
			CurrentSlPrice:   &sl,
			CurrentTpPrice:   &tp,
			OpenedAt:         "2026-07-06T00:00:00Z",
		},
		ClosedVolumeLots:  &closedVolume,
		FillPrice:         &fillPrice,
		ServerTimestamp:   "2026-07-06T00:00:01Z",
		ReceivedAtGateway: "2026-07-06T00:00:02Z",
	}

	if got.EventId != want.EventId ||
		got.MasterBrokerAccountId != want.MasterBrokerAccountId ||
		got.EventType != want.EventType ||
		got.ServerTimestamp != want.ServerTimestamp ||
		got.ReceivedAtGateway != want.ReceivedAtGateway ||
		*got.ClosedVolumeLots != *want.ClosedVolumeLots ||
		*got.FillPrice != *want.FillPrice {
		t.Fatalf("toProto() top-level fields = %+v, want %+v", got, want)
	}
	if got.Position.BrokerPositionId != want.Position.BrokerPositionId ||
		got.Position.Symbol.CanonicalCode != want.Position.Symbol.CanonicalCode ||
		got.Position.Symbol.AssetClass != want.Position.Symbol.AssetClass ||
		got.Position.Direction != want.Position.Direction ||
		got.Position.VolumeLots != want.Position.VolumeLots ||
		got.Position.OpenPrice != want.Position.OpenPrice ||
		*got.Position.CurrentSlPrice != *want.Position.CurrentSlPrice ||
		*got.Position.CurrentTpPrice != *want.Position.CurrentTpPrice ||
		got.Position.OpenedAt != want.Position.OpenedAt {
		t.Fatalf("toProto() Position = %+v, want %+v", got.Position, want.Position)
	}
}

func TestToProtoEventType_CoversEveryDomainValue(t *testing.T) {
	cases := []struct {
		in   domain.TradeEventType
		want eventsv1.TradeEventType
	}{
		{domain.TradeEventPositionOpened, eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_OPENED},
		{domain.TradeEventPositionModified, eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_MODIFIED},
		{domain.TradeEventPositionPartiallyClosed, eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_PARTIALLY_CLOSED},
		{domain.TradeEventPositionClosed, eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_CLOSED},
	}
	for _, c := range cases {
		got, err := toProtoEventType(c.in)
		if err != nil {
			t.Fatalf("toProtoEventType(%q) error = %v", c.in, err)
		}
		if got != c.want {
			t.Fatalf("toProtoEventType(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestToProtoEventType_UnknownValueIsARealErrorNotSilentUnspecified(t *testing.T) {
	if _, err := toProtoEventType(domain.TradeEventType("SOMETHING_NEW")); err == nil {
		t.Fatal("toProtoEventType(unknown): expected an error, got nil — an unmapped event type must never silently publish as UNSPECIFIED")
	}
}

func TestToProtoAssetClass_UnknownFallsBackToUnspecified(t *testing.T) {
	if got := toProtoAssetClass(domain.AssetClass("SOMETHING_NEW")); got != eventsv1.AssetClass_ASSET_CLASS_UNSPECIFIED {
		t.Fatalf("toProtoAssetClass(unknown) = %v, want ASSET_CLASS_UNSPECIFIED", got)
	}
}

func TestToProtoDirection_UnknownFallsBackToUnspecified(t *testing.T) {
	if got := toProtoDirection(domain.TradeDirection("SOMETHING_NEW")); got != eventsv1.TradeDirection_TRADE_DIRECTION_UNSPECIFIED {
		t.Fatalf("toProtoDirection(unknown) = %v, want TRADE_DIRECTION_UNSPECIFIED", got)
	}
}
