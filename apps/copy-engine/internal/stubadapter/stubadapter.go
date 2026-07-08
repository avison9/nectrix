// Package stubadapter is TICKET-009's in-memory domain.BrokerAdapter
// implementation — enough to prove the Copy Engine pipeline shape end to
// end before any real cTrader/MT5 adapter exists (that's Phase 1). Lives
// inside apps/copy-engine rather than a shared packages/ module because
// copy-engine is its only consumer right now; relocating later if
// apps/broker-adapters needs it too is a one-file move, not a redesign.
package stubadapter

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/observability"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

const (
	pipSizeDefault  = 0.0001
	fakeMarketPrice = 1.10000
)

// fillStrategy simulates how a broker fills a market order, given a fake
// reference market price and the order's direction — this is the one bit
// of behavior StubBrokerAdapter and StubBrokerAdapterVariant genuinely
// differ on (see New/NewVariant), proving AC4's swap is a real abstraction
// boundary rather than a renamed copy.
type fillStrategy func(basePrice float64, direction domain.TradeDirection) float64

// slippageFill simulates a fixed, adverse-to-the-taker slippage in pips.
func slippageFill(pips float64) fillStrategy {
	return func(basePrice float64, direction domain.TradeDirection) float64 {
		offset := pips * pipSizeDefault
		if direction == domain.TradeDirectionSell {
			return basePrice - offset
		}
		return basePrice + offset
	}
}

// exactFill simulates a broker that always fills at the exact reference
// price — no slippage.
func exactFill(basePrice float64, _ domain.TradeDirection) float64 {
	return basePrice
}

// stubCore implements domain.BrokerAdapter; StubBrokerAdapter and
// StubBrokerAdapterVariant each embed one, configured with a different
// brokerType and fillStrategy at construction time.
type stubCore struct {
	brokerType domain.BrokerType
	fill       fillStrategy

	mu   sync.Mutex
	subs map[string]func(context.Context, domain.NormalizedTradeEvent) error // handle.ID -> registered onEvent
}

func newCore(brokerType domain.BrokerType, fill fillStrategy) *stubCore {
	return &stubCore{
		brokerType: brokerType,
		fill:       fill,
		subs:       make(map[string]func(context.Context, domain.NormalizedTradeEvent) error),
	}
}

// StubBrokerAdapter is the base stub — CTRADER, simulates a small fixed
// fill slippage on PlaceOrder.
type StubBrokerAdapter struct{ *stubCore }

// New constructs a StubBrokerAdapter.
func New() *StubBrokerAdapter {
	return &StubBrokerAdapter{stubCore: newCore(domain.BrokerTypeCTrader, slippageFill(0.5))}
}

// StubBrokerAdapterVariant is a second, distinctly-behaved stub — MT5,
// fills at the exact requested price with no slippage. Exists to prove
// AC4: swapping this in for StubBrokerAdapter requires zero Copy Engine
// pipeline code changes, only a different constructor call at wiring time.
type StubBrokerAdapterVariant struct{ *stubCore }

// NewVariant constructs a StubBrokerAdapterVariant.
func NewVariant() *StubBrokerAdapterVariant {
	return &StubBrokerAdapterVariant{stubCore: newCore(domain.BrokerTypeMT5, exactFill)}
}

func (c *stubCore) BrokerType() domain.BrokerType { return c.brokerType }

func (c *stubCore) Connect(ctx context.Context, credentials domain.BrokerCredentials) (domain.ConnectionHandle, error) {
	handle := domain.ConnectionHandle{ID: uuid.NewString(), BrokerType: c.brokerType, AccountID: credentials.AccountID}
	observability.LogWithTrace(ctx, "stubadapter: connected",
		"broker_type", string(c.brokerType), "handle_id", handle.ID, "account_id", credentials.AccountID)
	return handle, nil
}

func (c *stubCore) Disconnect(ctx context.Context, handle domain.ConnectionHandle) error {
	c.mu.Lock()
	delete(c.subs, handle.ID)
	c.mu.Unlock()
	observability.LogWithTrace(ctx, "stubadapter: disconnected", "broker_type", string(c.brokerType), "handle_id", handle.ID)
	return nil
}

func (c *stubCore) HealthCheck(ctx context.Context, handle domain.ConnectionHandle) (domain.ConnectionHealth, error) {
	return domain.ConnectionHealth{Connected: true, Detail: "stub: always healthy"}, nil
}

func (c *stubCore) GetAccountSnapshot(ctx context.Context, handle domain.ConnectionHandle) (domain.AccountSnapshot, error) {
	return domain.AccountSnapshot{
		BrokerAccountID: handle.AccountID,
		Currency:        "USD",
		Balance:         10000,
		Equity:          10000,
		UsedMargin:      0,
		FreeMargin:      10000,
		AsOf:            time.Now().UTC().Format(time.RFC3339),
	}, nil
}

func (c *stubCore) GetOpenPositions(ctx context.Context, handle domain.ConnectionHandle) ([]domain.NormalizedPosition, error) {
	return nil, nil
}

// StreamTradeEvents registers onEvent for handle — InjectEvent (see
// inject.go) is what actually invokes it; there is no real broker stream
// here, only this test/manual-trigger affordance.
func (c *stubCore) StreamTradeEvents(ctx context.Context, handle domain.ConnectionHandle, onEvent func(context.Context, domain.NormalizedTradeEvent) error) (domain.Subscription, error) {
	c.mu.Lock()
	c.subs[handle.ID] = onEvent
	c.mu.Unlock()
	return &subscription{core: c, handleID: handle.ID}, nil
}

type subscription struct {
	core     *stubCore
	handleID string
}

func (s *subscription) Close() error {
	s.core.mu.Lock()
	delete(s.core.subs, s.handleID)
	s.core.mu.Unlock()
	return nil
}

func (c *stubCore) PlaceOrder(ctx context.Context, handle domain.ConnectionHandle, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	filled := c.fill(fakeMarketPrice, order.Direction)
	positionID := uuid.NewString()
	observability.LogWithTrace(ctx, "stubadapter: PlaceOrder",
		"broker_type", string(c.brokerType), "handle_id", handle.ID, "account_id", handle.AccountID,
		"symbol", order.Symbol.CanonicalCode, "direction", string(order.Direction), "volume_lots", order.VolumeLots,
		"filled_price", filled, "broker_position_id", positionID)
	return domain.NormalizedOrderResult{
		Success:           true,
		BrokerPositionID:  positionID,
		FilledPrice:       &filled,
		RawBrokerResponse: map[string]any{"stub": true, "brokerType": string(c.brokerType)},
	}, nil
}

func (c *stubCore) ModifyPosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	observability.LogWithTrace(ctx, "stubadapter: ModifyPosition",
		"broker_type", string(c.brokerType), "handle_id", handle.ID, "position_id", positionID)
	return domain.NormalizedOrderResult{Success: true, BrokerPositionID: positionID, RawBrokerResponse: map[string]any{"stub": true}}, nil
}

func (c *stubCore) ClosePosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	observability.LogWithTrace(ctx, "stubadapter: ClosePosition",
		"broker_type", string(c.brokerType), "handle_id", handle.ID, "position_id", positionID)
	return domain.NormalizedOrderResult{Success: true, BrokerPositionID: positionID, RawBrokerResponse: map[string]any{"stub": true}}, nil
}

// ResolveSymbol applies the suffix-stripping heuristic
// docs/08-copy-trading-engine.md §8.4 describes for auto-suggesting symbol
// mappings at account-link time (e.g. "EURUSD.a"/"EURUSDm"/"EURUSD_i" ->
// "EURUSD") — real, if simplified, logic rather than a pure pass-through.
func (c *stubCore) ResolveSymbol(ctx context.Context, brokerSymbol string) (domain.NormalizedSymbol, error) {
	canonical := strings.ToUpper(brokerSymbol)
	canonical = strings.TrimPrefix(canonical, "#")
	for _, suffix := range []string{".A", "M", "_I"} {
		canonical = strings.TrimSuffix(canonical, suffix)
	}
	if canonical == "" {
		return domain.NormalizedSymbol{}, fmt.Errorf("stubadapter: cannot resolve empty broker symbol")
	}
	return domain.NormalizedSymbol{CanonicalCode: canonical, AssetClass: domain.AssetClassFX}, nil
}

func (c *stubCore) GetSymbolSpecification(ctx context.Context, symbol domain.NormalizedSymbol) (domain.SymbolSpec, error) {
	return domain.SymbolSpec{
		Symbol:           symbol,
		BrokerSymbolName: symbol.CanonicalCode,
		ContractSize:     100000,
		LotStep:          0.01,
		MinLot:           0.01,
		MaxLot:           50,
		PipSize:          pipSizeDefault,
		Digits:           5,
		MarginCurrency:   "USD",
	}, nil
}

var (
	_ domain.BrokerAdapter = (*StubBrokerAdapter)(nil)
	_ domain.BrokerAdapter = (*StubBrokerAdapterVariant)(nil)
)
