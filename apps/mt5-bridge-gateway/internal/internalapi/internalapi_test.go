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

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/internalapi"
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

	modifyResult     domain.NormalizedOrderResult
	modifyErr        error
	gotModifyHandle  domain.ConnectionHandle
	gotModifyPosID   string
	gotModifyChanges domain.SLTPChange

	closeResult    domain.NormalizedOrderResult
	closeErr       error
	gotCloseHandle domain.ConnectionHandle
	gotClosePosID  string
	gotCloseVolume *float64

	positions          []domain.NormalizedPosition
	positionsErr       error
	gotPositionsHandle domain.ConnectionHandle
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
	f.gotPositionsHandle = handle
	return f.positions, f.positionsErr
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
	f.gotModifyHandle = handle
	f.gotModifyPosID = positionID
	f.gotModifyChanges = changes
	return f.modifyResult, f.modifyErr
}
func (f *fakeBrokerAdapter) ClosePosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	f.gotCloseHandle = handle
	f.gotClosePosID = positionID
	f.gotCloseVolume = volume
	return f.closeResult, f.closeErr
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

// --- TICKET-107: POST .../positions/{positionId}/modify ---

func TestModifyPosition_MT5_Success(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, modifyResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-1"}}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	body := `{"platform":"MT5","slPrice":1.0950,"tpPrice":1.1050}`
	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/modify", sharedSecret, body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt5.gotModifyHandle.AccountID != "acct-1" || mt5.gotModifyPosID != "pos-1" {
		t.Fatalf("MT5 adapter called with handle=%+v positionID=%q, want AccountID=acct-1 positionID=pos-1", mt5.gotModifyHandle, mt5.gotModifyPosID)
	}
	if mt5.gotModifyChanges.SLPrice == nil || *mt5.gotModifyChanges.SLPrice != 1.0950 {
		t.Fatalf("ModifyPosition called with SLPrice %v, want 1.0950", mt5.gotModifyChanges.SLPrice)
	}
	if mt4.gotModifyHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT4 adapter must not have been called")
	}
}

func TestModifyPosition_MT4_RoutesToMT4Adapter(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4, modifyResult: domain.NormalizedOrderResult{Success: true}}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-2/positions/pos-2/modify", sharedSecret, `{"platform":"MT4"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt4.gotModifyHandle.AccountID != "acct-2" || mt4.gotModifyPosID != "pos-2" {
		t.Fatalf("MT4 adapter called with handle=%+v positionID=%q, want AccountID=acct-2 positionID=pos-2", mt4.gotModifyHandle, mt4.gotModifyPosID)
	}
	if mt5.gotModifyHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT5 adapter must not have been called")
	}
}

func TestModifyPosition_UnrecognizedPlatform_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/modify", sharedSecret, `{"platform":"CTRADER"}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestModifyPosition_InvalidJSON_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/modify", sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestModifyPosition_NoSession_NotFound(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, modifyErr: fmt.Errorf("mtadapter(MT5): no live EA session for broker account acct-1: %w", eabridge.ErrNoSession)}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/modify", sharedSecret, `{"platform":"MT5"}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestModifyPosition_OtherAdapterError_BadGateway(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, modifyErr: errors.New("eabridge: request timed out")}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/modify", sharedSecret, `{"platform":"MT5"}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

// --- TICKET-107: POST .../positions/{positionId}/close ---

func TestClosePosition_MT5_FullClose_Success(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, closeResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-1"}}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/close", sharedSecret, `{"platform":"MT5"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt5.gotCloseVolume != nil {
		t.Fatalf("ClosePosition called with volume %v, want nil (full close)", *mt5.gotCloseVolume)
	}
}

func TestClosePosition_MT4_RoutesToMT4Adapter_PartialClose(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4, closeResult: domain.NormalizedOrderResult{Success: true}}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-2/positions/pos-2/close", sharedSecret, `{"platform":"MT4","volumeLots":0.5}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt4.gotCloseVolume == nil || *mt4.gotCloseVolume != 0.5 {
		t.Fatalf("ClosePosition called with volume %v, want 0.5", mt4.gotCloseVolume)
	}
	if mt5.gotCloseHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT5 adapter must not have been called")
	}
}

func TestClosePosition_UnrecognizedPlatform_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/close", sharedSecret, `{"platform":"CTRADER"}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestClosePosition_InvalidJSON_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/close", sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestClosePosition_NoSession_NotFound(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, closeErr: fmt.Errorf("mtadapter(MT5): no live EA session for broker account acct-1: %w", eabridge.ErrNoSession)}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/close", sharedSecret, `{"platform":"MT5"}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestClosePosition_OtherAdapterError_BadGateway(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, closeErr: errors.New("eabridge: request timed out")}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodPost, "/internal/mt/accounts/acct-1/positions/pos-1/close", sharedSecret, `{"platform":"MT5"}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

// --- TICKET-109: GET .../positions ---

func TestGetOpenPositions_MT5_Success(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, positions: []domain.NormalizedPosition{
		{BrokerPositionID: "pos-1", Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX}, Direction: domain.TradeDirectionBuy, VolumeLots: 1.5, OpenPrice: 1.2000},
	}}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/positions?platform=MT5", sharedSecret, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt5.gotPositionsHandle.AccountID != "acct-1" {
		t.Fatalf("MT5 adapter called with handle %+v, want AccountID=acct-1", mt5.gotPositionsHandle)
	}
	if mt4.gotPositionsHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT4 adapter must not have been called")
	}

	var got []domain.NormalizedPosition
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if len(got) != 1 || got[0].BrokerPositionID != "pos-1" {
		t.Fatalf("got positions %+v, want one position pos-1", got)
	}
}

func TestGetOpenPositions_MT4_RoutesToMT4Adapter(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5}
	mt4 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT4, positions: []domain.NormalizedPosition{{BrokerPositionID: "pos-2"}}}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: mt4}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-2/positions?platform=MT4", sharedSecret, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if mt4.gotPositionsHandle.AccountID != "acct-2" {
		t.Fatalf("MT4 adapter called with handle %+v, want AccountID=acct-2", mt4.gotPositionsHandle)
	}
	if mt5.gotPositionsHandle != (domain.ConnectionHandle{}) {
		t.Fatalf("MT5 adapter must not have been called")
	}
}

func TestGetOpenPositions_UnrecognizedPlatform_BadRequest(t *testing.T) {
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: &fakeBrokerAdapter{}, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/positions?platform=CTRADER", sharedSecret, "")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestGetOpenPositions_NoSession_NotFound(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, positionsErr: fmt.Errorf("mtadapter(MT5): no live EA session for broker account acct-1: %w", eabridge.ErrNoSession)}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/positions?platform=MT5", sharedSecret, "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestGetOpenPositions_OtherAdapterError_BadGateway(t *testing.T) {
	mt5 := &fakeBrokerAdapter{brokerType: domain.BrokerTypeMT5, positionsErr: errors.New("eabridge: request timed out")}
	mux := internalapi.NewMux(internalapi.PlatformAdapters{MT5: mt5, MT4: &fakeBrokerAdapter{}}, sharedSecret, nil)

	rec := doRequest(t, mux, http.MethodGet, "/internal/mt/accounts/acct-1/positions?platform=MT5", sharedSecret, "")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}
