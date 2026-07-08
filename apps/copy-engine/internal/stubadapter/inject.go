package stubadapter

import (
	"context"
	"fmt"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

// InjectEventParams describes a synthetic trade event to inject — the
// "manual/HTTP-triggerable inject event test endpoint" TICKET-009 asks
// for. Every field has a sane default so an empty/minimal JSON body still
// produces a valid event; callers that need a deterministic dedup key
// (e.g. AC3's "submit the identical event twice" test) set
// BrokerPositionID and ServerTimestamp explicitly.
type InjectEventParams struct {
	BrokerPositionID string  `json:"brokerPositionId"`
	EventType        string  `json:"eventType"`
	Symbol           string  `json:"symbol"`
	AssetClass       string  `json:"assetClass"`
	Direction        string  `json:"direction"`
	VolumeLots       float64 `json:"volumeLots"`
	OpenPrice        float64 `json:"openPrice"`
	ServerTimestamp  string  `json:"serverTimestamp"`
}

// Injectable is the test-only affordance both stub adapters expose (via
// promoted *stubCore methods) — deliberately not part of domain.BrokerAdapter
// itself, since real adapters have no equivalent "inject a fake event" hook.
// Callers holding only a domain.BrokerAdapter (e.g. the pipeline) never see
// this; only test/HTTP wiring code that knows it's talking to a stub does.
type Injectable interface {
	InjectEvent(ctx context.Context, handle domain.ConnectionHandle, params InjectEventParams) error
}

// InjectEvent synthesizes a domain.NormalizedTradeEvent from params and
// invokes whatever callback was registered for handle via
// StreamTradeEvents, synchronously — so an HTTP handler or test can await
// full pipeline completion (DB insert + Kafka publish) before returning.
func (c *stubCore) InjectEvent(ctx context.Context, handle domain.ConnectionHandle, params InjectEventParams) error {
	c.mu.Lock()
	onEvent, ok := c.subs[handle.ID]
	c.mu.Unlock()
	if !ok {
		return fmt.Errorf("stubadapter: no active StreamTradeEvents subscription for handle %s", handle.ID)
	}
	return onEvent(ctx, buildEvent(handle, params))
}

func buildEvent(handle domain.ConnectionHandle, p InjectEventParams) domain.NormalizedTradeEvent {
	brokerPositionID := p.BrokerPositionID
	if brokerPositionID == "" {
		brokerPositionID = uuid.NewString()
	}
	eventType := domain.TradeEventType(p.EventType)
	if eventType == "" {
		eventType = domain.TradeEventPositionOpened
	}
	symbol := p.Symbol
	if symbol == "" {
		symbol = "EURUSD"
	}
	assetClass := domain.AssetClass(p.AssetClass)
	if assetClass == "" {
		assetClass = domain.AssetClassFX
	}
	direction := domain.TradeDirection(p.Direction)
	if direction == "" {
		direction = domain.TradeDirectionBuy
	}
	volumeLots := p.VolumeLots
	if volumeLots == 0 {
		volumeLots = 1.0
	}
	openPrice := p.OpenPrice
	if openPrice == 0 {
		openPrice = fakeMarketPrice
	}
	serverTimestamp := p.ServerTimestamp
	if serverTimestamp == "" {
		serverTimestamp = time.Now().UTC().Format(time.RFC3339)
	}

	return domain.NormalizedTradeEvent{
		EventID:               uuid.NewString(),
		MasterBrokerAccountID: handle.AccountID,
		EventType:             eventType,
		Position: domain.NormalizedPosition{
			BrokerPositionID: brokerPositionID,
			Symbol:           domain.NormalizedSymbol{CanonicalCode: symbol, AssetClass: assetClass},
			Direction:        direction,
			VolumeLots:       volumeLots,
			OpenPrice:        openPrice,
			OpenedAt:         time.Now().UTC().Format(time.RFC3339),
		},
		ServerTimestamp:   serverTimestamp,
		ReceivedAtGateway: time.Now().UTC().Format(time.RFC3339),
	}
}

var (
	_ Injectable = (*StubBrokerAdapter)(nil)
	_ Injectable = (*StubBrokerAdapterVariant)(nil)
)
