package ctrader

import (
	"context"
	"fmt"
	"time"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
)

// fillWaitTimeout (bugfix) -- ClosePosition's own bounded wait for a later,
// separately-delivered ORDER_FILLED event when the immediate close response
// carries no Deal. Matches ctraderapi.Client's own pendingRequestTimeout
// convention (a request the connection is otherwise healthy for shouldn't
// need longer than this to resolve) -- on timeout, FilledPrice simply stays
// nil (never fabricated), the close itself already succeeded. A var, not a
// const, solely so tests can shrink it temporarily instead of really waiting
// 5s to prove the timeout path.
var fillWaitTimeout = 5 * time.Second

// reconcileFillRetryAttempts/reconcileFillRetryDelay (bugfix) -- PlaceOrder's own Reconcile
// follow-up (reconcilePositionPrice) can genuinely race a brand-new position: cTrader's own
// server-side Reconcile view doesn't always reflect a position created microseconds ago yet, so
// the very first follow-up call can come back with no matching position at all -- silently, by
// design (never fabricate a price), leaving copied_trades.filled_price permanently null for that
// trade even though the position really did fill. A short, bounded retry closes most of this
// race without meaningfully slowing down a real PlaceOrder call. vars (not consts), like
// fillWaitTimeout above, so tests can shrink the delay instead of really waiting.
var (
	reconcileFillRetryAttempts = 3
	reconcileFillRetryDelay    = 300 * time.Millisecond
)

// cTrader has no dedicated *Res message for NewOrderReq/ClosePositionReq/
// AmendPositionSLTPReq (confirmed absent from the vendored proto — only
// ProtoOAExecutionEvent has a Default_..._PayloadType tying it to order
// operations); the real acknowledgment is the resulting ProtoOAExecutionEvent,
// echoing the same clientMsgId our low-level Client.Request() already
// correlates on. Treating that event as the "response" is the best-effort
// interpretation of cTrader's own request/event design — confirmed for real
// against a live demo account as part of this ticket's verification runbook,
// not assumed silently.

// symbolIDAndLotSize resolves a canonical symbol code to cTrader's numeric
// symbolId and real lot size, fetching the full spec if the cache hasn't
// seen this symbol yet.
func (a *CTraderAdapter) symbolIDAndLotSize(ctx context.Context, conn *connection, canonicalCode string) (int64, int64, error) {
	light, ok := a.symbols.byBrokerName(canonicalCode)
	if !ok {
		return 0, 0, fmt.Errorf("ctrader: unknown symbol %q", canonicalCode)
	}
	_, lotSize, err := a.resolveSymbolByID(ctx, conn, light.GetSymbolId())
	if err != nil {
		return 0, 0, err
	}
	return light.GetSymbolId(), lotSize, nil
}

// PlaceOrder submits a real market order. Idempotency-key deduplication
// (this platform's own guarantee — cTrader's own client-tag matching isn't
// a reliable server-side dedup mechanism) is the caller's responsibility;
// see the Redis-backed decorator wrapping this adapter in cmd/ wiring, not
// this method itself.
func (a *CTraderAdapter) PlaceOrder(ctx context.Context, handle domain.ConnectionHandle, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}

	symbolID, lotSize, err := a.symbolIDAndLotSize(ctx, conn, order.Symbol.CanonicalCode)
	if err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error()}, nil
	}

	clientOrderID := order.ClientOrderTag
	req := &openapi.ProtoOANewOrderReq{
		CtidTraderAccountId: &conn.credential.CtidTraderAccountID,
		SymbolId:            &symbolID,
		OrderType:           openapi.ProtoOAOrderType_MARKET.Enum(),
		TradeSide:           protoTradeSide(order.Direction).Enum(),
		Volume:              int64Ptr(lotsToVolume(order.VolumeLots, lotSize)),
		ClientOrderId:       &clientOrderID, // round-trips this platform's idempotency key through cTrader's own execution reports
	}
	if order.SLPrice != nil {
		req.StopLoss = order.SLPrice
	}
	if order.TPPrice != nil {
		req.TakeProfit = order.TPPrice
	}

	event := &openapi.ProtoOAExecutionEvent{}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_NEW_ORDER_REQ), req, event); err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error(), RawBrokerResponse: err.Error()}, nil
	}

	result := executionEventToOrderResult(event)
	// Bugfix -- cTrader sends order-acceptance and fill-confirmation as two
	// SEPARATE ProtoOAExecutionEvent messages (see connection.fillWaiters'
	// own doc-comment); the event Request() actually caught above may well be
	// the ACCEPTED one, with no Deal at all. Unlike ClosePosition, a
	// brand-new position's id isn't known until AFTER this response, so
	// there's no waiter to register in advance -- instead, fall back to a
	// synchronous ProtoOAReconcileReq (the same call ClosePosition already
	// trusts) and read the now-open position's own durably-stored Price.
	if result.Success && result.FilledPrice == nil && result.BrokerPositionID != "" {
		if price, ok := a.reconcilePositionPrice(ctx, conn, result.BrokerPositionID); ok {
			result.FilledPrice = &price
		}
	}
	return result, nil
}

// reconcilePositionPrice looks up positionID's own real, server-side open
// price via ProtoOAReconcileReq -- ok=false (not an error) if the position
// can't be found (e.g. it already closed in the moment between PlaceOrder's
// own accept and this follow-up) or the reconcile call itself fails; a
// missing fill price is left nil, never fabricated.
func (a *CTraderAdapter) reconcilePositionPrice(ctx context.Context, conn *connection, positionID string) (float64, bool) {
	posID, err := parsePositionID(positionID)
	if err != nil {
		return 0, false
	}
	for attempt := 1; attempt <= reconcileFillRetryAttempts; attempt++ {
		if price, ok := a.reconcilePositionPriceOnce(ctx, conn, posID, positionID); ok {
			return price, true
		}
		if attempt < reconcileFillRetryAttempts {
			select {
			case <-time.After(reconcileFillRetryDelay):
			case <-ctx.Done():
				return 0, false
			}
		}
	}
	return 0, false
}

func (a *CTraderAdapter) reconcilePositionPriceOnce(ctx context.Context, conn *connection, posID int64, positionID string) (float64, bool) {
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()
	reconcileReq := &openapi.ProtoOAReconcileReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	reconcileResp := &openapi.ProtoOAReconcileRes{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_REQ), reconcileReq, reconcileResp); err != nil {
		a.logger.Warn("ctrader: reconcile follow-up for open fill price failed", "positionId", positionID, "error", err)
		return 0, false
	}
	for _, p := range reconcileResp.GetPosition() {
		if p.GetPositionId() == posID {
			return p.GetPrice(), true
		}
	}
	return 0, false
}

// ModifyPosition amends a position's stop-loss/take-profit — cTrader has no
// separate "modify order" concept for an already-filled position, only
// AmendPositionSLTP.
func (a *CTraderAdapter) ModifyPosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	posID, err := parsePositionID(positionID)
	if err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error()}, nil
	}

	req := &openapi.ProtoOAAmendPositionSLTPReq{
		CtidTraderAccountId: &conn.credential.CtidTraderAccountID,
		PositionId:          &posID,
		StopLoss:            changes.SLPrice,
		TakeProfit:          changes.TPPrice,
	}

	event := &openapi.ProtoOAExecutionEvent{}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_AMEND_POSITION_SLTP_REQ), req, event); err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error(), RawBrokerResponse: err.Error()}, nil
	}

	return executionEventToOrderResult(event), nil
}

// ClosePosition closes all or part of a position. volume == nil closes the
// entire remaining position. Always looks the position up via a real
// ProtoOAReconcileReq first — needed regardless of whether volume is
// explicit, since converting lots to cTrader's raw volume-in-cents requires
// the position's own symbol's real lot size.
func (a *CTraderAdapter) ClosePosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	posID, err := parsePositionID(positionID)
	if err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error()}, nil
	}

	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()

	reconcileReq := &openapi.ProtoOAReconcileReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	reconcileResp := &openapi.ProtoOAReconcileRes{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_REQ), reconcileReq, reconcileResp); err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error()}, nil
	}

	var target *openapi.ProtoOAPosition
	for _, p := range reconcileResp.GetPosition() {
		if p.GetPositionId() == posID {
			target = p
			break
		}
	}
	if target == nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: fmt.Sprintf("ctrader: position %s not found (already closed?)", positionID)}, nil
	}

	normalized, lotSize, mapErr := a.mapPosition(ctx, conn, target)
	if mapErr != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: mapErr.Error()}, nil
	}
	closeVolumeLots := normalized.VolumeLots
	if volume != nil {
		closeVolumeLots = *volume
	}

	req := &openapi.ProtoOAClosePositionReq{
		CtidTraderAccountId: &conn.credential.CtidTraderAccountID,
		PositionId:          &posID,
		Volume:              int64Ptr(lotsToVolume(closeVolumeLots, lotSize)),
	}

	// Bugfix -- register BEFORE sending the close request: unlike PlaceOrder,
	// posID is already known here, so there's no race window (see
	// connection.fillWaiters' own doc-comment for why ClosePosition gets this
	// mechanism and PlaceOrder gets the simpler Reconcile-follow-up instead).
	fillCh, unregister := conn.registerFillWaiter(posID)
	defer unregister()

	event := &openapi.ProtoOAExecutionEvent{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_CLOSE_POSITION_REQ), req, event); err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error(), RawBrokerResponse: err.Error()}, nil
	}

	result := executionEventToOrderResult(event)
	if result.Success && result.FilledPrice == nil {
		// The immediate response was the ACCEPTED event, not the FILLED one --
		// wait (bounded) for handleEvent to deliver the real fill via the
		// waiter registered above.
		timer := time.NewTimer(fillWaitTimeout)
		defer timer.Stop()
		select {
		case deal := <-fillCh:
			if deal != nil {
				result.FilledPrice = deal.ExecutionPrice
			}
		case <-timer.C:
			a.logger.Warn("ctrader: timed out waiting for close fill confirmation", "positionId", positionID)
		case <-ctx.Done():
		}
	}
	return result, nil
}

func executionEventToOrderResult(event *openapi.ProtoOAExecutionEvent) domain.NormalizedOrderResult {
	if event.GetErrorCode() != "" {
		return domain.NormalizedOrderResult{Success: false, RejectReason: event.GetErrorCode(), RawBrokerResponse: event}
	}
	result := domain.NormalizedOrderResult{Success: true, RawBrokerResponse: event}
	if pos := event.GetPosition(); pos != nil {
		result.BrokerPositionID = fmt.Sprintf("%d", pos.GetPositionId())
	}
	if deal := event.GetDeal(); deal != nil {
		result.FilledPrice = deal.ExecutionPrice
	}
	return result
}

func parsePositionID(positionID string) (int64, error) {
	var id int64
	if _, err := fmt.Sscanf(positionID, "%d", &id); err != nil {
		return 0, fmt.Errorf("ctrader: invalid position id %q: %w", positionID, err)
	}
	return id, nil
}

func int64Ptr(v int64) *int64 { return &v }
