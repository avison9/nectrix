package pipeline

import (
	"context"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
)

// computeUnrealizedPnL (TICKET-124) is computeRealizedPnL applied to a live price instead of a
// real close price -- deliberately a thin wrapper, not a copy, so the two can never compute a
// different number for the same position/price pair. A distinct name exists purely so traces/
// logs at this call site read as "unrealized," not "realized," for a position that hasn't
// actually closed.
func (p *Pipeline) computeUnrealizedPnL(ctx context.Context, followerBrokerAccountID uuid.UUID, symbol domain.NormalizedSymbol, direction domain.TradeDirection, volumeLots, openPrice, currentPrice float64) *float64 {
	return p.computeRealizedPnL(ctx, followerBrokerAccountID, symbol, direction, volumeLots, openPrice, currentPrice)
}

// UnrealizedPnLItem is one open position's inputs for a batch unrealized-P&L computation. ID is
// a caller-supplied correlation key (e.g. copied_trades.id) -- echoed back in the result, never
// assumed positional. AssetClass is carried on the wire rather than re-derived server-side: it
// only ever comes from a broker adapter's own resolved symbol data (GetOpenPositions), and the
// caller (core-app) already has that exact value from the same call -- there is no independent
// canonicalSymbol -> AssetClass lookup anywhere in this codebase to re-derive it from.
type UnrealizedPnLItem struct {
	ID                      string  `json:"id"`
	FollowerBrokerAccountID string  `json:"followerBrokerAccountId"`
	CanonicalSymbol         string  `json:"canonicalSymbol"`
	AssetClass              string  `json:"assetClass"`
	Direction               string  `json:"direction"`
	VolumeLots              float64 `json:"volumeLots"`
	OpenPrice               float64 `json:"openPrice"`
	CurrentPrice            float64 `json:"currentPrice"`
}

// UnrealizedPnLResult is one item's computed result, keyed back to the request's ID. UnrealizedPnl
// is nil whenever computeUnrealizedPnL itself returns nil (unmapped symbol, unresolvable
// account currency, or a failed FX lookup) -- an honest "couldn't compute this one," never a
// fabricated 0.
type UnrealizedPnLResult struct {
	ID            string   `json:"id"`
	UnrealizedPnl *float64 `json:"unrealizedPnl"`
}

// ComputeUnrealizedPnLBatch is TICKET-124's internal HTTP surface for core-app: one request per
// Trade History/Live Activity page load, one item per open position, instead of a round trip per
// row. Each item is independent -- one malformed/unresolvable item never fails the whole batch.
func (p *Pipeline) ComputeUnrealizedPnLBatch(ctx context.Context, items []UnrealizedPnLItem) []UnrealizedPnLResult {
	results := make([]UnrealizedPnLResult, 0, len(items))
	for _, item := range items {
		accountID, err := uuid.Parse(item.FollowerBrokerAccountID)
		if err != nil {
			results = append(results, UnrealizedPnLResult{ID: item.ID})
			continue
		}
		symbol := domain.NormalizedSymbol{CanonicalCode: item.CanonicalSymbol, AssetClass: domain.AssetClass(item.AssetClass)}
		pnl := p.computeUnrealizedPnL(ctx, accountID, symbol, domain.TradeDirection(item.Direction), item.VolumeLots, item.OpenPrice, item.CurrentPrice)
		results = append(results, UnrealizedPnLResult{ID: item.ID, UnrealizedPnl: pnl})
	}
	return results
}
