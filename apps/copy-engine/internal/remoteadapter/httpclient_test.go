package remoteadapter_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	domain "github.com/avison9/nectrix/go-domain"
)

func TestHTTPClient_CTrader_GetAccountSnapshot_RealWireFormat(t *testing.T) {
	var gotPath, gotToken string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotToken = r.Header.Get("X-Internal-Service-Token")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(domain.AccountSnapshot{BrokerAccountID: "acct-1", Currency: "USD", Balance: 10000, Equity: 9950})
	}))
	defer server.Close()

	client := remoteadapter.NewCTraderHTTPClient(server.URL, "shared-secret", nil)
	snapshot, err := client.GetAccountSnapshot(context.Background(), "acct-1")
	if err != nil {
		t.Fatalf("GetAccountSnapshot returned error: %v", err)
	}
	if gotPath != "/internal/ctrader/accounts/acct-1/snapshot" {
		t.Fatalf("request path = %q, want /internal/ctrader/accounts/acct-1/snapshot", gotPath)
	}
	if gotToken != "shared-secret" {
		t.Fatalf("X-Internal-Service-Token = %q, want shared-secret", gotToken)
	}
	if snapshot.Balance != 10000 || snapshot.Equity != 9950 {
		t.Fatalf("got snapshot %+v, want balance=10000 equity=9950", snapshot)
	}
}

func TestHTTPClient_MT_GetAccountSnapshot_IncludesPlatformQueryParam(t *testing.T) {
	var gotPath, gotQuery string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(domain.AccountSnapshot{BrokerAccountID: "acct-2"})
	}))
	defer server.Close()

	client := remoteadapter.NewMTHTTPClient(server.URL, "shared-secret", "MT5", nil)
	if _, err := client.GetAccountSnapshot(context.Background(), "acct-2"); err != nil {
		t.Fatalf("GetAccountSnapshot returned error: %v", err)
	}
	if gotPath != "/internal/mt/accounts/acct-2/snapshot" {
		t.Fatalf("request path = %q, want /internal/mt/accounts/acct-2/snapshot", gotPath)
	}
	if gotQuery != "platform=MT5" {
		t.Fatalf("query = %q, want platform=MT5", gotQuery)
	}
}

func TestHTTPClient_GetAccountSnapshot_NonOKStatus_ReturnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "no connected handle for broker account acct-1", http.StatusNotFound)
	}))
	defer server.Close()

	client := remoteadapter.NewCTraderHTTPClient(server.URL, "shared-secret", nil)
	if _, err := client.GetAccountSnapshot(context.Background(), "acct-1"); err == nil {
		t.Fatal("expected an error for a non-200 response")
	}
}

func TestHTTPClient_PlaceOrder_RealWireFormat_DropsRawBrokerResponse(t *testing.T) {
	var gotPath string
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "brokerPositionId": "pos-1"})
	}))
	defer server.Close()

	client := remoteadapter.NewMTHTTPClient(server.URL, "shared-secret", "MT4", nil)
	result, err := client.PlaceOrder(context.Background(), "acct-1", domain.NormalizedOrderRequest{
		IdempotencyKey: "idem-1", VolumeLots: 2.5,
	})
	if err != nil {
		t.Fatalf("PlaceOrder returned error: %v", err)
	}
	if gotPath != "/internal/mt/accounts/acct-1/orders" {
		t.Fatalf("request path = %q, want /internal/mt/accounts/acct-1/orders", gotPath)
	}
	if gotBody["platform"] != "MT4" {
		t.Fatalf("request body platform = %v, want MT4", gotBody["platform"])
	}
	order, _ := gotBody["order"].(map[string]any)
	if order["volumeLots"].(float64) != 2.5 {
		t.Fatalf("request body order.volumeLots = %v, want 2.5", order["volumeLots"])
	}
	if !result.Success || result.BrokerPositionID != "pos-1" {
		t.Fatalf("got result %+v, want Success=true BrokerPositionID=pos-1", result)
	}
	if result.RawBrokerResponse != nil {
		t.Fatalf("RawBrokerResponse should never be populated from the wire response, got %v", result.RawBrokerResponse)
	}
}

func TestHTTPClient_PlaceOrder_NonOKStatus_ReturnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "internal error", http.StatusBadGateway)
	}))
	defer server.Close()

	client := remoteadapter.NewCTraderHTTPClient(server.URL, "shared-secret", nil)
	if _, err := client.PlaceOrder(context.Background(), "acct-1", domain.NormalizedOrderRequest{}); err == nil {
		t.Fatal("expected an error for a non-200 response")
	}
}
