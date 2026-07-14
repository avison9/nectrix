package internalapi_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/avison9/nectrix/broker-adapters/internal/ctrader"
	"github.com/avison9/nectrix/broker-adapters/internal/internalapi"
	domain "github.com/avison9/nectrix/go-domain"
)

type fakeAccountLister struct {
	accounts []ctrader.AccountSummary
	err      error
	gotToken string
}

func (f *fakeAccountLister) ListAccountsByAccessToken(ctx context.Context, accessToken string) ([]ctrader.AccountSummary, error) {
	f.gotToken = accessToken
	return f.accounts, f.err
}

// fakeHandleProvider mirrors *reconcile.Loop.HandleFor without needing a
// real Loop.
type fakeHandleProvider struct {
	handles map[string]domain.ConnectionHandle
}

func (f *fakeHandleProvider) HandleFor(brokerAccountID string) (domain.ConnectionHandle, bool) {
	h, ok := f.handles[brokerAccountID]
	return h, ok
}

// fakeBrokerAdapter implements the full domain.BrokerAdapter interface;
// only GetAccountSnapshot/PlaceOrder are exercised by this package, the rest
// are unused stubs.
type fakeBrokerAdapter struct {
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

func (f *fakeBrokerAdapter) BrokerType() domain.BrokerType { return domain.BrokerTypeCTrader }
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

func doRequest(t *testing.T, mux http.Handler, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/internal/ctrader/accounts", strings.NewReader(body))
	if token != "" {
		req.Header.Set("X-Internal-Service-Token", token)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func doPathRequest(t *testing.T, mux http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("X-Internal-Service-Token", token)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func TestListAccounts_Success(t *testing.T) {
	lister := &fakeAccountLister{accounts: []ctrader.AccountSummary{
		{CtidTraderAccountID: 42, IsLive: false, TraderLogin: 12345, BrokerTitleShort: "IC Markets"},
	}}
	mux := internalapi.NewMux(lister, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `{"accessToken":"tok-1"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if lister.gotToken != "tok-1" {
		t.Fatalf("lister received accessToken = %q, want tok-1", lister.gotToken)
	}

	var got []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("got %d accounts, want 1", len(got))
	}
	if got[0]["ctidTraderAccountId"].(float64) != 42 {
		t.Fatalf("ctidTraderAccountId = %v, want 42", got[0]["ctidTraderAccountId"])
	}
	if got[0]["brokerTitleShort"] != "IC Markets" {
		t.Fatalf("brokerTitleShort = %v, want IC Markets", got[0]["brokerTitleShort"])
	}
}

func TestListAccounts_MissingOrWrongSharedSecretRejected(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	for _, token := range []string{"", "wrong-token"} {
		rec := doRequest(t, mux, token, `{"accessToken":"tok-1"}`)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("token=%q: status = %d, want 401", token, rec.Code)
		}
	}
}

func TestListAccounts_EmptySharedSecretConfigRejectsEverything(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, &fakeHandleProvider{}, &fakeBrokerAdapter{}, "", nil)

	rec := doRequest(t, mux, "", `{"accessToken":"tok-1"}`)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401 when sharedSecret is unconfigured", rec.Code)
	}
}

func TestListAccounts_MissingAccessTokenRejected(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `{}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestListAccounts_InvalidJSONRejected(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestListAccounts_ListerErrorSurfacesAsBadGateway(t *testing.T) {
	lister := &fakeAccountLister{err: errors.New("ctrader: no accounts found")}
	mux := internalapi.NewMux(lister, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `{"accessToken":"tok-1"}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

// --- TICKET-106: GET .../snapshot ---

func TestGetAccountSnapshot_Success(t *testing.T) {
	handle := domain.ConnectionHandle{ID: "h1", BrokerType: domain.BrokerTypeCTrader, AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{snapshot: domain.AccountSnapshot{BrokerAccountID: "acct-1", Currency: "USD", Balance: 10000, Equity: 9800}}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/acct-1/snapshot", sharedSecret, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if adapter.gotSnapshotHandle != handle {
		t.Fatalf("GetAccountSnapshot called with handle %+v, want %+v", adapter.gotSnapshotHandle, handle)
	}

	var got domain.AccountSnapshot
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if got.Balance != 10000 || got.Equity != 9800 {
		t.Fatalf("got snapshot %+v, want balance=10000 equity=9800", got)
	}
}

func TestGetAccountSnapshot_UnknownAccount_NotFound(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/unknown/snapshot", sharedSecret, "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestGetAccountSnapshot_AdapterError_BadGateway(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{snapshotErr: errors.New("ctrader: connection dropped")}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/acct-1/snapshot", sharedSecret, "")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

func TestGetAccountSnapshot_MissingToken_Unauthorized(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/acct-1/snapshot", "", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

// --- TICKET-106: POST .../orders ---

func TestPlaceOrder_Success(t *testing.T) {
	handle := domain.ConnectionHandle{ID: "h1", BrokerType: domain.BrokerTypeCTrader, AccountID: "acct-1"}
	filledPrice := 1.10005
	adapter := &fakeBrokerAdapter{orderResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-1", FilledPrice: &filledPrice}}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	body := `{"platform":"","order":{"idempotencyKey":"idem-1","followerBrokerAccountId":"acct-1","symbol":{"canonicalCode":"EURUSD","assetClass":"FX"},"direction":"BUY","volumeLots":1.5,"maxSlippagePips":5,"clientOrderTag":"rel-1:pos-1"}}`
	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/orders", sharedSecret, body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if adapter.gotOrderHandle != handle {
		t.Fatalf("PlaceOrder called with handle %+v, want %+v", adapter.gotOrderHandle, handle)
	}
	if adapter.gotOrder.VolumeLots != 1.5 || adapter.gotOrder.IdempotencyKey != "idem-1" {
		t.Fatalf("PlaceOrder called with unexpected order: %+v", adapter.gotOrder)
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

func TestPlaceOrder_UnknownAccount_NotFound(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/unknown/orders", sharedSecret, `{"order":{}}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestPlaceOrder_MismatchedFollowerAccountId_BadRequest(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, &fakeBrokerAdapter{}, sharedSecret, nil)

	body := `{"order":{"followerBrokerAccountId":"acct-2"}}`
	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/orders", sharedSecret, body)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPlaceOrder_InvalidJSON_BadRequest(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/orders", sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestPlaceOrder_AdapterError_BadGateway(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{orderErr: errors.New("ctrader: request timed out")}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/orders", sharedSecret, `{"order":{}}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

// --- TICKET-107: POST .../positions/{positionId}/modify ---

func TestModifyPosition_Success(t *testing.T) {
	handle := domain.ConnectionHandle{ID: "h1", BrokerType: domain.BrokerTypeCTrader, AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{modifyResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-1"}}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	body := `{"platform":"","slPrice":1.0950,"tpPrice":1.1050}`
	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/modify", sharedSecret, body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if adapter.gotModifyHandle != handle {
		t.Fatalf("ModifyPosition called with handle %+v, want %+v", adapter.gotModifyHandle, handle)
	}
	if adapter.gotModifyPosID != "pos-1" {
		t.Fatalf("ModifyPosition called with positionID %q, want pos-1", adapter.gotModifyPosID)
	}
	if adapter.gotModifyChanges.SLPrice == nil || *adapter.gotModifyChanges.SLPrice != 1.0950 {
		t.Fatalf("ModifyPosition called with SLPrice %v, want 1.0950", adapter.gotModifyChanges.SLPrice)
	}
	if adapter.gotModifyChanges.TPPrice == nil || *adapter.gotModifyChanges.TPPrice != 1.1050 {
		t.Fatalf("ModifyPosition called with TPPrice %v, want 1.1050", adapter.gotModifyChanges.TPPrice)
	}
}

func TestModifyPosition_UnknownAccount_NotFound(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/unknown/positions/pos-1/modify", sharedSecret, `{}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestModifyPosition_InvalidJSON_BadRequest(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/modify", sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestModifyPosition_AdapterError_BadGateway(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{modifyErr: errors.New("ctrader: request timed out")}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/modify", sharedSecret, `{}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

// --- TICKET-107: POST .../positions/{positionId}/close ---

func TestClosePosition_FullClose_Success(t *testing.T) {
	handle := domain.ConnectionHandle{ID: "h1", BrokerType: domain.BrokerTypeCTrader, AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{closeResult: domain.NormalizedOrderResult{Success: true, BrokerPositionID: "pos-1"}}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/close", sharedSecret, `{"platform":""}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if adapter.gotCloseHandle != handle {
		t.Fatalf("ClosePosition called with handle %+v, want %+v", adapter.gotCloseHandle, handle)
	}
	if adapter.gotClosePosID != "pos-1" {
		t.Fatalf("ClosePosition called with positionID %q, want pos-1", adapter.gotClosePosID)
	}
	if adapter.gotCloseVolume != nil {
		t.Fatalf("ClosePosition called with volume %v, want nil (full close)", *adapter.gotCloseVolume)
	}
}

func TestClosePosition_PartialClose_PassesVolume(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{closeResult: domain.NormalizedOrderResult{Success: true}}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/close", sharedSecret, `{"volumeLots":0.75}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if adapter.gotCloseVolume == nil || *adapter.gotCloseVolume != 0.75 {
		t.Fatalf("ClosePosition called with volume %v, want 0.75", adapter.gotCloseVolume)
	}
}

func TestClosePosition_UnknownAccount_NotFound(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/unknown/positions/pos-1/close", sharedSecret, `{}`)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestClosePosition_InvalidJSON_BadRequest(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/close", sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestClosePosition_AdapterError_BadGateway(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{closeErr: errors.New("ctrader: request timed out")}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodPost, "/internal/ctrader/accounts/acct-1/positions/pos-1/close", sharedSecret, `{}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

// --- TICKET-109: GET .../positions ---

func TestGetOpenPositions_Success(t *testing.T) {
	handle := domain.ConnectionHandle{ID: "h1", BrokerType: domain.BrokerTypeCTrader, AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{positions: []domain.NormalizedPosition{
		{BrokerPositionID: "pos-1", Symbol: domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX}, Direction: domain.TradeDirectionBuy, VolumeLots: 1.5, OpenPrice: 1.2000},
	}}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/acct-1/positions", sharedSecret, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	if adapter.gotPositionsHandle != handle {
		t.Fatalf("GetOpenPositions called with handle %+v, want %+v", adapter.gotPositionsHandle, handle)
	}

	var got []domain.NormalizedPosition
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if len(got) != 1 || got[0].BrokerPositionID != "pos-1" || got[0].VolumeLots != 1.5 {
		t.Fatalf("got positions %+v, want one position pos-1/1.5 lots", got)
	}
}

func TestGetOpenPositions_UnknownAccount_NotFound(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/unknown/positions", sharedSecret, "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
}

func TestGetOpenPositions_AdapterError_BadGateway(t *testing.T) {
	handle := domain.ConnectionHandle{AccountID: "acct-1"}
	adapter := &fakeBrokerAdapter{positionsErr: errors.New("ctrader: connection dropped")}
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{handles: map[string]domain.ConnectionHandle{"acct-1": handle}}, adapter, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/acct-1/positions", sharedSecret, "")
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}

func TestGetOpenPositions_MissingToken_Unauthorized(t *testing.T) {
	mux := internalapi.NewMux(&fakeAccountLister{}, &fakeHandleProvider{}, &fakeBrokerAdapter{}, sharedSecret, nil)

	rec := doPathRequest(t, mux, http.MethodGet, "/internal/ctrader/accounts/acct-1/positions", "", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}
