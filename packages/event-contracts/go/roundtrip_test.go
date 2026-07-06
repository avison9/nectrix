package eventcontracts_test

import (
	"os"
	"testing"

	"google.golang.org/protobuf/encoding/protojson"

	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
)

// TestRoundTrip parses the fixture shared with the Java round-trip test
// (packages/event-contracts/testdata/sample_trade_event.json) and asserts
// the decoded fields, proving the Go-generated type can consume the
// canonical wire format. The Java test in packages/event-contracts/java
// parses the identical file and asserts the same values, proving both
// generated languages agree on structure without needing a live
// cross-process round trip.
func TestRoundTrip(t *testing.T) {
	raw, err := os.ReadFile("../testdata/sample_trade_event.json")
	if err != nil {
		t.Fatalf("reading fixture: %v", err)
	}

	var evt eventsv1.NormalizedTradeEvent
	if err := protojson.Unmarshal(raw, &evt); err != nil {
		t.Fatalf("unmarshaling fixture: %v", err)
	}

	if got, want := evt.GetEventId(), "evt_01HXAMPLE0000000000000001"; got != want {
		t.Errorf("EventId = %q, want %q", got, want)
	}
	if got, want := evt.GetMasterBrokerAccountId(), "bacc_master_0001"; got != want {
		t.Errorf("MasterBrokerAccountId = %q, want %q", got, want)
	}
	if got, want := evt.GetEventType(), eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_OPENED; got != want {
		t.Errorf("EventType = %v, want %v", got, want)
	}

	pos := evt.GetPosition()
	if pos == nil {
		t.Fatal("Position is nil")
	}
	if got, want := pos.GetBrokerPositionId(), "pos_0001"; got != want {
		t.Errorf("Position.BrokerPositionId = %q, want %q", got, want)
	}
	if got, want := pos.GetSymbol().GetCanonicalCode(), "EURUSD"; got != want {
		t.Errorf("Position.Symbol.CanonicalCode = %q, want %q", got, want)
	}
	if got, want := pos.GetSymbol().GetAssetClass(), eventsv1.AssetClass_ASSET_CLASS_FX; got != want {
		t.Errorf("Position.Symbol.AssetClass = %v, want %v", got, want)
	}
	if got, want := pos.GetDirection(), eventsv1.TradeDirection_TRADE_DIRECTION_BUY; got != want {
		t.Errorf("Position.Direction = %v, want %v", got, want)
	}
	if got, want := pos.GetVolumeLots(), 1.5; got != want {
		t.Errorf("Position.VolumeLots = %v, want %v", got, want)
	}
	if pos.CurrentSlPrice == nil || *pos.CurrentSlPrice != 1.08 {
		t.Errorf("Position.CurrentSlPrice = %v, want present with value 1.08", pos.CurrentSlPrice)
	}

	// Fields absent from the fixture must decode as "not present" (nil), not zero-as-present.
	if evt.ClosedVolumeLots != nil {
		t.Errorf("ClosedVolumeLots should be absent (nil), got present with value %v", *evt.ClosedVolumeLots)
	}
}
