package ctrader

import (
	"context"
	"fmt"
	"time"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
	"google.golang.org/protobuf/proto"
)

// subscription is the Subscription domain.BrokerAdapter's StreamTradeEvents
// returns — Close just detaches the callback; it does not tear down the
// underlying connection (Disconnect does that).
type subscription struct {
	conn *connection
}

func (s *subscription) Close() error {
	s.conn.onEventMu.Lock()
	s.conn.onEvent = nil
	s.conn.onEventMu.Unlock()
	return nil
}

// StreamTradeEvents registers onEvent to receive every real execution event
// cTrader pushes for this account — position opens, partial/full closes.
// cTrader streams these automatically once AccountAuth has succeeded (no
// separate subscribe call, unlike spot prices); Connect already started the
// drain loop feeding this callback.
func (a *CTraderAdapter) StreamTradeEvents(ctx context.Context, handle domain.ConnectionHandle, onEvent func(context.Context, domain.NormalizedTradeEvent) error) (domain.Subscription, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return nil, err
	}
	conn.onEventMu.Lock()
	conn.onEvent = onEvent
	conn.onEventMu.Unlock()
	return &subscription{conn: conn}, nil
}

// drainEvents is the single consumer of one connection's ctraderapi.Client
// Events() channel — restarted (by reconnectLoop) against the new Client
// after every reconnect, so a StreamTradeEvents subscription survives a
// real connection drop transparently.
func (a *CTraderAdapter) drainEvents(ctx context.Context, conn *connection) {
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()

	for {
		select {
		case <-ctx.Done():
			return
		case <-client.Done():
			return // reconnectLoop notices the same signal and re-dials; this goroutine's job for the old client ends here
		case msg := <-client.Events():
			a.handleEvent(ctx, conn, msg)
		}
	}
}

func (a *CTraderAdapter) handleEvent(ctx context.Context, conn *connection, msg *openapi.ProtoMessage) {
	if msg.GetPayloadType() == uint32(openapi.ProtoOAPayloadType_PROTO_OA_SPOT_EVENT) {
		a.handleSpotEvent(conn, msg)
		return
	}
	if msg.GetPayloadType() != uint32(openapi.ProtoOAPayloadType_PROTO_OA_EXECUTION_EVENT) {
		return // an event type this platform doesn't act on
	}

	event := &openapi.ProtoOAExecutionEvent{}
	if err := proto.Unmarshal(msg.GetPayload(), event); err != nil {
		a.logger.Error("ctrader: dropping unparseable execution event", "error", err)
		return
	}

	// Bugfix -- deliver to any registered ClosePosition fill-waiter FIRST,
	// additively (never instead of the normal onEvent dispatch below): a
	// FILLED/PARTIAL_FILL event with a real Deal might be exactly the close
	// confirmation orders.go's own synchronous Request() already missed
	// (cTrader sends acceptance and fill as separate messages -- see
	// connection.fillWaiters' own doc-comment).
	switch event.GetExecutionType() {
	case openapi.ProtoOAExecutionType_ORDER_FILLED, openapi.ProtoOAExecutionType_ORDER_PARTIAL_FILL:
		if pos, deal := event.GetPosition(), event.GetDeal(); pos != nil && deal != nil {
			conn.deliverFill(pos.GetPositionId(), deal)
		}
	}

	normalized, ok := a.mapExecutionEvent(ctx, conn, event)
	if !ok {
		return // an execution type this platform doesn't act on (rejection, swap, deposit, ...)
	}

	conn.onEventMu.Lock()
	onEvent := conn.onEvent
	conn.onEventMu.Unlock()
	if onEvent == nil {
		return // no active StreamTradeEvents subscription right now
	}
	if err := onEvent(ctx, normalized); err != nil {
		a.logger.Error("ctrader: onEvent callback returned an error", "error", err, "eventId", normalized.EventID)
	}
}

// handleSpotEvent caches the real bid/ask for GetAccountSnapshot's
// unrealized-P&L computation. Bid/Ask are "specified in 1/100000 of unit of
// a price" (ProtoOASpotEvent's own doc-comment) — real value = raw/100000.
func (a *CTraderAdapter) handleSpotEvent(conn *connection, msg *openapi.ProtoMessage) {
	spot := &openapi.ProtoOASpotEvent{}
	if err := proto.Unmarshal(msg.GetPayload(), spot); err != nil {
		a.logger.Error("ctrader: dropping unparseable spot event", "error", err)
		return
	}
	if spot.Bid == nil || spot.Ask == nil {
		return // a trendbar-only or session-close-only tick, no fresh bid/ask to cache
	}
	conn.spotMu.Lock()
	conn.latestSpot[spot.GetSymbolId()] = spotPrice{
		bid: float64(spot.GetBid()) / 100000,
		ask: float64(spot.GetAsk()) / 100000,
	}
	conn.spotMu.Unlock()
}

// mapExecutionEvent turns a real ProtoOAExecutionEvent into this platform's
// NormalizedTradeEvent. The core distinction — opened vs. modified vs.
// (partially) closed — comes from real, documented fields: Deal's own
// ClosePositionDetail is only ever populated "for closing deal" (its own
// doc-comment), and Position.PositionStatus distinguishes a fully-closed
// position from a partial one. ORDER_PARTIAL_FILL with no close detail
// (still building up an opening position) maps to MODIFIED, matching this
// platform's four-value TradeEventType — there's no fifth
// "still-opening-partial-fill" case in the domain model.
func (a *CTraderAdapter) mapExecutionEvent(ctx context.Context, conn *connection, event *openapi.ProtoOAExecutionEvent) (domain.NormalizedTradeEvent, bool) {
	position := event.GetPosition()
	deal := event.GetDeal()
	if position == nil || deal == nil {
		return domain.NormalizedTradeEvent{}, false
	}
	switch event.GetExecutionType() {
	case openapi.ProtoOAExecutionType_ORDER_FILLED, openapi.ProtoOAExecutionType_ORDER_PARTIAL_FILL:
		// handled below
	default:
		return domain.NormalizedTradeEvent{}, false // ACCEPTED/REJECTED/CANCELLED/EXPIRED/SWAP/... — no position state change to report
	}

	normalizedPosition, lotSize, err := a.mapPosition(ctx, conn, position)
	if err != nil {
		a.logger.Error("ctrader: could not resolve symbol for a live execution event", "error", err, "symbolId", position.GetTradeData().GetSymbolId())
		return domain.NormalizedTradeEvent{}, false
	}
	fillPrice := deal.ExecutionPrice

	var eventType domain.TradeEventType
	var closedVolumeLots *float64

	if closeDetail := deal.GetClosePositionDetail(); closeDetail != nil {
		if position.GetPositionStatus() == openapi.ProtoOAPositionStatus_POSITION_STATUS_CLOSED {
			eventType = domain.TradeEventPositionClosed
		} else {
			eventType = domain.TradeEventPositionPartiallyClosed
		}
		lots := volumeToLots(closeDetail.GetClosedVolume(), lotSize)
		closedVolumeLots = &lots
	} else if event.GetExecutionType() == openapi.ProtoOAExecutionType_ORDER_PARTIAL_FILL {
		eventType = domain.TradeEventPositionModified
	} else {
		eventType = domain.TradeEventPositionOpened
	}

	return domain.NormalizedTradeEvent{
		EventID:               fmt.Sprintf("ctrader-deal-%d", deal.GetDealId()),
		MasterBrokerAccountID: conn.credential.AccountID,
		EventType:             eventType,
		Position:              normalizedPosition,
		ClosedVolumeLots:      closedVolumeLots,
		FillPrice:             fillPrice,
		ServerTimestamp:       msToRFC3339(deal.GetExecutionTimestamp()),
		ReceivedAtGateway:     time.Now().UTC().Format(time.RFC3339Nano),
	}, true
}

// mapPosition converts a raw ProtoOAPosition into NormalizedPosition,
// resolving VolumeLots correctly via the symbol's real LotSize — never a
// placeholder. Returns the resolved lot size too, so callers that also need
// to scale a closedVolume (mapExecutionEvent) don't re-resolve the symbol.
func (a *CTraderAdapter) mapPosition(ctx context.Context, conn *connection, position *openapi.ProtoOAPosition) (domain.NormalizedPosition, int64, error) {
	trade := position.GetTradeData()
	symbol, lotSize, err := a.resolveSymbolByID(ctx, conn, trade.GetSymbolId())
	if err != nil {
		return domain.NormalizedPosition{}, 0, err
	}
	direction := tradeDirection(trade.GetTradeSide())

	// TICKET-124 — a passive read only, deliberately not a lazy-subscribe-on-miss like
	// unrealizedPnL's: mapPosition is also called from the hot execution-event path
	// (handleEvent, for every live trade event), where a synchronous subscribe-and-block call
	// would add unpredictable latency to real-time event ingestion for a field nothing on that
	// path consumes. GetOpenPositions (the caller this field actually exists for) does its own
	// best-effort subscribe pass below instead, so a miss here still converges on the next call.
	conn.spotMu.RLock()
	spot, haveSpot := conn.latestSpot[trade.GetSymbolId()]
	conn.spotMu.RUnlock()
	var currentPrice *float64
	if haveSpot {
		p := closingPriceFor(direction, spot)
		currentPrice = &p
	}

	return domain.NormalizedPosition{
		BrokerPositionID: fmt.Sprintf("%d", position.GetPositionId()),
		Symbol:           symbol,
		Direction:        direction,
		VolumeLots:       volumeToLots(trade.GetVolume(), lotSize),
		OpenPrice:        position.GetPrice(),
		CurrentSLPrice:   position.StopLoss,
		CurrentTPPrice:   position.TakeProfit,
		OpenedAt:         msToRFC3339(trade.GetOpenTimestamp()),
		CurrentPrice:     currentPrice,
	}, lotSize, nil
}

// resolveSymbolByID looks up a symbol's canonical code and real lot size by
// cTrader's numeric symbolId — the symbol cache is normally already warm
// (Connect populates it via populateSymbolCache before any event can
// realistically arrive), but on a genuine cache miss this fetches the full
// spec synchronously (a single request/response round trip) rather than
// dropping or mis-scaling a real trade event.
func (a *CTraderAdapter) resolveSymbolByID(ctx context.Context, conn *connection, symbolID int64) (domain.NormalizedSymbol, int64, error) {
	a.symbols.mu.RLock()
	light, haveLight := a.symbols.byID[symbolID]
	full, haveFull := a.symbols.fullByID[symbolID]
	a.symbols.mu.RUnlock()

	if !haveLight {
		if err := a.populateSymbolCache(ctx, conn); err != nil {
			return domain.NormalizedSymbol{}, 0, fmt.Errorf("ctrader: resolve symbolId %d: %w", symbolID, err)
		}
		a.symbols.mu.RLock()
		light, haveLight = a.symbols.byID[symbolID]
		a.symbols.mu.RUnlock()
		if !haveLight {
			return domain.NormalizedSymbol{}, 0, fmt.Errorf("ctrader: broker has no symbolId %d", symbolID)
		}
	}

	if !haveFull {
		conn.mu.Lock()
		client := conn.client
		conn.mu.Unlock()
		req := &openapi.ProtoOASymbolByIdReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID, SymbolId: []int64{symbolID}}
		resp := &openapi.ProtoOASymbolByIdRes{}
		if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOL_BY_ID_REQ), req, resp); err != nil {
			return domain.NormalizedSymbol{}, 0, fmt.Errorf("ctrader: fetch symbol spec for symbolId %d: %w", symbolID, err)
		}
		if len(resp.GetSymbol()) == 0 {
			return domain.NormalizedSymbol{}, 0, fmt.Errorf("ctrader: broker returned no spec for symbolId %d", symbolID)
		}
		full = resp.GetSymbol()[0]
		a.symbols.mu.Lock()
		a.symbols.fullByID[symbolID] = full
		a.symbols.mu.Unlock()
	}

	canonical := domain.NormalizeSymbolName(light.GetSymbolName())
	return domain.NormalizedSymbol{CanonicalCode: canonical, AssetClass: domain.AssetClassOf(canonical)}, full.GetLotSize(), nil
}

func msToRFC3339(ms int64) string {
	if ms == 0 {
		return time.Now().UTC().Format(time.RFC3339Nano)
	}
	return time.UnixMilli(ms).UTC().Format(time.RFC3339Nano)
}
