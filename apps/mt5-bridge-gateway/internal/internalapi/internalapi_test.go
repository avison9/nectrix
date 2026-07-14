package internalapi_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/internalapi"
	domain "github.com/avison9/nectrix/go-domain"
)

// fakeBrokerAdapter implements the full domain.BrokerAdapter interface;
// only GetAccountSnapshot/PlaceOrder are exercised by this package.
type fakeBrokerAdapter struct {
	brokerType domain.BrokerType

	snapshot          domain.AccountSnapshot
	snapshotErr       error
	gotSnapshotHandle domain.ConnectionHandle

	orderResult    domain.NormalizedOrderResult
	orderErr       error
	gotOrder       domain.NormalizedOrderRequest
	gotOrderHandle domain.ConnectionHandle
}

func (f *fakeBrokerAdapter) BrokerType() domain.BrokerType { return f.brokerType }
func (f *fakeBrokerAdapter) Connect(ctx context.Context, credentials domain.BrokerCredentials) (domain.ConnectionHandle, error) {
	return domain.ConnectionHandle{}, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) Disconnect(ctx context.Context, handle domain.ConnectionHandle) error {
	return errors.New("not implemented")
}
func (f *fakeBrokerAdapter) HealthCheck(ctx context.Context, handle domain.ConnectionHandle) (domain.ConnectionHealth, error) {
	return domain.ConnectionHealth{}, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) GetAccountSnapshot(ctx context.Context, handle domain.ConnectionHandle) (domain.AccountSnapshot, error) {
	f.gotSnapshotHandle = handle
	return f.snapshot, f.snapshotErr
}
func (f *fakeBrokerAdapter) GetOpenPositions(ctx context.Context, handle domain.ConnectionHandle) ([]domain.NormalizedPosition, error) {
	return nil, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) StreamTradeEvents(ctx context.Context, handle domain.ConnectionHandle, onEvent func(context.Context, domain.NormalizedTradeEvent) error) (domain.Subscription, error) {
	return nil, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) PlaceOrder(ctx context.Context, handle domain.ConnectionHandle, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	f.gotOrderHandle = handle
	f.gotOrder = order
	return f.orderResult, f.orderErr
}
func (f *fakeBrokerAdapter) ModifyPosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	return domain.NormalizedOrderResult{}, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) ClosePosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	return domain.NormalizedOrderResult{}, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) ResolveSymbol(ctx context.Context, brokerSymbol string) (domain.NormalizedSymbol, error) {
	return domain.NormalizedSymbol{}, errors.New("not implemented")
}
func (f *fakeBrokerAdapter) GetSymbolSpecification(ctx context.Context, symbol domain.NormalizedSymbol) (domain.SymbolSpec, error) {
	return domain.SymbolSpec{}, errors.New("not implemented")
}

const sharedSecret = "test-internal-token"

func doRequest(t *testing.T, mux http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("X-Internal-Service-Token", token)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

// --- GET .../snapshot ---

func TestGetAccountSnapshot_MT5_Success(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, snapshot: domain.AccountSnapshot{BrokerAccountID: "acct-1", Currency: "USD", Balance: 5000, Equity: 4900}}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/snapshot?platform=MT5", sharedSecret, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt5.gotSnapshotHandle.AccountID != "acct-1" {
		t.Fatalf("MT5 adapter called with handle %+v, want AccountID=acct-1", mt5.gotSnapshotHandle)
	}
	if mt4.gotSnapshotHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT4 adapter must not have been called, got handle %+v", mt4.gotSnapshotHandle)
	}

	var got domain.AccountSnapshot
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if got.Balance != 5000 || got.Equity != 4900 {
		t.Fatalf("got snapshot %+v, want balance=5000 equity=4900", got)
	}
}

func TestGetAccountSnapshot_MT4_RoutesToMT4Adapter(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4, snapshot: domain.AccountSnapshot{BrokerAccountID: "acct-2", Balance: 1000}}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-2/snapshot?platform=MT4", sharedSecret, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt4.gotSnapshotHandle.AccountID != "acct-2" {
		t.Fatalf("MT4 adapter called with handle %+v, want AccountID=acct-2", mt4.gotSnapshotHandle)
	}
	if mt5.gotSnapshotHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT5 adapter must not have been called, got handle %+v", mt5.gotSnapshotHandle)
	}
}

func TestGetAccountSnapshot_UnrecognizedPlatform_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/snapshot?platform=CTRADER", sharedSecret, "")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestGetAccountSnapshot_NoSession_NotFound(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, snapshotErr: fmt.Errorf("mtadapter(MT5): no live EA session for broker account acct-1: %w", eabridge.ErrNoSession)}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/snapshot?platform=MT5", sharedSecret, "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestGetAccountSnapshot_OtherAdapterError_BadGateway(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, snapshotErr: errors.New("eabridge: request timed out")}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/snapshot?platform=MT5", sharedSecret, "")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

func TestGetAccountSnapshot_MissingToken_Unauthorized(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/snapshot?platform=MT5", "", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestEmptySharedSecretConfigRejectsEverything(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, "", nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/snapshot?platform=MT5", "", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401 when sharedSecret is unconfigured", rec.Code)
	}
}

// --- POST .../orders ---

func TestPlaceOrder_MT5_Success(t *testing.T) {
	filledPrice := 1.10005
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, orderResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-1", FilledPrice: &filledPrice}}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	body := `{"platform":"MT5","order":{"idempotencyKey":"idem-1","followerBrokerAccountId":"acct-1","symbol":{"canonicalCode":"EURUSD","assetClass":"FX"},"direction":"BUY","volumeLots":1.5,"maxSlippagePips":5,"clientOrderTag":"rel-1:pos-1"}}`
	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/orders", sharedSecret, body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt5.gotOrderHandle.AccountID != "acct-1" {
		t.Fatalf("MT5 adapter called with handle %+v, want AccountID=acct-1", mt5.gotOrderHandle)
	}
	if mt5.gotOrder.VolumeLots != 1.5 {
		t.Fatalf("PlaceOrder called with unexpected order: %+v", mt5.gotOrder)
	}
	if mt4.gotOrderHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT4 adapter must not have been called")
	}

	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if _, hasRaw := got["rawBrokerResponse"]; hasRaw {
		t.Fatalf("response must not include rawBrokerResponse, got %v", got)
	}
	if got["brokerPositionId"] != "pos-1" {
		t.Fatalf("brokerPositionId = %v, want pos-1", got["brokerPositionId"])
	}
}

func TestPlaceOrder_MT4_RoutesToMT4Adapter(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4, orderResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-2"}}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	body := `{"platform":"MT4","order":{"idempotencyKey":"idem-2"}}`
	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-2/orders", sharedSecret, body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt4.gotOrderHandle.AccountID != "acct-2" {
		t.Fatalf("MT4 adapter called with handle %+v, want AccountID=acct-2", mt4.gotOrderHandle)
	}
	if mt5.gotOrderHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT5 adapter must not have been called")
	}
}

func TestPlaceOrder_UnrecognizedPlatform_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/orders", sharedSecret, `{"platform":"CTRADER","order":{}}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPlaceOrder_MismatchedFollowerAccountId_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	body := `{"platform":"MT5","order":{"followerBrokerAccountId":"acct-2"}}`
	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/orders", sharedSecret, body)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPlaceOrder_InvalidJSON_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/orders", sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPlaceOrder_NoSession_NotFound(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, orderErr: fmt.Errorf("mtadapter(MT5): no live EA session for broker account acct-1: %w", eabridge.ErrNoSession)}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/orders", sharedSecret, `{"platform":"MT5","order":{}}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestPlaceOrder_OtherAdapterError_BadGateway(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, orderErr: errors.New("eabridge: request timed out")}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/orders", sharedSecret, `{"platform":"MT5","order":{}}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}
