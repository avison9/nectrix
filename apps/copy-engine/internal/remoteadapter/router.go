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
// GetAccountSnapshot (both master and follower sides) and a live PlaceOrder
// (follower side only -- no ModifyPosition/ClosePosition, TICKET-107's job).
type RemoteAdapter interface {
	GetAccountSnapshot(ctx context.Context, brokerAccountID string) (domain.AccountSnapshot, error)
	PlaceOrder(ctx context.Context, brokerAccountID string, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error)
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
