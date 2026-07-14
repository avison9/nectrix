package remoteadapter

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	domain "github.com/avison9/nectrix/go-domain"
)

// orderResultWire mirrors the wire-safe result shape both
// apps/broker-adapters/internal/internalapi and
// apps/mt5-bridge-gateway/internal/internalapi return for PlaceOrder,
// ModifyPosition, and ClosePosition alike (TICKET-107 widened this from
// PlaceOrder-only) -- deliberately missing domain.NormalizedOrderResult's
// RawBrokerResponse (never parsed upstream, fragile to depend on across a
// network hop).
type orderResultWire struct {
	Success          bool     `json:"success"`
	BrokerPositionID string   `json:"brokerPositionId,omitempty"`
	FilledPrice      *float64 `json:"filledPrice,omitempty"`
	RejectReason     string   `json:"rejectReason,omitempty"`
}

type placeOrderWireRequest struct {
	Platform string                        `json:"platform"`
	Order    domain.NormalizedOrderRequest `json:"order"`
}

// modifyPositionWireRequest mirrors domain.SLTPChange, plus platform (only
// meaningful for mt5-bridge-gateway's two-platforms-one-process design).
type modifyPositionWireRequest struct {
	Platform string   `json:"platform"`
	SLPrice  *float64 `json:"slPrice"`
	TPPrice  *float64 `json:"tpPrice"`
}

// closePositionWireRequest: VolumeLots nil/omitted means "close the entire
// remaining position" -- domain.BrokerAdapter.ClosePosition's own contract.
type closePositionWireRequest struct {
	Platform   string   `json:"platform"`
	VolumeLots *float64 `json:"volumeLots,omitempty"`
}

// HTTPClient implements RemoteAdapter over broker-adapters' or
// mt5-bridge-gateway's internal routes. platform is "" for cTrader
// (broker-adapters serves exactly one platform per deployment) and
// "MT5"/"MT4" for mt5-bridge-gateway (one process, two platforms, identical
// wire shape) -- kept as one type with a platform field rather than two
// types, since the wire format is otherwise identical.
type HTTPClient struct {
	// pathPrefix is "/internal/ctrader" or "/internal/mt" -- baseURL is
	// just the service's scheme://host:port, kept env-var-simple.
	baseURL      string
	pathPrefix   string
	platform     string
	sharedSecret string
	httpClient   *http.Client
}

// NewCTraderHTTPClient builds a RemoteAdapter calling apps/broker-adapters'
// internal routes. httpClient defaults to http.DefaultClient when nil.
func NewCTraderHTTPClient(baseURL, sharedSecret string, httpClient *http.Client) *HTTPClient {
	return newHTTPClient(baseURL, "/internal/ctrader", "", sharedSecret, httpClient)
}

// NewMTHTTPClient builds a RemoteAdapter calling apps/mt5-bridge-gateway's
// internal routes for one platform ("MT5" or "MT4") -- one process serves
// both, so every request carries which one to route to.
func NewMTHTTPClient(baseURL, sharedSecret, platform string, httpClient *http.Client) *HTTPClient {
	return newHTTPClient(baseURL, "/internal/mt", platform, sharedSecret, httpClient)
}

func newHTTPClient(baseURL, pathPrefix, platform, sharedSecret string, httpClient *http.Client) *HTTPClient {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &HTTPClient{baseURL: baseURL, pathPrefix: pathPrefix, platform: platform, sharedSecret: sharedSecret, httpClient: httpClient}
}

func (c *HTTPClient) GetAccountSnapshot(ctx context.Context, brokerAccountID string) (domain.AccountSnapshot, error) {
	url := fmt.Sprintf("%s%s/accounts/%s/snapshot", c.baseURL, c.pathPrefix, brokerAccountID)
	if c.platform != "" {
		url += "?platform=" + c.platform
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("remoteadapter: build snapshot request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("remoteadapter: snapshot request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return domain.AccountSnapshot{}, fmt.Errorf("remoteadapter: snapshot request for %s: %w", brokerAccountID, wireStatusError(resp))
	}

	var snapshot domain.AccountSnapshot
	if err := json.NewDecoder(resp.Body).Decode(&snapshot); err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("remoteadapter: decode snapshot response: %w", err)
	}
	return snapshot, nil
}

func (c *HTTPClient) PlaceOrder(ctx context.Context, brokerAccountID string, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	url := fmt.Sprintf("%s%s/accounts/%s/orders", c.baseURL, c.pathPrefix, brokerAccountID)

	body, err := json.Marshal(placeOrderWireRequest{Platform: c.platform, Order: order})
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: marshal order request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: build order request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: order request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: order request for %s: %w", brokerAccountID, wireStatusError(resp))
	}

	var wire orderResultWire
	if err := json.NewDecoder(resp.Body).Decode(&wire); err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: decode order response: %w", err)
	}
	return domain.NormalizedOrderResult{
		Success:          wire.Success,
		BrokerPositionID: wire.BrokerPositionID,
		FilledPrice:      wire.FilledPrice,
		RejectReason:     wire.RejectReason,
	}, nil
}

// ModifyPosition changes a follower position's SL/TP -- TICKET-107,
// docs/08-copy-trading-engine.md §8.7.
func (c *HTTPClient) ModifyPosition(ctx context.Context, brokerAccountID, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	url := fmt.Sprintf("%s%s/accounts/%s/positions/%s/modify", c.baseURL, c.pathPrefix, brokerAccountID, positionID)

	body, err := json.Marshal(modifyPositionWireRequest{Platform: c.platform, SLPrice: changes.SLPrice, TPPrice: changes.TPPrice})
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: marshal modify request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: build modify request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: modify request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: modify request for %s/%s: %w", brokerAccountID, positionID, wireStatusError(resp))
	}

	var wire orderResultWire
	if err := json.NewDecoder(resp.Body).Decode(&wire); err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: decode modify response: %w", err)
	}
	return domain.NormalizedOrderResult{
		Success:          wire.Success,
		BrokerPositionID: wire.BrokerPositionID,
		FilledPrice:      wire.FilledPrice,
		RejectReason:     wire.RejectReason,
	}, nil
}

// ClosePosition closes all (volume nil) or part (volume non-nil) of a
// follower position -- TICKET-107, docs/09-money-management-risk-formulas.md
// §9.5.
func (c *HTTPClient) ClosePosition(ctx context.Context, brokerAccountID, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	url := fmt.Sprintf("%s%s/accounts/%s/positions/%s/close", c.baseURL, c.pathPrefix, brokerAccountID, positionID)

	body, err := json.Marshal(closePositionWireRequest{Platform: c.platform, VolumeLots: volume})
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: marshal close request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: build close request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: close request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: close request for %s/%s: %w", brokerAccountID, positionID, wireStatusError(resp))
	}

	var wire orderResultWire
	if err := json.NewDecoder(resp.Body).Decode(&wire); err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("remoteadapter: decode close response: %w", err)
	}
	return domain.NormalizedOrderResult{
		Success:          wire.Success,
		BrokerPositionID: wire.BrokerPositionID,
		FilledPrice:      wire.FilledPrice,
		RejectReason:     wire.RejectReason,
	}, nil
}

// GetOpenPositions is reconciliation's ground truth -- TICKET-109,
// docs/08-copy-trading-engine.md §8.9. domain.NormalizedPosition has no
// RawBrokerResponse-shaped field needing a wire-safe subset (already a clean
// DTO), so the response is decoded directly.
func (c *HTTPClient) GetOpenPositions(ctx context.Context, brokerAccountID string) ([]domain.NormalizedPosition, error) {
	url := fmt.Sprintf("%s%s/accounts/%s/positions", c.baseURL, c.pathPrefix, brokerAccountID)
	if c.platform != "" {
		url += "?platform=" + c.platform
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("remoteadapter: build positions request: %w", err)
	}
	req.Header.Set("X-Internal-Service-Token", c.sharedSecret)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("remoteadapter: positions request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("remoteadapter: positions request for %s: %w", brokerAccountID, wireStatusError(resp))
	}

	var positions []domain.NormalizedPosition
	if err := json.NewDecoder(resp.Body).Decode(&positions); err != nil {
		return nil, fmt.Errorf("remoteadapter: decode positions response: %w", err)
	}
	return positions, nil
}

func wireStatusError(resp *http.Response) error {
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
	return fmt.Errorf("unexpected status %d: %s", resp.StatusCode, string(body))
}
