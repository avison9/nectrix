// Package coreappclient is the real HTTP client for Core App's internal-only
// endpoints (task #119, not yet built on the Java side) — the two Go's own
// internal/reconcile.Loop needs: a lightweight broker_accounts listing, and
// a per-account decrypted-credentials fetch. Written and fully tested
// against the documented contract (see internal/reconcile's own package doc)
// so it's ready the moment those Java endpoints exist.
package coreappclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/avison9/nectrix/broker-adapters/internal/reconcile"
)

// Client talks to Core App's internal HTTP surface, authenticating with the
// same shared X-Internal-Service-Token header internalapi's own handlers
// check — INTERNAL_SERVICE_TOKEN is symmetric across both services.
type Client struct {
	baseURL      string
	sharedSecret string
	httpClient   *http.Client
}

var (
	_ reconcile.BrokerAccountLister = (*Client)(nil)
	_ reconcile.CredentialFetcher   = (*Client)(nil)
	_ reconcile.StatusReporter      = (*Client)(nil)
)

func New(baseURL, sharedSecret string, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), sharedSecret: sharedSecret, httpClient: httpClient}
}

type listBrokerAccountsResponse struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

// ListBrokerAccounts calls GET /internal/broker-accounts?status=CONNECTED,PENDING&brokerType=CTRADER
// — the reconcile loop only ever cares about accounts in those two statuses
// (DEGRADED/DISCONNECTED/REAUTH_REQUIRED accounts stay disconnected until
// Core App's own token-refresh job — task #120 — moves them back).
func (c *Client) ListBrokerAccounts(ctx context.Context) ([]reconcile.BrokerAccountRef, error) {
	u := c.baseURL + "/internal/broker-accounts?" + url.Values{
		"status":     {"CONNECTED,PENDING"},
		"brokerType": {"CTRADER"},
	}.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("coreappclient: list broker accounts: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("coreappclient: list broker accounts: unexpected status %d", resp.StatusCode)
	}

	var body []listBrokerAccountsResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("coreappclient: decode list broker accounts response: %w", err)
	}

	refs := make([]reconcile.BrokerAccountRef, 0, len(body))
	for _, acc := range body {
		refs = append(refs, reconcile.BrokerAccountRef{ID: acc.ID, Status: acc.Status})
	}
	return refs, nil
}

type fetchCredentialsResponse struct {
	AccessToken         string `json:"accessToken"`
	RefreshToken        string `json:"refreshToken"`
	CtidTraderAccountID int64  `json:"ctidTraderAccountId"`
	IsLive              bool   `json:"isLive"`
}

// FetchCredentials calls GET /internal/broker-accounts/credentials/{id} —
// Core App decrypts via EnvelopeEncryptionService before responding; this
// client never sees credentials_ciphertext, only the already-decrypted
// tokens.
func (c *Client) FetchCredentials(ctx context.Context, brokerAccountID string) (reconcile.BrokerAccountCredentials, error) {
	u := c.baseURL + "/internal/broker-accounts/credentials/" + url.PathEscape(brokerAccountID)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return reconcile.BrokerAccountCredentials{}, fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return reconcile.BrokerAccountCredentials{}, fmt.Errorf("coreappclient: fetch credentials: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return reconcile.BrokerAccountCredentials{}, fmt.Errorf("coreappclient: fetch credentials for %s: unexpected status %d", brokerAccountID, resp.StatusCode)
	}

	var body fetchCredentialsResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return reconcile.BrokerAccountCredentials{}, fmt.Errorf("coreappclient: decode fetch credentials response: %w", err)
	}

	return reconcile.BrokerAccountCredentials{
		AccessToken:         body.AccessToken,
		RefreshToken:        body.RefreshToken,
		CtidTraderAccountID: body.CtidTraderAccountID,
		IsLive:              body.IsLive,
	}, nil
}

type reportConnectionStatusRequest struct {
	Status string `json:"status"`
	Detail string `json:"detail,omitempty"`
}

// ReportConnectionStatus calls POST /internal/broker-accounts/{id}/connection-status
// — Core App updates the row and publishes BrokerConnectionEvent to Kafka.
func (c *Client) ReportConnectionStatus(ctx context.Context, brokerAccountID, status, detail string) error {
	u := c.baseURL + "/internal/broker-accounts/" + url.PathEscape(brokerAccountID) + "/connection-status"

	body, err := json.Marshal(reportConnectionStatusRequest{Status: status, Detail: detail})
	if err != nil {
		return fmt.Errorf("coreappclient: marshal connection status request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("coreappclient: report connection status: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("coreappclient: report connection status for %s: unexpected status %d", brokerAccountID, resp.StatusCode)
	}
	return nil
}
