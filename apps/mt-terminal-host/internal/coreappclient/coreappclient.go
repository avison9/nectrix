// Package coreappclient is this service's HTTP client for Core App's internal-only endpoints.
// Unlike every other internal caller in this monorepo (apps/broker-adapters,
// apps/mt5-bridge-gateway), this client authenticates with TWO separate secrets: the shared
// X-Internal-Service-Token (for the general-purpose broker-accounts listing, reused unmodified from
// TICKET-101/102) and a second, narrowly-scoped token (for the new mt-terminal-credentials
// endpoint, the one place a real plaintext broker password is ever returned over the wire) — see
// that endpoint's own Java-side Javadoc for why these are deliberately kept separate.
package coreappclient

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/avison9/nectrix/mt-terminal-host/internal/reconcile"
)

// Client talks to Core App's internal HTTP surface.
type Client struct {
	baseURL                  string
	sharedServiceToken       string
	terminalProvisionerToken string
	httpClient               *http.Client
}

var (
	_ reconcile.Lister            = (*Client)(nil)
	_ reconcile.CredentialFetcher = (*Client)(nil)
)

// New builds a Client. sharedServiceToken authenticates the broker-accounts listing (the same
// value apps/broker-adapters/apps/mt5-bridge-gateway use); terminalProvisionerToken authenticates
// ONLY the mt-terminal-credentials fetch — a deliberately separate secret, held by no other
// service in this system.
func New(baseURL, sharedServiceToken, terminalProvisionerToken string, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &Client{
		baseURL:                  strings.TrimRight(baseURL, "/"),
		sharedServiceToken:       sharedServiceToken,
		terminalProvisionerToken: terminalProvisionerToken,
		httpClient:               httpClient,
	}
}

type listBrokerAccountsResponse struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

// ListMtBrokerAccounts calls GET /internal/broker-accounts?status=&brokerType= once per MT5/MT4 —
// the exact same existing, unmodified endpoint apps/mt5-bridge-gateway's internal/pairing already
// polls (it only ever filters by a single brokerType), authenticated with the shared token.
func (c *Client) ListMtBrokerAccounts(ctx context.Context) ([]reconcile.AccountRef, error) {
	var all []reconcile.AccountRef
	for _, brokerType := range []string{"MT5", "MT4"} {
		refs, err := c.listByBrokerType(ctx, brokerType)
		if err != nil {
			return nil, err
		}
		all = append(all, refs...)
	}
	return all, nil
}

func (c *Client) listByBrokerType(ctx context.Context, brokerType string) ([]reconcile.AccountRef, error) {
	u := c.baseURL + "/internal/broker-accounts?" + url.Values{
		"status":     {"PENDING,CONNECTED"},
		"brokerType": {brokerType},
	}.Encode()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedServiceToken)

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

	refs := make([]reconcile.AccountRef, 0, len(body))
	for _, acc := range body {
		refs = append(refs, reconcile.AccountRef{ID: acc.ID, Status: acc.Status, BrokerType: brokerType})
	}
	return refs, nil
}

type fetchTerminalCredentialsResponse struct {
	Login        string `json:"login"`
	Password     string `json:"password"`
	Server       string `json:"server"`
	PairingToken string `json:"pairingToken"`
}

// FetchTerminalCredentials calls GET /internal/broker-accounts/mt-terminal-credentials/{id},
// authenticated with the separate terminalProvisionerToken — the ONE call in this entire system
// that returns a real plaintext broker password.
func (c *Client) FetchTerminalCredentials(ctx context.Context, brokerAccountID string) (reconcile.TerminalCredentials, error) {
	u := c.baseURL + "/internal/broker-accounts/mt-terminal-credentials/" + url.PathEscape(brokerAccountID)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return reconcile.TerminalCredentials{}, fmt.Errorf("coreappclient: build request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.terminalProvisionerToken)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return reconcile.TerminalCredentials{}, fmt.Errorf("coreappclient: fetch terminal credentials: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return reconcile.TerminalCredentials{}, fmt.Errorf("coreappclient: fetch terminal credentials for %s: unexpected status %d", brokerAccountID, resp.StatusCode)
	}

	var body fetchTerminalCredentialsResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return reconcile.TerminalCredentials{}, fmt.Errorf("coreappclient: decode terminal credentials response: %w", err)
	}

	return reconcile.TerminalCredentials{
		Login:        body.Login,
		Password:     body.Password,
		Server:       body.Server,
		PairingToken: body.PairingToken,
	}, nil
}
