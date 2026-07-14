//go:build integration

// TICKET-106's own new tests, covering exactly the approved plan's
// testing-strategy table (AC1-AC4). Given live cTrader/MT5 demo trades
// can't be automated in this environment, these tests prove the real wire
// protocol against fake HTTP servers standing in for broker-adapters'/
// mt5-bridge-gateway's new internal PlaceOrder/GetAccountSnapshot routes
// (see remoteadapter.HTTPClient, which both real services' internalapi
// packages independently implement the same wire shape for) -- what's
// proven here is the real routing, real idempotency, real sizing/risk-guard
// math, and real SL-TP/slippage arithmetic, not a live broker fill.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/httpapi"
	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
)

const dispatchTestSharedSecret = "test-internal-service-token"

// AC1: "all 4 broker-pair combinations are sized/capped/dispatched via the
// real wire protocol." Each subtest registers the SAME fake server under
// both the CTRADER and MT5 router keys (remoteadapter.HTTPClient's wire
// shape is identical for both -- only the path prefix/platform differ),
// proving master and follower can be on entirely different broker types in
// one relationship, which is the whole point of this ticket's cross-service
// design.
func TestAC1_AllBrokerPairCombinations_SizedAndDispatchedViaRealWireProtocol(t *testing.T) {
	combos := []struct {
		name     string
		master   domain.BrokerType
		follower domain.BrokerType
	}{
		{"CTraderToCTrader", domain.BrokerTypeCTrader, domain.BrokerTypeCTrader},
		{"MT5ToMT5", domain.BrokerTypeMT5, domain.BrokerTypeMT5},
		{"CTraderToMT5", domain.BrokerTypeCTrader, domain.BrokerTypeMT5},
		{"MT5ToCTrader", domain.BrokerTypeMT5, domain.BrokerTypeCTrader},
	}

	for _, combo := range combos {
		t.Run(combo.name, func(t *testing.T) {
			ctx := context.Background()
			pool := newTestPool(t)
			deduper := newTestDeduper(t)
			topic := createDedicatedTopic(t)
			writer := newWriter(topic)
			t.Cleanup(func() { writer.Close() })

			masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, combo.master, combo.follower, "SAME")

			fake := newFakeBrokerService()
			fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
			fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
			router := buildDispatchRouter(t, fake)
			server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

			brokerPositionID := "ac1-" + combo.name + "-" + uuid.NewString()
			serverTimestamp := time.Now().UTC().Format(time.RFC3339)
			postInjectWithOpenPrice(t, server, brokerPositionID, serverTimestamp, 2.0, 1.2000)

			assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)

			if got := fake.placeOrderCallCount(followerAccountID); got != 1 {
				t.Fatalf("PlaceOrder called %d times against the follower's real wire route, want exactly 1", got)
			}
			calls := fake.callsFor(followerAccountID)
			if calls[0].VolumeLots != 2.0 {
				t.Fatalf("PlaceOrder VolumeLots = %v, want 2.0 (MULTIPLIER x1.0 of master's 2.0 lots)", calls[0].VolumeLots)
			}
			if calls[0].Direction != domain.TradeDirectionBuy {
				t.Fatalf("PlaceOrder Direction = %v, want BUY (SAME copy direction, master signal is BUY)", calls[0].Direction)
			}
			if calls[0].Symbol.CanonicalCode != "EURUSD" {
				t.Fatalf("PlaceOrder Symbol = %q, want EURUSD", calls[0].Symbol.CanonicalCode)
			}

			var status string
			var computedVolume float64
			err := pool.QueryRow(ctx, `SELECT status, computed_volume_lots FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status, &computedVolume)
			if err != nil {
				t.Fatalf("query copied_trades: %v", err)
			}
			if status != "FILLED" {
				t.Fatalf("copied_trades.status = %q, want FILLED", status)
			}
			if computedVolume != 2.0 {
				t.Fatalf("copied_trades.computed_volume_lots = %v, want 2.0", computedVolume)
			}
		})
	}
}

// AC2: "sizing_method_snapshot is detailed enough to reproduce
// computed_volume_lots by hand." Deserializes the PERSISTED JSON alone
// (never the in-memory values from this test run) and recomputes
// ComputeLotSize from it, proving the snapshot is self-contained --
// errorFXProvider below additionally proves this specific MULTIPLIER
// relationship never depended on a live FX lookup to reproduce.
func TestAC2_SizingMethodSnapshot_ReproducesComputedVolumeFromJSONAlone(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	postInjectWithOpenPrice(t, server, "ac2-"+uuid.NewString(), time.Now().UTC().Format(time.RFC3339), 3.0, 1.2000)

	var computedVolume float64
	var snapshotJSON []byte
	err := pool.QueryRow(ctx, `SELECT computed_volume_lots, sizing_method_snapshot FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&computedVolume, &snapshotJSON)
	if err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}

	var snap sizingSnapshotJSON
	if err := json.Unmarshal(snapshotJSON, &snap); err != nil {
		t.Fatalf("unmarshal sizing_method_snapshot: %v", err)
	}

	recomputed, err := moneymgmt.ComputeLotSize(ctx, moneymgmt.Input{
		Profile:         snap.Profile,
		MasterPosition:  snap.MasterPosition,
		MasterAccount:   snap.MasterAccountSnapshot,
		FollowerAccount: snap.FollowerAccountSnapshot,
		SymbolSpec:      snap.FollowerSymbolSpec,
	}, errorFXProvider{})
	if err != nil {
		t.Fatalf("recompute ComputeLotSize from persisted snapshot alone: %v", err)
	}
	if recomputed.Rejected {
		t.Fatalf("recompute unexpectedly rejected: %s", recomputed.RejectReason)
	}
	if !approxEqual(recomputed.NormalizedLots, computedVolume) {
		t.Fatalf("recomputed volume %v != persisted computed_volume_lots %v -- sizing_method_snapshot is not reproducible", recomputed.NormalizedLots, computedVolume)
	}
	if !approxEqual(snap.RiskGuard.FinalVolume, computedVolume) {
		t.Fatalf("persisted riskGuard.finalVolume %v != computed_volume_lots %v", snap.RiskGuard.FinalVolume, computedVolume)
	}
}

// AC3: "a duplicate signal results in no second copied_trades row and no
// second real broker-side PlaceOrder call" -- the honest, testable proof of
// this ticket's claim -> place -> finalize idempotency redesign (see
// dispatch.go's own doc comment): the durable Postgres claim, not just
// dedupadapter's Redis TTL, is what prevents the second PlaceOrder call.
func TestAC3_DuplicateSignal_ExactlyOneCopiedTradesRowAndOnePlaceOrderCall(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))
	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "ac3-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	body, err := json.Marshal(stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  serverTimestamp,
		VolumeLots:       1.0,
		OpenPrice:        1.2000,
	})
	if err != nil {
		t.Fatalf("marshal inject params: %v", err)
	}

	const concurrency = 20
	var ready sync.WaitGroup
	ready.Add(concurrency)
	start := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(concurrency)
	for i := 0; i < concurrency; i++ {
		go func() {
			defer wg.Done()
			ready.Done()
			<-start
			resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", bytes.NewReader(body))
			if err != nil {
				t.Errorf("POST inject: %v", err)
				return
			}
			resp.Body.Close()
		}()
	}
	ready.Wait()
	close(start)
	wg.Wait()

	assertTradeSignalRowCount(t, ctx, pool, masterAccountID, brokerPositionID, 1)

	var copiedTradesCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&copiedTradesCount); err != nil {
		t.Fatalf("query copied_trades count: %v", err)
	}
	if copiedTradesCount != 1 {
		t.Fatalf("copied_trades row count = %d, want 1", copiedTradesCount)
	}

	if got := fake.placeOrderCallCount(followerAccountID); got != 1 {
		t.Fatalf("PlaceOrder called %d times, want exactly 1 -- the durable Postgres claim must prevent a second real broker-side order", got)
	}
}

// AC4: "slippage is recorded correctly for a deliberate price move" --
// docs/08 §8.5's slippage_pips = |follower_fill_price - master_open_price| /
// follower_pip_size, computed by hand here against a fake follower fill
// price deliberately different from the injected master open price.
func TestAC4_SlippageRecordedForDeliberatePriceMove(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	deduper := newTestDeduper(t)
	topic := createDedicatedTopic(t)
	writer := newWriter(topic)
	t.Cleanup(func() { writer.Close() })

	masterAccountID, followerAccountID, relationshipID := seedDispatchFixture(t, ctx, pool, domain.BrokerTypeCTrader, domain.BrokerTypeCTrader, "SAME")

	fake := newFakeBrokerService()
	fake.setSnapshot(masterAccountID, testAccountSnapshot(masterAccountID))
	fake.setSnapshot(followerAccountID, testAccountSnapshot(followerAccountID))

	const masterOpenPrice = 1.20000
	const followerFilledPrice = 1.20037 // deliberately different from the master's open price
	fake.setFilledPrice(followerAccountID, followerFilledPrice)

	router := buildDispatchRouter(t, fake)
	server := buildDispatchTestServer(t, ctx, pool, deduper, writer, router, masterAccountID)

	brokerPositionID := "ac4-" + uuid.NewString()
	serverTimestamp := time.Now().UTC().Format(time.RFC3339)
	postInjectWithOpenPrice(t, server, brokerPositionID, serverTimestamp, 1.0, masterOpenPrice)

	var status string
	var slippagePips *float64
	err := pool.QueryRow(ctx, `SELECT status, slippage_pips FROM copied_trades WHERE copy_relationship_id = $1`, relationshipID).Scan(&status, &slippagePips)
	if err != nil {
		t.Fatalf("query copied_trades: %v", err)
	}
	if status != "FILLED" {
		t.Fatalf("copied_trades.status = %q, want FILLED", status)
	}
	if slippagePips == nil {
		t.Fatalf("slippage_pips is NULL, want a real recorded value")
	}

	const followerPipSize = 0.0001 // per seedDispatchFixture's EURUSD symbol_mappings row
	want := math.Abs(followerFilledPrice-masterOpenPrice) / followerPipSize
	if !approxEqual(*slippagePips, want) {
		t.Fatalf("slippage_pips = %v, want %v (docs/08 §8.5, computed by hand)", *slippagePips, want)
	}
}

// ---- shared dispatch-test wiring ----

// sizingSnapshotJSON mirrors just enough of dispatch.go's
// buildSizingMethodSnapshot output shape to recompute AC2's volume from the
// persisted JSON alone.
type sizingSnapshotJSON struct {
	Profile                 moneymgmt.Profile         `json:"profile"`
	MasterAccountSnapshot   domain.AccountSnapshot    `json:"masterAccountSnapshot"`
	FollowerAccountSnapshot domain.AccountSnapshot    `json:"followerAccountSnapshot"`
	MasterPosition          domain.NormalizedPosition `json:"masterPosition"`
	FollowerSymbolSpec      domain.SymbolSpec         `json:"followerSymbolSpec"`
	RiskGuard               struct {
		FinalVolume  float64 `json:"finalVolume"`
		Rejected     bool    `json:"rejected"`
		RejectReason string  `json:"rejectReason"`
	} `json:"riskGuard"`
}

// errorFXProvider deliberately fails any FX lookup -- used only in AC2's
// recompute, where the relationship's MULTIPLIER method never needs FX, so
// a call reaching this would itself be a bug worth surfacing loudly rather
// than silently returning some placeholder rate.
type errorFXProvider struct{}

func (errorFXProvider) Rate(ctx context.Context, from, to string) (float64, error) {
	return 0, fmt.Errorf("errorFXProvider: unexpected FX rate lookup for %s->%s", from, to)
}

func approxEqual(a, b float64) bool {
	const epsilon = 1e-6
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < epsilon
}

func testAccountSnapshot(brokerAccountID string) domain.AccountSnapshot {
	return domain.AccountSnapshot{
		BrokerAccountID: brokerAccountID,
		Currency:        "USD",
		Balance:         10000,
		Equity:          10000,
		UsedMargin:      0,
		FreeMargin:      10000,
		AsOf:            time.Now().UTC().Format(time.RFC3339),
	}
}

// fakePlaceOrderWireRequest/fakePlaceOrderWireResult mirror the exact wire
// shapes remoteadapter.HTTPClient sends/expects -- duplicated here (rather
// than imported, since they're unexported in that package) because this
// fake server stands in for the REAL, independent broker-adapters/
// mt5-bridge-gateway processes, which each independently define the same
// shape on their own side of the wire.
type fakePlaceOrderWireRequest struct {
	Platform string                        `json:"platform"`
	Order    domain.NormalizedOrderRequest `json:"order"`
}

type fakePlaceOrderWireResult struct {
	Success          bool     `json:"success"`
	BrokerPositionID string   `json:"brokerPositionId,omitempty"`
	FilledPrice      *float64 `json:"filledPrice,omitempty"`
	RejectReason     string   `json:"rejectReason,omitempty"`
}

// fakeModifyRequest/fakeCloseRequest mirror remoteadapter.HTTPClient's
// TICKET-107 wire shapes for ModifyPosition/ClosePosition.
type fakeModifyRequest struct {
	Platform string   `json:"platform"`
	SLPrice  *float64 `json:"slPrice"`
	TPPrice  *float64 `json:"tpPrice"`
}

type fakeCloseRequest struct {
	Platform   string   `json:"platform"`
	VolumeLots *float64 `json:"volumeLots,omitempty"`
}

// fakeModifyCall/fakeCloseCall record what a ModifyPosition/ClosePosition
// call to the fake service actually carried, so tests can assert against it.
type fakeModifyCall struct {
	PositionID string
	SLPrice    *float64
	TPPrice    *float64
}

type fakeCloseCall struct {
	PositionID string
	VolumeLots *float64 // nil = full close
}

// fakeBrokerService stands in for BOTH apps/broker-adapters' and
// apps/mt5-bridge-gateway's new internal PlaceOrder/GetAccountSnapshot
// routes -- one fake server serves both /internal/ctrader and /internal/mt
// prefixes, since TICKET-106 deliberately designed both services' wire
// shape identically (see remoteadapter.HTTPClient). Records every
// PlaceOrder call it receives, keyed by brokerAccountID, so AC3/AC4 can
// assert against real recorded HTTP calls, not an in-process stub.
type fakeBrokerService struct {
	mu                  sync.Mutex
	snapshots           map[string]domain.AccountSnapshot
	filledPriceByAcct   map[string]float64
	placeOrderCalls     map[string][]domain.NormalizedOrderRequest
	modifyPositionCalls map[string][]fakeModifyCall
	closePositionCalls  map[string][]fakeCloseCall
}

func newFakeBrokerService() *fakeBrokerService {
	return &fakeBrokerService{
		snapshots:           make(map[string]domain.AccountSnapshot),
		filledPriceByAcct:   make(map[string]float64),
		placeOrderCalls:     make(map[string][]domain.NormalizedOrderRequest),
		modifyPositionCalls: make(map[string][]fakeModifyCall),
		closePositionCalls:  make(map[string][]fakeCloseCall),
	}
}

func (f *fakeBrokerService) setSnapshot(brokerAccountID string, snapshot domain.AccountSnapshot) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.snapshots[brokerAccountID] = snapshot
}

func (f *fakeBrokerService) setFilledPrice(brokerAccountID string, price float64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.filledPriceByAcct[brokerAccountID] = price
}

func (f *fakeBrokerService) placeOrderCallCount(brokerAccountID string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.placeOrderCalls[brokerAccountID])
}

func (f *fakeBrokerService) callsFor(brokerAccountID string) []domain.NormalizedOrderRequest {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]domain.NormalizedOrderRequest(nil), f.placeOrderCalls[brokerAccountID]...)
}

func (f *fakeBrokerService) modifyPositionCallCount(brokerAccountID string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.modifyPositionCalls[brokerAccountID])
}

func (f *fakeBrokerService) modifyPositionCallsFor(brokerAccountID string) []fakeModifyCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]fakeModifyCall(nil), f.modifyPositionCalls[brokerAccountID]...)
}

func (f *fakeBrokerService) closePositionCallCount(brokerAccountID string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.closePositionCalls[brokerAccountID])
}

func (f *fakeBrokerService) closePositionCallsFor(brokerAccountID string) []fakeCloseCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]fakeCloseCall(nil), f.closePositionCalls[brokerAccountID]...)
}

const fakeDefaultFilledPrice = 1.10000

func (f *fakeBrokerService) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /internal/ctrader/accounts/{id}/snapshot", f.handleSnapshot)
	mux.HandleFunc("POST /internal/ctrader/accounts/{id}/orders", f.handlePlaceOrder)
	mux.HandleFunc("POST /internal/ctrader/accounts/{id}/positions/{positionId}/modify", f.handleModifyPosition)
	mux.HandleFunc("POST /internal/ctrader/accounts/{id}/positions/{positionId}/close", f.handleClosePosition)
	mux.HandleFunc("GET /internal/mt/accounts/{id}/snapshot", f.handleSnapshot)
	mux.HandleFunc("POST /internal/mt/accounts/{id}/orders", f.handlePlaceOrder)
	mux.HandleFunc("POST /internal/mt/accounts/{id}/positions/{positionId}/modify", f.handleModifyPosition)
	mux.HandleFunc("POST /internal/mt/accounts/{id}/positions/{positionId}/close", f.handleClosePosition)
	return f.requireToken(mux)
}

func (f *fakeBrokerService) requireToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Internal-Service-Token") != dispatchTestSharedSecret {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (f *fakeBrokerService) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	f.mu.Lock()
	snapshot, ok := f.snapshots[id]
	f.mu.Unlock()
	if !ok {
		http.Error(w, "unknown account", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(snapshot)
}

func (f *fakeBrokerService) handlePlaceOrder(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var wire fakePlaceOrderWireRequest
	if err := json.NewDecoder(r.Body).Decode(&wire); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	f.mu.Lock()
	f.placeOrderCalls[id] = append(f.placeOrderCalls[id], wire.Order)
	filledPrice, hasOverride := f.filledPriceByAcct[id]
	f.mu.Unlock()

	if !hasOverride {
		filledPrice = fakeDefaultFilledPrice
	}
	result := fakePlaceOrderWireResult{
		Success:          true,
		BrokerPositionID: "fake-position-" + uuid.NewString(),
		FilledPrice:      &filledPrice,
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}

func (f *fakeBrokerService) handleModifyPosition(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	positionID := r.PathValue("positionId")
	var wire fakeModifyRequest
	if err := json.NewDecoder(r.Body).Decode(&wire); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	f.mu.Lock()
	f.modifyPositionCalls[id] = append(f.modifyPositionCalls[id], fakeModifyCall{PositionID: positionID, SLPrice: wire.SLPrice, TPPrice: wire.TPPrice})
	f.mu.Unlock()

	result := fakePlaceOrderWireResult{Success: true, BrokerPositionID: positionID}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}

func (f *fakeBrokerService) handleClosePosition(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	positionID := r.PathValue("positionId")
	var wire fakeCloseRequest
	if err := json.NewDecoder(r.Body).Decode(&wire); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	f.mu.Lock()
	f.closePositionCalls[id] = append(f.closePositionCalls[id], fakeCloseCall{PositionID: positionID, VolumeLots: wire.VolumeLots})
	f.mu.Unlock()

	result := fakePlaceOrderWireResult{Success: true, BrokerPositionID: positionID}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}

// buildDispatchRouter wires ONE fake server under both the CTRADER and MT5
// router keys -- remoteadapter.HTTPClient's wire shape is identical for
// both, so this single fake stands in for either real service depending on
// which broker_accounts.broker_type a given test fixture uses.
func buildDispatchRouter(t *testing.T, fake *fakeBrokerService) *remoteadapter.Router {
	t.Helper()
	fakeServer := httptest.NewServer(fake.handler())
	t.Cleanup(fakeServer.Close)
	return remoteadapter.NewRouter(map[domain.BrokerType]remoteadapter.RemoteAdapter{
		domain.BrokerTypeCTrader: remoteadapter.NewCTraderHTTPClient(fakeServer.URL, dispatchTestSharedSecret, nil),
		domain.BrokerTypeMT5:     remoteadapter.NewMTHTTPClient(fakeServer.URL, dispatchTestSharedSecret, "MT5", nil),
	})
}

// buildDispatchTestServer mirrors pipeline_integration_test.go's
// buildTestServer, but only ever connects a MASTER handle -- TICKET-106's
// dispatch.go routes every GetAccountSnapshot/PlaceOrder call through
// router (the real HTTP path here), so the injecting stub adapter's own
// role shrinks to being an event source, exactly like main.go's real stub
// path.
func buildDispatchTestServer(t *testing.T, ctx context.Context, pool *pgxpool.Pool, deduper domain.Deduper, kafkaWriter *kafka.Writer, router *remoteadapter.Router, masterAccountID string) *httptest.Server {
	t.Helper()

	adapter := stubadapter.New()
	masterHandle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: masterAccountID})
	if err != nil {
		t.Fatalf("connect master handle: %v", err)
	}

	pl := pipeline.New(pool, deduper, router, moneymgmt.NewFrankfurterClient(nil, nil), kafkaWriter, nil, nil)

	sub, err := adapter.StreamTradeEvents(ctx, masterHandle, pl.HandleEvent)
	if err != nil {
		t.Fatalf("stream trade events: %v", err)
	}
	t.Cleanup(func() { sub.Close() })

	mux := httpapi.NewMux("copy-engine-dispatch-test", adapter, masterHandle)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

// seedDispatchFixture mirrors pipeline_integration_test.go's seedFixture,
// parameterized over master/follower broker_type and copy_direction --
// TICKET-106's AC1 needs all 4 broker-pair combinations (CTRADER/MT5 on
// either side), which seedFixture's hardcoded 'CTRADER' can't express, and
// AC1's REVERSE-copy-direction siblings need a non-default copy_direction.
func seedDispatchFixture(t *testing.T, ctx context.Context, pool *pgxpool.Pool, masterBrokerType, followerBrokerType domain.BrokerType, copyDirection string) (masterAccountID, followerAccountID, relationshipID string) {
	t.Helper()
	suffix := uuid.NewString()[:8]

	adminUserID := uuid.NewString()
	masterUserID := uuid.NewString()
	followerUserID := uuid.NewString()
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status) VALUES ($1,$2,$3,'ACTIVE')`,
		adminUserID, "dispatch-admin-"+suffix+"@test.nectrix.dev", "Test Admin")
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status, created_by_user_id) VALUES ($1,$2,$3,'ACTIVE',$4)`,
		masterUserID, "dispatch-master-"+suffix+"@test.nectrix.dev", "Test Master", adminUserID)
	mustExec(t, ctx, pool, `INSERT INTO users (id, email, display_name, status, created_by_user_id) VALUES ($1,$2,$3,'ACTIVE',$4)`,
		followerUserID, "dispatch-follower-"+suffix+"@test.nectrix.dev", "Test Follower", adminUserID)

	masterAccountID = uuid.NewString()
	followerAccountID = uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version, connection_status)
		VALUES ($1,$2,$3,$4,TRUE,'USD','MASTER_ONLY',$5,1,'CONNECTED')`,
		masterAccountID, masterUserID, string(masterBrokerType), "dispatch-test-master-"+suffix, []byte{0})
	mustExec(t, ctx, pool, `
		INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version, connection_status)
		VALUES ($1,$2,$3,$4,TRUE,'USD','FOLLOWER_ONLY',$5,1,'CONNECTED')`,
		followerAccountID, followerUserID, string(followerBrokerType), "dispatch-test-follower-"+suffix, []byte{0})

	masterProfileID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name, is_public)
		VALUES ($1,$2,$3,'Test Master',TRUE)`,
		masterProfileID, masterUserID, masterAccountID)

	mmProfileID := uuid.NewString()
	riskProfileID := uuid.NewString()
	mustExec(t, ctx, pool, `INSERT INTO money_management_profiles (id, method, multiplier, rounding_mode) VALUES ($1,'MULTIPLIER',1.0,'DOWN')`, mmProfileID)
	mustExec(t, ctx, pool, `INSERT INTO risk_profiles (id, max_lot_per_trade, max_open_positions, max_slippage_pips) VALUES ($1,5.0,20,5)`, riskProfileID)

	followRequestID := uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO follow_requests (id, follower_user_id, master_profile_id, follower_broker_account_id, proposed_money_management_profile_id, proposed_risk_profile_id, status, decided_by_user_id, decided_at)
		VALUES ($1,$2,$3,$4,$5,$6,'APPROVED',$7,now())`,
		followRequestID, followerUserID, masterProfileID, followerAccountID, mmProfileID, riskProfileID, masterUserID)

	relationshipID = uuid.NewString()
	mustExec(t, ctx, pool, `
		INSERT INTO copy_relationships (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id, money_management_profile_id, risk_profile_id, status, copy_direction, performance_fee_percent, fee_collection_method, originating_follow_request_id)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'ACTIVE',$8,20.00,'BROKER_PARTNERSHIP',$9)`,
		relationshipID, masterProfileID, masterAccountID, followerUserID, followerAccountID, mmProfileID, riskProfileID, copyDirection, followRequestID)

	// Both sides need a CONFIRMED symbol_mappings row (TICKET-103/TICKET-106
	// decision #5) -- master's own pip_size/contract_size are direct inputs
	// to §9.6 SL/TP translation and RISK_PERCENT sizing.
	mustExec(t, ctx, pool, `
		INSERT INTO symbol_mappings (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step, min_lot, max_lot, pip_size, digits, margin_currency, is_confirmed)
		VALUES ($1,'EURUSD','EURUSD',100000,0.01,0.01,100,0.0001,5,'USD',TRUE)`,
		followerAccountID)
	mustExec(t, ctx, pool, `
		INSERT INTO symbol_mappings (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step, min_lot, max_lot, pip_size, digits, margin_currency, is_confirmed)
		VALUES ($1,'EURUSD','EURUSD',100000,0.01,0.01,100,0.0001,5,'USD',TRUE)`,
		masterAccountID)

	return masterAccountID, followerAccountID, relationshipID
}

// postInjectWithOpenPrice extends pipeline_integration_test.go's postInject
// with an explicit OpenPrice -- needed for AC2/AC4, which both depend on a
// deterministic master open price to compute an expected result by hand.
func postInjectWithOpenPrice(t *testing.T, server *httptest.Server, brokerPositionID, serverTimestamp string, volumeLots, openPrice float64) {
	t.Helper()
	body, err := json.Marshal(stubadapter.InjectEventParams{
		BrokerPositionID: brokerPositionID,
		ServerTimestamp:  serverTimestamp,
		VolumeLots:       volumeLots,
		OpenPrice:        openPrice,
	})
	if err != nil {
		t.Fatalf("marshal inject params: %v", err)
	}
	resp, err := http.Post(server.URL+"/test/inject-trade-event", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("POST inject: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("POST inject: status %d body %s", resp.StatusCode, b)
	}
}
