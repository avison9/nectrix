package remoteadapter_test

import (
	"context"
	"testing"

	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
)

func TestLocalAdapter_GetAccountSnapshot_UsesRegisteredHandle(t *testing.T) {
	ctx := context.Background()
	adapter := stubadapter.New()
	handle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: "acct-1"})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}

	local := remoteadapter.NewLocalAdapter(adapter, map[string]domain.ConnectionHandle{"acct-1": handle})

	snapshot, err := local.GetAccountSnapshot(ctx, "acct-1")
	if err != nil {
		t.Fatalf("GetAccountSnapshot returned error: %v", err)
	}
	if snapshot.BrokerAccountID != "acct-1" {
		t.Fatalf("snapshot.BrokerAccountID = %q, want acct-1", snapshot.BrokerAccountID)
	}
}

func TestLocalAdapter_GetAccountSnapshot_UnknownAccount_ReturnsError(t *testing.T) {
	local := remoteadapter.NewLocalAdapter(stubadapter.New(), map[string]domain.ConnectionHandle{})

	if _, err := local.GetAccountSnapshot(context.Background(), "unknown"); err == nil {
		t.Fatal("expected an error for an account with no registered handle")
	}
}

func TestLocalAdapter_PlaceOrder_UsesRegisteredHandle(t *testing.T) {
	ctx := context.Background()
	adapter := stubadapter.New()
	handle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: "acct-1"})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}

	local := remoteadapter.NewLocalAdapter(adapter, map[string]domain.ConnectionHandle{"acct-1": handle})

	result, err := local.PlaceOrder(ctx, "acct-1", domain.NormalizedOrderRequest{
		IdempotencyKey: "idem-1",
		Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		Direction:      domain.TradeDirectionBuy,
		VolumeLots:     1.0,
	})
	if err != nil {
		t.Fatalf("PlaceOrder returned error: %v", err)
	}
	if !result.Success {
		t.Fatalf("expected a successful stub fill, got %+v", result)
	}
}

func TestLocalAdapter_PlaceOrder_UnknownAccount_ReturnsError(t *testing.T) {
	local := remoteadapter.NewLocalAdapter(stubadapter.New(), map[string]domain.ConnectionHandle{})

	if _, err := local.PlaceOrder(context.Background(), "unknown", domain.NormalizedOrderRequest{}); err == nil {
		t.Fatal("expected an error for an account with no registered handle")
	}
}

func TestLocalAdapter_ModifyPosition_UsesRegisteredHandle(t *testing.T) {
	ctx := context.Background()
	adapter := stubadapter.New()
	handle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: "acct-1"})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}

	local := remoteadapter.NewLocalAdapter(adapter, map[string]domain.ConnectionHandle{"acct-1": handle})

	sl := 1.0950
	result, err := local.ModifyPosition(ctx, "acct-1", "pos-1", domain.SLTPChange{SLPrice: &sl})
	if err != nil {
		t.Fatalf("ModifyPosition returned error: %v", err)
	}
	if !result.Success {
		t.Fatalf("expected a successful stub modify, got %+v", result)
	}
}

func TestLocalAdapter_ModifyPosition_UnknownAccount_ReturnsError(t *testing.T) {
	local := remoteadapter.NewLocalAdapter(stubadapter.New(), map[string]domain.ConnectionHandle{})

	if _, err := local.ModifyPosition(context.Background(), "unknown", "pos-1", domain.SLTPChange{}); err == nil {
		t.Fatal("expected an error for an account with no registered handle")
	}
}

func TestLocalAdapter_ClosePosition_UsesRegisteredHandle(t *testing.T) {
	ctx := context.Background()
	adapter := stubadapter.New()
	handle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: "acct-1"})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}

	local := remoteadapter.NewLocalAdapter(adapter, map[string]domain.ConnectionHandle{"acct-1": handle})

	volume := 0.5
	result, err := local.ClosePosition(ctx, "acct-1", "pos-1", &volume)
	if err != nil {
		t.Fatalf("ClosePosition returned error: %v", err)
	}
	if !result.Success {
		t.Fatalf("expected a successful stub close, got %+v", result)
	}
}

func TestLocalAdapter_ClosePosition_UnknownAccount_ReturnsError(t *testing.T) {
	local := remoteadapter.NewLocalAdapter(stubadapter.New(), map[string]domain.ConnectionHandle{})

	if _, err := local.ClosePosition(context.Background(), "unknown", "pos-1", nil); err == nil {
		t.Fatal("expected an error for an account with no registered handle")
	}
}

func TestLocalAdapter_GetOpenPositions_UsesRegisteredHandle(t *testing.T) {
	ctx := context.Background()
	adapter := stubadapter.New()
	handle, err := adapter.Connect(ctx, domain.BrokerCredentials{BrokerType: adapter.BrokerType(), AccountID: "acct-1"})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}

	local := remoteadapter.NewLocalAdapter(adapter, map[string]domain.ConnectionHandle{"acct-1": handle})

	if _, err := local.GetOpenPositions(ctx, "acct-1"); err != nil {
		t.Fatalf("GetOpenPositions returned error: %v", err)
	}
}

func TestLocalAdapter_GetOpenPositions_UnknownAccount_ReturnsError(t *testing.T) {
	local := remoteadapter.NewLocalAdapter(stubadapter.New(), map[string]domain.ConnectionHandle{})

	if _, err := local.GetOpenPositions(context.Background(), "unknown"); err == nil {
		t.Fatal("expected an error for an account with no registered handle")
	}
}
