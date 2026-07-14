package pipeline

import (
	"strings"
	"testing"

	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

// driftKey lets tests assert on WHICH drifts fired without depending on
// diffMasterPositions'/diffFollowerPositions' map-iteration-driven (hence
// non-deterministic) result order.
type driftKey struct {
	driftType        int
	brokerPositionID string
}

func masterDriftKeys(drifts []masterDrift) map[driftKey]bool {
	keys := make(map[driftKey]bool, len(drifts))
	for _, d := range drifts {
		keys[driftKey{int(d.driftType), d.brokerPositionID}] = true
	}
	return keys
}

func followerDriftKeys(drifts []followerDrift) map[driftKey]bool {
	keys := make(map[driftKey]bool, len(drifts))
	for _, d := range drifts {
		keys[driftKey{int(d.driftType), d.brokerPositionID}] = true
	}
	return keys
}

func TestDiffMasterPositions_NoDiff_NoDrifts(t *testing.T) {
	actual := []domain.NormalizedPosition{
		{BrokerPositionID: "pos-1", VolumeLots: 1.0, CurrentSLPrice: floatPtrRT(1.09), CurrentTPPrice: floatPtrRT(1.11)},
	}
	believed := map[string]believedMasterPosition{
		"pos-1": {brokerPositionID: "pos-1", eventType: "POSITION_OPENED", volumeLots: 1.0, slPrice: floatPtrRT(1.09), tpPrice: floatPtrRT(1.11)},
	}

	drifts := diffMasterPositions(actual, believed)
	if len(drifts) != 0 {
		t.Fatalf("got %d drifts, want 0 (fully synced) -- %+v", len(drifts), drifts)
	}
}

func TestDiffMasterPositions_MissedOpen(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "pos-1", VolumeLots: 1.0}}
	drifts := diffMasterPositions(actual, map[string]believedMasterPosition{})

	got := masterDriftKeys(drifts)
	want := driftKey{int(masterDriftMissedOpen), "pos-1"}
	if !got[want] {
		t.Fatalf("drifts = %+v, want to include %+v", drifts, want)
	}
}

func TestDiffMasterPositions_MissedClose(t *testing.T) {
	believed := map[string]believedMasterPosition{
		"pos-1": {brokerPositionID: "pos-1", eventType: "POSITION_OPENED", volumeLots: 1.0},
	}
	drifts := diffMasterPositions(nil, believed)

	got := masterDriftKeys(drifts)
	want := driftKey{int(masterDriftMissedClose), "pos-1"}
	if !got[want] {
		t.Fatalf("drifts = %+v, want to include %+v", drifts, want)
	}
}

func TestDiffMasterPositions_MissedPartialClose(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "pos-1", VolumeLots: 0.5}}
	believed := map[string]believedMasterPosition{
		"pos-1": {brokerPositionID: "pos-1", eventType: "POSITION_OPENED", volumeLots: 1.0},
	}
	drifts := diffMasterPositions(actual, believed)

	got := masterDriftKeys(drifts)
	want := driftKey{int(masterDriftMissedPartialClose), "pos-1"}
	if !got[want] {
		t.Fatalf("drifts = %+v, want to include %+v", drifts, want)
	}
}

func TestDiffMasterPositions_MissedModify(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "pos-1", VolumeLots: 1.0, CurrentSLPrice: floatPtrRT(1.08)}}
	believed := map[string]believedMasterPosition{
		"pos-1": {brokerPositionID: "pos-1", eventType: "POSITION_OPENED", volumeLots: 1.0, slPrice: floatPtrRT(1.09)},
	}
	drifts := diffMasterPositions(actual, believed)

	got := masterDriftKeys(drifts)
	want := driftKey{int(masterDriftMissedModify), "pos-1"}
	if !got[want] {
		t.Fatalf("drifts = %+v, want to include %+v", drifts, want)
	}
}

// Partial close and modify are the only two drift types that can co-occur
// on the same position (close/partial-close are structurally mutually
// exclusive).
func TestDiffMasterPositions_PartialCloseAndModify_BothFire(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "pos-1", VolumeLots: 0.5, CurrentSLPrice: floatPtrRT(1.08)}}
	believed := map[string]believedMasterPosition{
		"pos-1": {brokerPositionID: "pos-1", eventType: "POSITION_OPENED", volumeLots: 1.0, slPrice: floatPtrRT(1.09)},
	}
	drifts := diffMasterPositions(actual, believed)

	got := masterDriftKeys(drifts)
	if !got[driftKey{int(masterDriftMissedPartialClose), "pos-1"}] {
		t.Fatalf("drifts = %+v, want to include MISSED_PARTIAL_CLOSE", drifts)
	}
	if !got[driftKey{int(masterDriftMissedModify), "pos-1"}] {
		t.Fatalf("drifts = %+v, want to include MISSED_MODIFY", drifts)
	}
	if len(drifts) != 2 {
		t.Fatalf("got %d drifts, want exactly 2", len(drifts))
	}
}

func TestDiffMasterPositions_UnsupportedVolumeIncrease_DetectedOnly(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "pos-1", VolumeLots: 2.0}}
	believed := map[string]believedMasterPosition{
		"pos-1": {brokerPositionID: "pos-1", eventType: "POSITION_OPENED", volumeLots: 1.0},
	}
	drifts := diffMasterPositions(actual, believed)

	got := masterDriftKeys(drifts)
	want := driftKey{int(masterDriftUnsupportedIncrease), "pos-1"}
	if !got[want] {
		t.Fatalf("drifts = %+v, want to include %+v", drifts, want)
	}
}

func TestSynthesizeMasterEvent_MissedOpen_FullPositionClone(t *testing.T) {
	actual := domain.NormalizedPosition{
		BrokerPositionID: "pos-1",
		Symbol:           domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		Direction:        domain.TradeDirectionBuy,
		VolumeLots:       1.5,
		OpenPrice:        1.2000,
		OpenedAt:         "2026-01-01T00:00:00Z",
	}
	event := synthesizeMasterEvent("master-1", masterDrift{driftType: masterDriftMissedOpen, brokerPositionID: "pos-1", actual: &actual})

	if event.EventType != domain.TradeEventPositionOpened {
		t.Fatalf("EventType = %v, want POSITION_OPENED", event.EventType)
	}
	if event.Position.VolumeLots != 1.5 || event.Position.OpenPrice != 1.2000 {
		t.Fatalf("Position = %+v, want a full clone of actual (volume 1.5, openPrice 1.2)", event.Position)
	}
	if event.ServerTimestamp != "2026-01-01T00:00:00Z" {
		t.Fatalf("ServerTimestamp = %q, want actual's OpenedAt", event.ServerTimestamp)
	}
}

// The critical correctness detail from planning: a synthesized MODIFY event
// must carry the FULL actual position (including volume), not a sparse
// SL/TP-only struct -- otherwise the next tick's belief-derivation would
// silently corrupt the volume belief.
func TestSynthesizeMasterEvent_MissedModify_CarriesFullVolumeNotJustSLTP(t *testing.T) {
	actual := domain.NormalizedPosition{BrokerPositionID: "pos-1", VolumeLots: 3.0, CurrentSLPrice: floatPtrRT(1.08)}
	event := synthesizeMasterEvent("master-1", masterDrift{driftType: masterDriftMissedModify, brokerPositionID: "pos-1", actual: &actual})

	if event.Position.VolumeLots != 3.0 {
		t.Fatalf("Position.VolumeLots = %v, want 3.0 (full clone, not a sparse SL/TP-only struct)", event.Position.VolumeLots)
	}
}

func TestDiffFollowerPositions_NoDiff_NoDrifts(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "fpos-1", VolumeLots: 1.0}}
	believed := map[string]believedFollowerPosition{
		"fpos-1": {followerBrokerPositionID: "fpos-1", currentOpenVolumeLots: 1.0},
	}
	drifts := diffFollowerPositions(actual, believed)
	if len(drifts) != 0 {
		t.Fatalf("got %d drifts, want 0 -- %+v", len(drifts), drifts)
	}
}

func TestDiffFollowerPositions_Closed(t *testing.T) {
	believed := map[string]believedFollowerPosition{
		"fpos-1": {followerBrokerPositionID: "fpos-1", currentOpenVolumeLots: 1.0},
	}
	drifts := diffFollowerPositions(nil, believed)

	got := followerDriftKeys(drifts)
	if !got[driftKey{int(followerDriftClosed), "fpos-1"}] {
		t.Fatalf("drifts = %+v, want CLOSED for fpos-1", drifts)
	}
}

func TestDiffFollowerPositions_PartialClose(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "fpos-1", VolumeLots: 0.4}}
	believed := map[string]believedFollowerPosition{
		"fpos-1": {followerBrokerPositionID: "fpos-1", currentOpenVolumeLots: 1.0},
	}
	drifts := diffFollowerPositions(actual, believed)

	got := followerDriftKeys(drifts)
	if !got[driftKey{int(followerDriftPartialClose), "fpos-1"}] {
		t.Fatalf("drifts = %+v, want PARTIAL_CLOSE for fpos-1", drifts)
	}
}

func TestDiffFollowerPositions_UnsupportedIncrease(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "fpos-1", VolumeLots: 2.0}}
	believed := map[string]believedFollowerPosition{
		"fpos-1": {followerBrokerPositionID: "fpos-1", currentOpenVolumeLots: 1.0},
	}
	drifts := diffFollowerPositions(actual, believed)

	got := followerDriftKeys(drifts)
	if !got[driftKey{int(followerDriftUnsupportedIncrease), "fpos-1"}] {
		t.Fatalf("drifts = %+v, want UNSUPPORTED_INCREASE for fpos-1", drifts)
	}
}

func TestDiffFollowerPositions_UnmatchedPosition(t *testing.T) {
	actual := []domain.NormalizedPosition{{BrokerPositionID: "fpos-1", VolumeLots: 1.0}}
	drifts := diffFollowerPositions(actual, map[string]believedFollowerPosition{})

	got := followerDriftKeys(drifts)
	if !got[driftKey{int(followerDriftUnmatchedPosition), "fpos-1"}] {
		t.Fatalf("drifts = %+v, want UNMATCHED_POSITION for fpos-1", drifts)
	}
}

func TestMatchUnmatchedFollowerPositions_UniqueMatch(t *testing.T) {
	pendingID := uuid.New()
	actual := []domain.NormalizedPosition{
		{BrokerPositionID: "fpos-1", Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD"}, Direction: domain.TradeDirectionBuy, VolumeLots: 1.0},
	}
	candidates := []pendingCandidate{
		{copiedTradeID: pendingID, computedVolumeLots: 1.0, symbol: "EURUSD", direction: "BUY"},
	}

	matches := matchUnmatchedFollowerPositions(actual, candidates)
	c, ok := matches["fpos-1"]
	if !ok || c.copiedTradeID != pendingID {
		t.Fatalf("matches = %+v, want fpos-1 uniquely matched to %v", matches, pendingID)
	}
}

func TestMatchUnmatchedFollowerPositions_AmbiguousCandidates_NoMatch(t *testing.T) {
	actual := []domain.NormalizedPosition{
		{BrokerPositionID: "fpos-1", Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD"}, Direction: domain.TradeDirectionBuy, VolumeLots: 1.0},
	}
	// Two PENDING rows both plausibly match the same actual position --
	// ambiguous, must not auto-finalize either.
	candidates := []pendingCandidate{
		{copiedTradeID: uuid.New(), computedVolumeLots: 1.0, symbol: "EURUSD", direction: "BUY"},
		{copiedTradeID: uuid.New(), computedVolumeLots: 1.0, symbol: "EURUSD", direction: "BUY"},
	}

	matches := matchUnmatchedFollowerPositions(actual, candidates)
	if _, ok := matches["fpos-1"]; ok {
		t.Fatalf("matches = %+v, want no match for fpos-1 (ambiguous: 2 candidates)", matches)
	}
}

func TestMatchUnmatchedFollowerPositions_CandidateMatchesMultiplePositions_NoMatch(t *testing.T) {
	// One PENDING candidate plausibly matches TWO different actual
	// positions -- ambiguous in the other direction, must not auto-finalize.
	actual := []domain.NormalizedPosition{
		{BrokerPositionID: "fpos-1", Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD"}, Direction: domain.TradeDirectionBuy, VolumeLots: 1.0},
		{BrokerPositionID: "fpos-2", Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD"}, Direction: domain.TradeDirectionBuy, VolumeLots: 1.0},
	}
	candidates := []pendingCandidate{
		{copiedTradeID: uuid.New(), computedVolumeLots: 1.0, symbol: "EURUSD", direction: "BUY"},
	}

	matches := matchUnmatchedFollowerPositions(actual, candidates)
	if len(matches) != 0 {
		t.Fatalf("matches = %+v, want none (the single candidate matches 2 positions, ambiguous)", matches)
	}
}

func TestMatchUnmatchedFollowerPositions_DifferentSymbolOrDirection_NoMatch(t *testing.T) {
	actual := []domain.NormalizedPosition{
		{BrokerPositionID: "fpos-1", Symbol: domain.NormalizedSymbol{CanonicalCode: "GBPUSD"}, Direction: domain.TradeDirectionSell, VolumeLots: 1.0},
	}
	candidates := []pendingCandidate{
		{copiedTradeID: uuid.New(), computedVolumeLots: 1.0, symbol: "EURUSD", direction: "BUY"},
	}

	matches := matchUnmatchedFollowerPositions(actual, candidates)
	if len(matches) != 0 {
		t.Fatalf("matches = %+v, want none (symbol/direction don't match)", matches)
	}
}

// AC3: drift detail strings must carry enough detail to distinguish stream
// drop from adapter bug -- both the actual and believed/ledger values.
func TestMasterDriftEventTypeAndDetail_DetailStrings(t *testing.T) {
	cases := []struct {
		name    string
		drift   masterDrift
		want    eventsv1.ReconciliationDriftType
		wantSub []string
	}{
		{
			name:    "MissedOpen",
			drift:   masterDrift{driftType: masterDriftMissedOpen, actual: &domain.NormalizedPosition{VolumeLots: 1.50}},
			want:    eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_OPEN,
			wantSub: []string{"1.50"},
		},
		{
			name:    "MissedClose",
			drift:   masterDrift{driftType: masterDriftMissedClose, believed: believedMasterPosition{volumeLots: 2.00}},
			want:    eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_CLOSE,
			wantSub: []string{"2.00"},
		},
		{
			name:    "MissedPartialClose",
			drift:   masterDrift{driftType: masterDriftMissedPartialClose, actual: &domain.NormalizedPosition{VolumeLots: 0.50}, believed: believedMasterPosition{volumeLots: 1.00}},
			want:    eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_MISSED_PARTIAL_CLOSE,
			wantSub: []string{"0.50", "1.00"},
		},
		{
			name:    "UnsupportedIncrease",
			drift:   masterDrift{driftType: masterDriftUnsupportedIncrease, actual: &domain.NormalizedPosition{VolumeLots: 2.00}, believed: believedMasterPosition{volumeLots: 1.00}},
			want:    eventsv1.ReconciliationDriftType_RECONCILIATION_DRIFT_TYPE_UNSUPPORTED_VOLUME_INCREASE,
			wantSub: []string{"2.00", "1.00"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			gotType, detail := masterDriftEventTypeAndDetail(c.drift)
			if gotType != c.want {
				t.Fatalf("drift type = %v, want %v", gotType, c.want)
			}
			for _, sub := range c.wantSub {
				if !strings.Contains(detail, sub) {
					t.Fatalf("detail = %q, want it to contain %q", detail, sub)
				}
			}
		})
	}
}

func floatPtrRT(v float64) *float64 { return &v }
