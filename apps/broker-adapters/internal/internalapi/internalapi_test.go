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

func TestListAccounts_Success(t *testing.T) {
	lister := &fakeAccountLister{accounts: []ctrader.AccountSummary{
		{CtidTraderAccountID: 42, IsLive: false, TraderLogin: 12345, BrokerTitleShort: "IC Markets"},
	}}
	mux := internalapi.NewMux(lister, sharedSecret, nil)

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
	mux := internalapi.NewMux(lister, sharedSecret, nil)

	for _, token := range []string{"", "wrong-token"} {
		rec := doRequest(t, mux, token, `{"accessToken":"tok-1"}`)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("token=%q: status = %d, want 401", token, rec.Code)
		}
	}
}

func TestListAccounts_EmptySharedSecretConfigRejectsEverything(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, "", nil)

	rec := doRequest(t, mux, "", `{"accessToken":"tok-1"}`)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401 when sharedSecret is unconfigured", rec.Code)
	}
}

func TestListAccounts_MissingAccessTokenRejected(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `{}`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestListAccounts_InvalidJSONRejected(t *testing.T) {
	lister := &fakeAccountLister{}
	mux := internalapi.NewMux(lister, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", rec.Code)
	}
}

func TestListAccounts_ListerErrorSurfacesAsBadGateway(t *testing.T) {
	lister := &fakeAccountLister{err: errors.New("ctrader: no accounts found")}
	mux := internalapi.NewMux(lister, sharedSecret, nil)

	rec := doRequest(t, mux, sharedSecret, `{"accessToken":"tok-1"}`)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", rec.Code)
	}
}
