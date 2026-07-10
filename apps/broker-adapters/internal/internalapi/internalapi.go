// Package internalapi builds broker-adapters' internal-only HTTP surface —
// today just POST /internal/ctrader/accounts, called once by Core App during
// the OAuth callback (before any broker_accounts row, and thus any
// domain.ConnectionHandle, exists). Reachability is restricted to in-cluster
// callers by a K8s NetworkPolicy (deploy/base/broker-adapters); the shared
// X-Internal-Service-Token header check here is defense in depth on top of
// that, not the primary guard.
package internalapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/avison9/nectrix/broker-adapters/internal/ctrader"
)

// AccountLister is the one method this package needs from *ctrader.CTraderAdapter
// — kept as a narrow interface so tests can substitute a fake instead of
// standing up a real cTrader connection.
type AccountLister interface {
	ListAccountsByAccessToken(ctx context.Context, accessToken string) ([]ctrader.AccountSummary, error)
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

// NewMux builds the internal mux. sharedSecret must be non-empty in any real
// deployment (env/secrets wiring sets INTERNAL_SERVICE_TOKEN) — an empty
// sharedSecret rejects every request rather than silently accepting an
// unauthenticated one.
func NewMux(lister AccountLister, sharedSecret string, logger *slog.Logger) http.Handler {
	if logger == nil {
		logger = slog.Default()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /internal/ctrader/accounts", func(w http.ResponseWriter, r *http.Request) {
		handleListAccounts(w, r, lister, logger)
	})
	return requireSharedSecret(sharedSecret, mux)
}

func requireSharedSecret(sharedSecret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if sharedSecret == "" || r.Header.Get("X-Internal-Service-Token") != sharedSecret {
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
