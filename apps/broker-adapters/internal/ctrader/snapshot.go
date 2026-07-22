package ctrader

import (
	"context"
	"fmt"
	"time"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
)

// GetAccountSnapshot reads real account financials. Balance/UsedMargin come
// directly from cTrader's own reported values (ProtoOATraderRes,
// ProtoOAReconcileRes — no computation, just unit scaling). Equity is
// Balance + the sum of every open position's real unrealized P&L, computed
// from each position's own recorded open price vs. its symbol's current
// price — deliberately NOT a money-management/risk calculation (that's
// TICKET-104/105's job, sizing *new* trades); this is just turning the
// broker's own raw numbers into an accurate point-in-time read, the same
// job GetOpenPositions does for positions.
func (a *CTraderAdapter) GetAccountSnapshot(ctx context.Context, handle domain.ConnectionHandle) (domain.AccountSnapshot, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return domain.AccountSnapshot{}, err
	}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()

	traderReq := &openapi.ProtoOATraderReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	traderResp := &openapi.ProtoOATraderRes{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_TRADER_REQ), traderReq, traderResp); err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("ctrader: fetch trader info: %w", err)
	}
	trader := traderResp.GetTrader()

	reconcileReq := &openapi.ProtoOAReconcileReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	reconcileResp := &openapi.ProtoOAReconcileRes{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_REQ), reconcileReq, reconcileResp); err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("ctrader: reconcile positions: %w", err)
	}

	balance := scaleMoney(trader.GetBalance(), trader.GetMoneyDigits())

	var usedMargin float64
	var unrealizedPnL float64
	for _, p := range reconcileResp.GetPosition() {
		usedMargin += scaleMoney(int64(p.GetUsedMargin()), p.GetMoneyDigits())
		pnl, err := a.unrealizedPnL(ctx, conn, p)
		if err != nil {
			// A single symbol's price fetch failing shouldn't blank out the
			// whole snapshot — log and treat that position's P&L as
			// unknown (0) rather than fail the entire call.
			a.logger.Warn("ctrader: could not compute unrealized P&L for a position", "positionId", p.GetPositionId(), "error", err)
			continue
		}
		unrealizedPnL += pnl
	}

	equity := balance + unrealizedPnL
	var marginLevelPct *float64
	if usedMargin > 0 {
		v := (equity / usedMargin) * 100
		marginLevelPct = &v
	}

	return domain.AccountSnapshot{
		BrokerAccountID: conn.credential.AccountID,
		Currency:        "", // needs the same assetId->currency-code lookup GetSymbolSpecification's MarginCurrency does — confirm during live verification
		Balance:         balance,
		Equity:          equity,
		UsedMargin:      usedMargin,
		FreeMargin:      equity - usedMargin,
		MarginLevelPct:  marginLevelPct,
		AsOf:            time.Now().UTC().Format(time.RFC3339Nano),
		Leverage:        formatLeverage(trader.GetLeverageInCents()),
	}, nil
}

// formatLeverage turns cTrader's own leverageInCents encoding (its own doc-comment: "If leverage =
// 1:50 then value = 5000") into a plain "1:N" ratio string. The trader object fetched above already
// carries this — it was simply never read past Balance/MoneyDigits until now.
func formatLeverage(leverageInCents uint32) string {
	if leverageInCents == 0 {
		return ""
	}
	return fmt.Sprintf("1:%d", leverageInCents/100)
}

// unrealizedPnL computes one position's real unrealized profit/loss:
// (currentPrice - openPrice) × volumeInUnits × (+1 for BUY, -1 for SELL).
// currentPrice comes from conn.latestSpot, kept warm by handleSpotEvent —
// Connect already subscribes to every symbol with an open position (see
// subscribeOpenPositionSpots), so this is normally populated by the time
// GetAccountSnapshot is called. If a position's symbol genuinely has no
// cached tick yet (a real but narrow race right after Connect, before the
// first tick arrives), this position contributes 0 unrealized P&L rather
// than blocking the whole snapshot on one missing quote — Balance and
// UsedMargin (both broker-reported directly, no pricing needed) are
// unaffected either way.
func (a *CTraderAdapter) unrealizedPnL(ctx context.Context, conn *connection, position *openapi.ProtoOAPosition) (float64, error) {
	trade := position.GetTradeData()
	symbolID := trade.GetSymbolId()

	conn.spotMu.RLock()
	spot, haveSpot := conn.latestSpot[symbolID]
	conn.spotMu.RUnlock()
	if !haveSpot {
		if err := a.subscribeSpots(ctx, conn, []int64{symbolID}); err != nil {
			return 0, fmt.Errorf("ctrader: subscribe spot for unrealized P&L: %w", err)
		}
		return 0, nil // first tick hasn't arrived yet — see doc-comment
	}

	volumeUnits := float64(trade.GetVolume()) / 100
	direction := 1.0
	currentPrice := spot.bid // closing a BUY realizes at bid
	if trade.GetTradeSide() == openapi.ProtoOATradeSide_SELL {
		direction = -1.0
		currentPrice = spot.ask // closing a SELL realizes at ask
	}
	return (currentPrice - position.GetPrice()) * volumeUnits * direction, nil
}

// GetOpenPositions lists every real open position on the account.
func (a *CTraderAdapter) GetOpenPositions(ctx context.Context, handle domain.ConnectionHandle) ([]domain.NormalizedPosition, error) {
	conn, err := a.lookup(handle)
	if err != nil {
		return nil, err
	}
	conn.mu.Lock()
	client := conn.client
	conn.mu.Unlock()

	req := &openapi.ProtoOAReconcileReq{CtidTraderAccountId: &conn.credential.CtidTraderAccountID}
	resp := &openapi.ProtoOAReconcileRes{}
	if err := client.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_REQ), req, resp); err != nil {
		return nil, fmt.Errorf("ctrader: reconcile positions: %w", err)
	}

	positions := make([]domain.NormalizedPosition, 0, len(resp.GetPosition()))
	for _, p := range resp.GetPosition() {
		normalized, _, err := a.mapPosition(ctx, conn, p)
		if err != nil {
			a.logger.Warn("ctrader: skipping a position whose symbol could not be resolved", "positionId", p.GetPositionId(), "error", err)
			continue
		}
		positions = append(positions, normalized)
	}
	return positions, nil
}
