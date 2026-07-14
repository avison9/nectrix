// Package internalapi builds broker-adapters' internal-only HTTP surface —
// POST /internal/ctrader/accounts (called once by Core App during the OAuth
// callback, before any broker_accounts row/domain.ConnectionHandle exists),
// plus TICKET-106's new GET .../snapshot and POST .../orders routes (called
// by Copy Engine's remoteadapter.HTTPClient once a relationship needs a live
// account snapshot or to place a real follower order). Reachability is
// restricted to in-cluster callers by a K8s NetworkPolicy
// (deploy/base/broker-adapters); the shared X-Internal-Service-Token header
// check here is defense in depth on top of that, not the primary guard.
package internalapi

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/avison9/nectrix/broker-adapters/internal/ctrader"
	domain "github.com/avison9/nectrix/go-domain"
)

// AccountLister is the one method this package needs from *ctrader.CTraderAdapter
// — kept as a narrow interface so tests can substitute a fake instead of
// standing up a real cTrader connection.
type AccountLister interface {
	ListAccountsByAccessToken(ctx context.Context, accessToken string) ([]ctrader.AccountSummary, error)
}

// HandleProvider is the *reconcile.Loop subset the new snapshot/orders
// routes need — kept narrow so tests can substitute a fake instead of a
// real Loop, matching AccountLister's own precedent.
type HandleProvider interface {
	HandleFor(brokerAccountID string) (domain.ConnectionHandle, bool)
}

type listAccountsRequest struct {
	AccessToken string `json:"accessToken"`
}

type accountResponse struct {
	CtidTraderAccountID int64  `json:"ctidTraderAccountId"`
	IsLive              bool   `json:"isLive"`
	TraderLogin         int64  `json:"traderLogin"`
	BrokerTitleShort    string `json:"brokerTitleShort"`
}

// placeOrderRequest's platform field is ignored here — present only because
// mt5-bridge-gateway's identical wire shape needs it (one process, two
// platforms); broker-adapters serves exactly one platform per deployment.
type placeOrderRequest struct {
	Platform string                        `json:"platform"`
	Order    domain.NormalizedOrderRequest `json:"order"`
}

// placeOrderWireResult deliberately drops domain.NormalizedOrderResult's
// RawBrokerResponse — never parsed by upstream logic, and an opaque
// proto/broker-response struct is fragile to depend on across a network hop.
type placeOrderWireResult struct {
	Success          bool     `json:"success"`
	BrokerPositionID string   `json:"brokerPositionId,omitempty"`
	FilledPrice      *float64 `json:"filledPrice,omitempty"`
	RejectReason     string   `json:"rejectReason,omitempty"`
}

// NewMux builds the internal mux. sharedSecret must be non-empty in any real
// deployment (env/secrets wiring sets INTERNAL_SERVICE_TOKEN) — an empty
// sharedSecret rejects every request rather than silently accepting an
// unauthenticated one. adapter is the dedup-wrapped domain.BrokerAdapter (not
// the raw one) — TICKET-106: this gives the new PlaceOrder route a
// Redis-backed idempotency guard beneath Copy Engine's own Postgres-level
// one, for free.
func NewMux(lister AccountLister, handles HandleProvider, adapter domain.BrokerAdapter, sharedSecret string, logger *slog.Logger) http.Handler {
	if logger == nil {
		logger = slog.Default()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /internal/ctrader/accounts", func(w http.ResponseWriter, r *http.Request) {
		handleListAccounts(w, r, lister, logger)
	})
	mux.HandleFunc("GET /internal/ctrader/accounts/{brokerAccountId}/snapshot", func(w http.ResponseWriter, r *http.Request) {
		handleGetAccountSnapshot(w, r, handles, adapter, logger)
	})
	mux.HandleFunc("POST /internal/ctrader/accounts/{brokerAccountId}/orders", func(w http.ResponseWriter, r *http.Request) {
		handlePlaceOrder(w, r, handles, adapter, logger)
	})
	return requireSharedSecret(sharedSecret, mux)
}

func requireSharedSecret(sharedSecret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		presented := r.Header.Get("X-Internal-Service-Token")
		// TICKET-106: switched from a plain != comparison to
		// subtle.ConstantTimeCompare, mirroring core-app's own
		// InternalServiceTokenFilter (Java, MessageDigest.isEqual) — avoids a
		// timing side channel now that mt5-bridge-gateway is about to
		// duplicate this exact pattern into a second service.
		if sharedSecret == "" || subtle.ConstantTimeCompare([]byte(sharedSecret), []byte(presented)) != 1 {
			http.Error(w, "missing or invalid internal service token", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func handleListAccounts(w http.ResponseWriter, r *http.Request, lister AccountLister, logger *slog.Logger) {
	defer func() { _ = r.Body.Close() }()

	var req listAccountsRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
		return
	}
	if req.AccessToken == "" {
		http.Error(w, "accessToken is required", http.StatusBadRequest)
		return
	}

	accounts, err := lister.ListAccountsByAccessToken(r.Context(), req.AccessToken)
	if err != nil {
		logger.Error("internalapi: list accounts by access token failed", "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	resp := make([]accountResponse, 0, len(accounts))
	for _, acc := range accounts {
		resp = append(resp, accountResponse{
			CtidTraderAccountID: acc.CtidTraderAccountID,
			IsLive:              acc.IsLive,
			TraderLogin:         acc.TraderLogin,
			BrokerTitleShort:    acc.BrokerTitleShort,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func handleGetAccountSnapshot(w http.ResponseWriter, r *http.Request, handles HandleProvider, adapter domain.BrokerAdapter, logger *slog.Logger) {
	brokerAccountID := r.PathValue("brokerAccountId")
	handle, ok := handles.HandleFor(brokerAccountID)
	if !ok {
		http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
		return
	}

	snapshot, err := adapter.GetAccountSnapshot(r.Context(), handle)
	if err != nil {
		logger.Error("internalapi: get account snapshot failed", "brokerAccountId", brokerAccountID, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(snapshot)
}

func handlePlaceOrder(w http.ResponseWriter, r *http.Request, handles HandleProvider, adapter domain.BrokerAdapter, logger *slog.Logger) {
	defer func() { _ = r.Body.Close() }()

	brokerAccountID := r.PathValue("brokerAccountId")

	var req placeOrderRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
		return
	}
	if req.Order.FollowerBrokerAccountID != "" && req.Order.FollowerBrokerAccountID != brokerAccountID {
		http.Error(w, "order.followerBrokerAccountId does not match path", http.StatusBadRequest)
		return
	}

	handle, ok := handles.HandleFor(brokerAccountID)
	if !ok {
		http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
		return
	}

	result, err := adapter.PlaceOrder(r.Context(), handle, req.Order)
	if err != nil {
		logger.Error("internalapi: place order failed", "brokerAccountId", brokerAccountID, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(placeOrderWireResult{
		Success:          result.Success,
		BrokerPositionID: result.BrokerPositionID,
		FilledPrice:      result.FilledPrice,
		RejectReason:     result.RejectReason,
	})
}
