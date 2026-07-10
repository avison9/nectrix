package coreappclient_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/avison9/nectrix/broker-adapters/internal/coreappclient"
)

const sharedSecret = "test-internal-token"

func TestListBrokerAccounts_ParsesRealResponseAndSendsSharedSecretAndFilters(t *testing.T) {
	var gotPath, gotToken string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path + "?" + r.URL.RawQuery
		gotToken = r.Header.Get("X-Internal-Service-Token")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode([]map[string]string{
			{"id": "acc-1", "status": "CONNECTED"},
			{"id": "acc-2", "status": "PENDING"},
		})
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	refs, err := client.ListBrokerAccounts(context.Background())
	if err != nil {
		t.Fatalf("ListBrokerAccounts() error = %v", err)
	}

	if gotToken != sharedSecret {
		t.Fatalf("X-Internal-Service-Token = %q, want %q", gotToken, sharedSecret)
	}
	if gotPath != "/internal/broker-accounts?brokerType=CTRADER&status=CONNECTED%2CPENDING" {
		t.Fatalf("request path+query = %q, unexpected", gotPath)
	}
	if len(refs) != 2 || refs[0].ID != "acc-1" || refs[0].Status != "CONNECTED" || refs[1].ID != "acc-2" {
		t.Fatalf("refs = %+v, unexpected", refs)
	}
}

func TestListBrokerAccounts_NonOKStatusIsAnError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	if _, err := client.ListBrokerAccounts(context.Background()); err == nil {
		t.Fatal("expected an error for a 500 response, got nil")
	}
}

func TestFetchCredentials_ParsesRealResponseAndSendsCorrectPath(t *testing.T) {
	var gotPath, gotToken string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotToken = r.Header.Get("X-Internal-Service-Token")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"accessToken":         "at-1",
			"refreshToken":        "rt-1",
			"ctidTraderAccountId": 42,
			"isLive":              false,
		})
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	creds, err := client.FetchCredentials(context.Background(), "acc-1")
	if err != nil {
		t.Fatalf("FetchCredentials() error = %v", err)
	}

	if gotToken != sharedSecret {
		t.Fatalf("X-Internal-Service-Token = %q, want %q", gotToken, sharedSecret)
	}
	if gotPath != "/internal/broker-accounts/credentials/acc-1" {
		t.Fatalf("request path = %q, unexpected", gotPath)
	}
	if creds.AccessToken != "at-1" || creds.RefreshToken != "rt-1" || creds.CtidTraderAccountID != 42 || creds.IsLive {
		t.Fatalf("creds = %+v, unexpected", creds)
	}
}

func TestFetchCredentials_NonOKStatusIsAnError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	if _, err := client.FetchCredentials(context.Background(), "acc-missing"); err == nil {
		t.Fatal("expected an error for a 404 response, got nil")
	}
}

func TestFetchCredentials_PathEscapesAccountID(t *testing.T) {
	var gotEscapedPath, gotDecodedPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotEscapedPath = r.URL.EscapedPath()
		gotDecodedPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"accessToken": "at", "ctidTraderAccountId": 1})
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	if _, err := client.FetchCredentials(context.Background(), "acc/weird id"); err != nil {
		t.Fatalf("FetchCredentials() error = %v", err)
	}
	// The wire form must escape the "/" (so it isn't mistaken for a path
	// segment separator) even though the server decodes it back for us.
	if gotEscapedPath != "/internal/broker-accounts/credentials/acc%2Fweird%20id" {
		t.Fatalf("escaped request path = %q, want escaped account id", gotEscapedPath)
	}
	if gotDecodedPath != "/internal/broker-accounts/credentials/acc/weird id" {
		t.Fatalf("decoded request path = %q, unexpected", gotDecodedPath)
	}
}

func TestReportConnectionStatus_SendsCorrectPathAndBody(t *testing.T) {
	var gotPath, gotToken string
	var gotBody map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotToken = r.Header.Get("X-Internal-Service-Token")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	err := client.ReportConnectionStatus(context.Background(), "acc-1", "CONNECTED", "")
	if err != nil {
		t.Fatalf("ReportConnectionStatus() error = %v", err)
	}

	if gotToken != sharedSecret {
		t.Fatalf("X-Internal-Service-Token = %q, want %q", gotToken, sharedSecret)
	}
	if gotPath != "/internal/broker-accounts/acc-1/connection-status" {
		t.Fatalf("request path = %q, unexpected", gotPath)
	}
	if gotBody["status"] != "CONNECTED" {
		t.Fatalf("request body status = %v, want CONNECTED", gotBody["status"])
	}
}

func TestReportConnectionStatus_NonOKStatusIsAnError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
	}))
	defer server.Close()

	client := coreappclient.New(server.URL, sharedSecret, nil)
	if err := client.ReportConnectionStatus(context.Background(), "acc-1", "NOT_A_REAL_STATUS", ""); err == nil {
		t.Fatal("expected an error for a 400 response, got nil")
	}
}
