// Package coreappclient is this service's HTTP client for Core App's
// internal-only endpoints — the MT5/MT4 counterpart of
// apps/broker-adapters' own identically-named package. Kept as a separate
// implementation (not a shared package) because these are two different Go
// modules/binaries in this monorepo — the same pattern TICKET-101 already
// established, not a new one.
package coreappclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/pairing"
)

// Client talks to Core App's internal HTTP surface, authenticating with the
// shared X-Internal-Service-Token header — the same symmetric
// INTERNAL_SERVICE_TOKEN TICKET-101 wired up, reused unmodified here.
type Client struct {
	baseURL      string
	sharedSecret string
	httpClient   *http.Client
}

var (
	_ pairing.Lister            = (*Client)(nil)
	_ pairing.CredentialFetcher = (*Client)(nil)
	_ pairing.StatusReporter    = (*Client)(nil)
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

// ListMtBrokerAccounts calls GET /internal/broker-accounts?status=&brokerType=
// once per MT5/MT4 (task #119's endpoint only ever filters by a single
// brokerType — see BrokerAccountInternalController, unmodified from
// TICKET-101) and merges the results, tagging each with the brokerType it
// was fetched under (the endpoint's own response doesn't carry it).
func (c *Client) ListMtBrokerAccounts(ctx context.Context) ([]pairing.AccountRef, error) {
	var all []pairing.AccountRef
	for _, brokerType := range []string{"MT5", "MT4"} {
		refs, err := c.listByBrokerType(ctx, brokerType)
		if err != nil {
			return nil, err
		}
		all = append(all, refs...)
	}
	return all, nil
}

func (c *Client) listByBrokerType(ctx context.Context, brokerType string) ([]pairing.AccountRef, error) {
	u := c.baseURL + "/internal/broker-accounts?" + url.Values{
		"status":     {"PENDING,CONNECTED"},
		"brokerType": {brokerType},
	}.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("coreappclient: list %s broker accounts: %w", brokerType, err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("coreappclient: list %s broker accounts: unexpected status %d", brokerType, resp.StatusCode)
	}

	var body []listBrokerAccountsResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, fmt.Errorf("coreappclient: decode list broker accounts response: %w", err)
	}

	refs := make([]pairing.AccountRef, 0, len(body))
	for _, acc := range body {
		refs = append(refs, pairing.AccountRef{ID: acc.ID, Status: acc.Status, BrokerType: brokerType})
	}
	return refs, nil
}

type fetchMtCredentialsResponse struct {
	Login        string `json:"login"`
	Server       string `json:"server"`
	PairingToken string `json:"pairingToken"`
}

// FetchMtCredentials calls GET /internal/broker-accounts/mt-credentials/{id}
// — the new, additive endpoint (task #132); Core App decrypts via
// EnvelopeEncryptionService before responding, same as cTrader's own
// credentials endpoint, so this client never sees credentials_ciphertext.
func (c *Client) FetchMtCredentials(ctx context.Context, brokerAccountID string) (pairing.MtCredentials, error) {
	u := c.baseURL + "/internal/broker-accounts/mt-credentials/" + url.PathEscape(brokerAccountID)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return pairing.MtCredentials{}, fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return pairing.MtCredentials{}, fmt.Errorf("coreappclient: fetch mt credentials: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return pairing.MtCredentials{}, fmt.Errorf("coreappclient: fetch mt credentials for %s: unexpected status %d", brokerAccountID, resp.StatusCode)
	}

	var body fetchMtCredentialsResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return pairing.MtCredentials{}, fmt.Errorf("coreappclient: decode mt credentials response: %w", err)
	}

	return pairing.MtCredentials{Login: body.Login, Server: body.Server, PairingToken: body.PairingToken}, nil
}

type reportConnectionStatusRequest struct {
	Status string `json:"status"`
	Detail string `json:"detail,omitempty"`
}

// ReportConnectionStatus calls POST /internal/broker-accounts/{id}/connection-status
// — the exact endpoint TICKET-101 built (and fixed a real missing-caller bug
// in), reused unmodified.
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
