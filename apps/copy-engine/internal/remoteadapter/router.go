// Package remoteadapter abstracts "the right adapter for broker type X" for
// TICKET-106's real cross-service dispatch: apps/broker-adapters (cTrader)
// and apps/mt5-bridge-gateway (MT5/MT4) are separate deployed Go binaries
// with no shared code (Go's internal/ package rule makes cross-importing
// their adapter packages compile-time impossible), so dispatch.go reaches
// them over HTTP via the new internal PlaceOrder/GetAccountSnapshot routes
// each service now exposes. HTTPClient is the production implementation;
// LocalAdapter is a test-only in-process implementation that lets every
// existing integration test keep using stubadapter, running the exact same
// dispatch.go code the real HTTP path runs.
package remoteadapter

import (
	"context"
	"fmt"

	domain "github.com/avison9/nectrix/go-domain"
)

// RemoteAdapter is what dispatch.go needs per broker type: a live
// GetAccountSnapshot (both master and follower sides), a live PlaceOrder
// (follower side only), TICKET-107's live ModifyPosition/ClosePosition
// (follower side only, for SL/TP sync and partial/full close), and --
// TICKET-109 -- a live GetOpenPositions (both sides, for reconciliation's
// diff-against-ground-truth).
type RemoteAdapter interface {
	GetAccountSnapshot(ctx context.Context, brokerAccountID string) (domain.AccountSnapshot, error)
	PlaceOrder(ctx context.Context, brokerAccountID string, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error)
	// ModifyPosition changes a follower's own SL/TP -- docs/08-copy-trading-engine.md §8.7.
	ModifyPosition(ctx context.Context, brokerAccountID, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error)
	// ClosePosition closes all (volume == nil) or part (volume != nil) of a
	// follower's position -- docs/09-money-management-risk-formulas.md §9.5.
	ClosePosition(ctx context.Context, brokerAccountID, positionID string, volume *float64) (domain.NormalizedOrderResult, error)
	// GetOpenPositions is reconciliation's ground truth -- docs/08 §8.9 /
	// appendix-a-copy-engine-pseudocode.md §A.8.
	GetOpenPositions(ctx context.Context, brokerAccountID string) ([]domain.NormalizedPosition, error)
}

// Router picks the RemoteAdapter for a domain.BrokerType -- master and
// follower accounts in a single relationship may be on different broker
// types (the whole point of TICKET-106's cross-broker acceptance criteria).
type Router struct {
	byBrokerType map[domain.BrokerType]RemoteAdapter
}

func NewRouter(byBrokerType map[domain.BrokerType]RemoteAdapter) *Router {
	return &Router{byBrokerType: byBrokerType}
}

func (r *Router) For(brokerType domain.BrokerType) (RemoteAdapter, error) {
	adapter, ok := r.byBrokerType[brokerType]
	if !ok {
		return nil, fmt.Errorf("remoteadapter: no RemoteAdapter registered for broker type %q", brokerType)
	}
	return adapter, nil
}
