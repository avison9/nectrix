package remoteadapter_test

import (
	"context"
	"testing"

	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	domain "github.com/avison9/nectrix/go-domain"
)

type fakeRemoteAdapter struct {
	name string
}

func (f *fakeRemoteAdapter) GetAccountSnapshot(ctx context.Context, brokerAccountID string) (domain.AccountSnapshot, error) {
	return domain.AccountSnapshot{}, nil
}

func (f *fakeRemoteAdapter) PlaceOrder(ctx context.Context, brokerAccountID string, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	return domain.NormalizedOrderResult{}, nil
}

func (f *fakeRemoteAdapter) ModifyPosition(ctx context.Context, brokerAccountID, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	return domain.NormalizedOrderResult{}, nil
}

func (f *fakeRemoteAdapter) ClosePosition(ctx context.Context, brokerAccountID, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	return domain.NormalizedOrderResult{}, nil
}

func TestRouter_For_ReturnsRegisteredAdapter(t *testing.T) {
	cTrader := &fakeRemoteAdapter{name: "ctrader"}
	mt5 := &fakeRemoteAdapter{name: "mt5"}
	router := remoteadapter.NewRouter(map[domain.BrokerType]remoteadapter.RemoteAdapter{
		domain.BrokerTypeCTrader: cTrader,
		domain.BrokerTypeMT5:     mt5,
	})

	got, err := router.For(domain.BrokerTypeCTrader)
	if err != nil {
		t.Fatalf("For(CTRADER) returned error: %v", err)
	}
	if got != remoteadapter.RemoteAdapter(cTrader) {
		t.Fatalf("For(CTRADER) returned a different adapter than registered")
	}

	got, err = router.For(domain.BrokerTypeMT5)
	if err != nil {
		t.Fatalf("For(MT5) returned error: %v", err)
	}
	if got != remoteadapter.RemoteAdapter(mt5) {
		t.Fatalf("For(MT5) returned a different adapter than registered")
	}
}

func TestRouter_For_UnregisteredBrokerType_ReturnsError(t *testing.T) {
	router := remoteadapter.NewRouter(map[domain.BrokerType]remoteadapter.RemoteAdapter{
		domain.BrokerTypeCTrader: &fakeRemoteAdapter{},
	})

	if _, err := router.For(domain.BrokerTypeMT4); err == nil {
		t.Fatal("expected an error for an unregistered broker type (MT4)")
	}
}
