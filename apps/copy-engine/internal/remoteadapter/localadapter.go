package remoteadapter

import (
	"context"
	"fmt"

	domain "github.com/avison9/nectrix/go-domain"
)

// LocalAdapter wraps an already-constructed domain.BrokerAdapter plus its
// already-Connect()-ed handles (keyed by broker_accounts.id) as a
// RemoteAdapter -- test-only. This is what lets existing integration tests
// keep using stubadapter (a single in-process domain.BrokerAdapter, no
// network hop) while running through the exact same Router/RemoteAdapter
// seam dispatch.go's real code uses, rather than a second, parallel
// test-only code path.
type LocalAdapter struct {
	adapter domain.BrokerAdapter
	handles map[string]domain.ConnectionHandle // keyed by broker_accounts.id
}

func NewLocalAdapter(adapter domain.BrokerAdapter, handles map[string]domain.ConnectionHandle) *LocalAdapter {
	return &LocalAdapter{adapter: adapter, handles: handles}
}

func (l *LocalAdapter) handleFor(brokerAccountID string) (domain.ConnectionHandle, error) {
	handle, ok := l.handles[brokerAccountID]
	if !ok {
		return domain.ConnectionHandle{}, fmt.Errorf("remoteadapter: LocalAdapter has no handle for broker account %s", brokerAccountID)
	}
	return handle, nil
}

func (l *LocalAdapter) GetAccountSnapshot(ctx context.Context, brokerAccountID string) (domain.AccountSnapshot, error) {
	handle, err := l.handleFor(brokerAccountID)
	if err != nil {
		return domain.AccountSnapshot{}, err
	}
	return l.adapter.GetAccountSnapshot(ctx, handle)
}

func (l *LocalAdapter) PlaceOrder(ctx context.Context, brokerAccountID string, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	handle, err := l.handleFor(brokerAccountID)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	return l.adapter.PlaceOrder(ctx, handle, order)
}

func (l *LocalAdapter) ModifyPosition(ctx context.Context, brokerAccountID, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	handle, err := l.handleFor(brokerAccountID)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	return l.adapter.ModifyPosition(ctx, handle, positionID, changes)
}

func (l *LocalAdapter) ClosePosition(ctx context.Context, brokerAccountID, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	handle, err := l.handleFor(brokerAccountID)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	return l.adapter.ClosePosition(ctx, handle, positionID, volume)
}
