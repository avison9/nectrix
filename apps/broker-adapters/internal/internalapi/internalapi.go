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
	"time"

	"github.com/avison9/nectrix/broker-adapters/internal/ctrader"
	"github.com/avison9/nectrix/broker-adapters/internal/reconcile"
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

// SelfStatusProvider is the *reconcile.Loop subset the Engine Control page's
// status endpoint needs. ConnectedCount/LastReconcileAt map directly to that
// page's status badge (see reconcile.Status's own doc comment).
type SelfStatusProvider interface {
	Status() reconcile.Status
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

// modifyPositionRequest mirrors domain.SLTPChange, plus the same
// ignored-here platform field placeOrderRequest already carries.
type modifyPositionRequest struct {
	Platform string   `json:"platform"`
	SLPrice  *float64 `json:"slPrice"`
	TPPrice  *float64 `json:"tpPrice"`
}

// closePositionRequest: VolumeLots nil/omitted means "close the entire
// remaining position" — domain.BrokerAdapter.ClosePosition's own contract.
type closePositionRequest struct {
	Platform   string   `json:"platform"`
	VolumeLots *float64 `json:"volumeLots,omitempty"`
}

// orderResultWire deliberately drops domain.NormalizedOrderResult's
// RawBrokerResponse — never parsed by upstream logic, and an opaque
// proto/broker-response struct is fragile to depend on across a network hop.
// TICKET-107 widened this from PlaceOrder-only to also cover
// ModifyPosition/ClosePosition — all three return the same wire-safe shape.
type orderResultWire struct {
	Success          bool     `json:"success"`
	BrokerPositionID string   `json:"brokerPositionId,omitempty"`
	FilledPrice      *float64 `json:"filledPrice,omitempty"`
	RejectReason     string   `json:"rejectReason,omitempty"`
}

func toOrderResultWire(result domain.NormalizedOrderResult) orderResultWire {
	return orderResultWire{
		Success:          result.Success,
		BrokerPositionID: result.BrokerPositionID,
		FilledPrice:      result.FilledPrice,
		RejectReason:     result.RejectReason,
	}
}

// NewMux builds the internal mux. sharedSecret must be non-empty in any real
// deployment (env/secrets wiring sets INTERNAL_SERVICE_TOKEN) — an empty
// sharedSecret rejects every request rather than silently accepting an
// unauthenticated one. adapter is the dedup-wrapped domain.BrokerAdapter (not
// the raw one) — TICKET-106: this gives the new PlaceOrder route a
// Redis-backed idempotency guard beneath Copy Engine's own Postgres-level
// one, for free.
func NewMux(lister AccountLister, handles HandleProvider, selfStatus SelfStatusProvider, adapter domain.BrokerAdapter, sharedSecret string, logger *slog.Logger) http.Handler {
	if logger == nil {
		logger = slog.Default()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /internal/ctrader/accounts", func(w http.ResponseWriter, r *http.Request) {
		handleListAccounts(w, r, lister, logger)
	})
	mux.HandleFunc("GET /internal/self/status", func(w http.ResponseWriter, r *http.Request) {
		handleSelfStatus(w, r, selfStatus)
	})
	mux.HandleFunc("GET /internal/ctrader/accounts/{brokerAccountId}/snapshot", func(w http.ResponseWriter, r *http.Request) {
		handleGetAccountSnapshot(w, r, handles, adapter, logger)
	})
	mux.HandleFunc("POST /internal/ctrader/accounts/{brokerAccountId}/orders", func(w http.ResponseWriter, r *http.Request) {
		handlePlaceOrder(w, r, handles, adapter, logger)
	})
	mux.HandleFunc("POST /internal/ctrader/accounts/{brokerAccountId}/positions/{positionId}/modify", func(w http.ResponseWriter, r *http.Request) {
		handleModifyPosition(w, r, handles, adapter, logger)
	})
	mux.HandleFunc("POST /internal/ctrader/accounts/{brokerAccountId}/positions/{positionId}/close", func(w http.ResponseWriter, r *http.Request) {
		handleClosePosition(w, r, handles, adapter, logger)
	})
	mux.HandleFunc("GET /internal/ctrader/accounts/{brokerAccountId}/positions", func(w http.ResponseWriter, r *http.Request) {
		handleGetOpenPositions(w, r, handles, adapter, logger)
	})
	mux.HandleFunc("GET /internal/ctrader/accounts/{brokerAccountId}/symbols/{symbol}/resolve", func(w http.ResponseWriter, r *http.Request) {
		handleResolveSymbol(w, r, adapter, logger)
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

// selfStatusResponse is the Engine Control page's own wire contract —
// `lastReconcileAt` is the zero time (encodes as `"0001-01-01T00:00:00Z"`)
// until this process's very first reconcile cycle completes, which the admin
// page's own status derivation treats identically to "never reconciled" —
// still correctly renders as Stale/Disconnected, not a crash on a null.
type selfStatusResponse struct {
	ConnectedCount  int       `json:"connectedCount"`
	LastReconcileAt time.Time `json:"lastReconcileAt"`
}

func handleSelfStatus(w http.ResponseWriter, r *http.Request, selfStatus SelfStatusProvider) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(selfStatusResponse(selfStatus.Status()))
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
	_ = json.NewEncoder(w).Encode(toOrderResultWire(result))
}

// handleModifyPosition changes a follower position's SL/TP — TICKET-107,
// docs/08-copy-trading-engine.md §8.7. Mirrors handlePlaceOrder's
// auth(-already-applied-by-mux)/decode/resolve-handle/call-adapter/
// encode-wire-result shape exactly.
func handleModifyPosition(w http.ResponseWriter, r *http.Request, handles HandleProvider, adapter domain.BrokerAdapter, logger *slog.Logger) {
	defer func() { _ = r.Body.Close() }()

	brokerAccountID := r.PathValue("brokerAccountId")
	positionID := r.PathValue("positionId")

	var req modifyPositionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
		return
	}

	handle, ok := handles.HandleFor(brokerAccountID)
	if !ok {
		http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
		return
	}

	result, err := adapter.ModifyPosition(r.Context(), handle, positionID, domain.SLTPChange{SLPrice: req.SLPrice, TPPrice: req.TPPrice})
	if err != nil {
		logger.Error("internalapi: modify position failed", "brokerAccountId", brokerAccountID, "positionId", positionID, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(toOrderResultWire(result))
}

// handleGetOpenPositions is TICKET-109's reconciliation ground truth --
// docs/08-copy-trading-engine.md §8.9. Mirrors handleGetAccountSnapshot
// exactly; domain.NormalizedPosition has no RawBrokerResponse-shaped field
// needing a wire-safe subset, so the response is encoded directly.
func handleGetOpenPositions(w http.ResponseWriter, r *http.Request, handles HandleProvider, adapter domain.BrokerAdapter, logger *slog.Logger) {
	brokerAccountID := r.PathValue("brokerAccountId")
	handle, ok := handles.HandleFor(brokerAccountID)
	if !ok {
		http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
		return
	}

	positions, err := adapter.GetOpenPositions(r.Context(), handle)
	if err != nil {
		logger.Error("internalapi: get open positions failed", "brokerAccountId", brokerAccountID, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(positions)
}

// handleResolveSymbol verifies a user-typed broker symbol name against the
// live broker and returns its full trading spec — TICKET-116's manual
// symbol-mapping fallback (TICKET-103's auto-suggestion probe list doesn't
// cover every possible broker symbol naming convention). ResolveSymbol/
// GetSymbolSpecification are account-agnostic (see ctrader/symbols.go's own
// doc comment on domain.BrokerAdapter's interface shape) — brokerAccountId is
// carried in the path purely for logging/REST consistency with this
// service's other account-scoped routes, not used to look up a
// domain.ConnectionHandle.
func handleResolveSymbol(w http.ResponseWriter, r *http.Request, adapter domain.BrokerAdapter, logger *slog.Logger) {
	brokerAccountID := r.PathValue("brokerAccountId")
	brokerSymbolName := r.PathValue("symbol")

	normalized, err := adapter.ResolveSymbol(r.Context(), brokerSymbolName)
	if err != nil {
		logger.Info("internalapi: resolve symbol failed", "brokerAccountId", brokerAccountID, "symbol", brokerSymbolName, "error", err)
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	spec, err := adapter.GetSymbolSpecification(r.Context(), normalized)
	if err != nil {
		logger.Error("internalapi: get symbol specification failed", "brokerAccountId", brokerAccountID, "symbol", brokerSymbolName, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(spec)
}

// handleClosePosition closes all (volumeLots omitted) or part (volumeLots
// set) of a follower position — TICKET-107,
// docs/09-money-management-risk-formulas.md §9.5.
func handleClosePosition(w http.ResponseWriter, r *http.Request, handles HandleProvider, adapter domain.BrokerAdapter, logger *slog.Logger) {
	defer func() { _ = r.Body.Close() }()

	brokerAccountID := r.PathValue("brokerAccountId")
	positionID := r.PathValue("positionId")

	var req closePositionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
		return
	}

	handle, ok := handles.HandleFor(brokerAccountID)
	if !ok {
		http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
		return
	}

	result, err := adapter.ClosePosition(r.Context(), handle, positionID, req.VolumeLots)
	if err != nil {
		logger.Error("internalapi: close position failed", "brokerAccountId", brokerAccountID, "positionId", positionID, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(toOrderResultWire(result))
}
