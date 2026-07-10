package ctrader

import (
	"context"
	"fmt"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
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

	return executionEventToOrderResult(event), nil
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

	event := &openapi.ProtoOAExecutionEvent{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_CLOSE_POSITION_REQ), req, event); err != nil {
		return domain.NormalizedOrderResult{Success: false, RejectReason: err.Error(), RawBrokerResponse: err.Error()}, nil
	}

	return executionEventToOrderResult(event), nil
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
