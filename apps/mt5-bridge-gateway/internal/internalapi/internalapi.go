// Package internalapi builds mt5-bridge-gateway's first internal-only HTTP
// surface — TICKET-106's GET .../snapshot and POST .../orders routes, called
// by Copy Engine's remoteadapter.HTTPClient once a relationship needs a live
// account snapshot or to place a real follower order against a connected
// MT5/MT4 EA session. Mirrors apps/broker-adapters/internal/internalapi's
// established shape exactly (same X-Internal-Service-Token shared-secret
// check, same route/response conventions) for consistency across the two
// services. Reachability is restricted to in-cluster callers by a K8s
// NetworkPolicy (deploy/base/mt5-bridge-gateway); the header check here is
// defense in depth on top of that, not the primary guard.
package internalapi

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"
)

// PlatformAdapters is this service's two dedup-wrapped domain.BrokerAdapter
// instances (mirrors main.go's mt5Adapter/mt4Adapter pair) -- one process
// serves both platforms, so every route needs to know which one to use.
type PlatformAdapters struct {
	MT5 domain.BrokerAdapter
	MT4 domain.BrokerAdapter
}

func (p PlatformAdapters) forPlatform(platform string) (domain.BrokerAdapter, error) {
	switch platform {
	case "MT5":
		return p.MT5, nil
	case "MT4":
		return p.MT4, nil
	default:
		return nil, fmt.Errorf("internalapi: unrecognized platform %q (want MT5 or MT4)", platform)
	}
}

// placeOrderRequest's platform selects which of PlatformAdapters to call --
// unlike broker-adapters (one platform per deployment), this service always
// needs it.
type placeOrderRequest struct {
	Platform string                        `json:"platform"`
	Order    domain.NormalizedOrderRequest `json:"order"`
}

// modifyPositionRequest mirrors domain.SLTPChange, plus the platform field
// every route on this service needs (one process, two platforms).
type modifyPositionRequest struct {
	Platform string   `json:"platform"`
	SLPrice  *float64 `json:"slPrice"`
	TPPrice  *float64 `json:"tpPrice"`
}

// closePositionRequest: VolumeLots nil/omitted means "close the entire
// remaining position" -- domain.BrokerAdapter.ClosePosition's own contract.
type closePositionRequest struct {
	Platform   string   `json:"platform"`
	VolumeLots *float64 `json:"volumeLots,omitempty"`
}

// orderResultWire deliberately drops domain.NormalizedOrderResult's
// RawBrokerResponse -- never parsed by upstream logic, and an opaque
// broker-response struct is fragile to depend on across a network hop.
// TICKET-107 widened this from PlaceOrder-only to also cover
// ModifyPosition/ClosePosition -- all three return the same wire-safe shape.
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
// deployment (INTERNAL_SERVICE_TOKEN, the same value already used for this
// service's own outbound core-app calls) — an empty sharedSecret rejects
// every request rather than silently accepting an unauthenticated one.
func NewMux(adapters PlatformAdapters, sharedSecret string, logger *slog.Logger) http.Handler {
	if logger == nil {
		logger = slog.Default()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /internal/mt/accounts/{brokerAccountId}/snapshot", func(w http.ResponseWriter, r *http.Request) {
		handleGetAccountSnapshot(w, r, adapters, logger)
	})
	mux.HandleFunc("POST /internal/mt/accounts/{brokerAccountId}/orders", func(w http.ResponseWriter, r *http.Request) {
		handlePlaceOrder(w, r, adapters, logger)
	})
	mux.HandleFunc("POST /internal/mt/accounts/{brokerAccountId}/positions/{positionId}/modify", func(w http.ResponseWriter, r *http.Request) {
		handleModifyPosition(w, r, adapters, logger)
	})
	mux.HandleFunc("POST /internal/mt/accounts/{brokerAccountId}/positions/{positionId}/close", func(w http.ResponseWriter, r *http.Request) {
		handleClosePosition(w, r, adapters, logger)
	})
	mux.HandleFunc("GET /internal/mt/accounts/{brokerAccountId}/positions", func(w http.ResponseWriter, r *http.Request) {
		handleGetOpenPositions(w, r, adapters, logger)
	})
	mux.HandleFunc("GET /internal/mt/accounts/{brokerAccountId}/symbols/{symbol}/resolve", func(w http.ResponseWriter, r *http.Request) {
		handleResolveSymbol(w, r, adapters, logger)
	})
	return requireSharedSecret(sharedSecret, mux)
}

// requireSharedSecret mirrors apps/broker-adapters' identical helper
// (constant-time comparison, same header name) -- Go's internal/ package
// visibility rule means this can't be imported from there, so it's
// duplicated here rather than left as a plain != comparison a second time.
func requireSharedSecret(sharedSecret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		presented := r.Header.Get("X-Internal-Service-Token")
		if sharedSecret == "" || subtle.ConstantTimeCompare([]byte(sharedSecret), []byte(presented)) != 1 {
			http.Error(w, "missing or invalid internal service token", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func handleGetAccountSnapshot(w http.ResponseWriter, r *http.Request, adapters PlatformAdapters, logger *slog.Logger) {
	brokerAccountID := r.PathValue("brokerAccountId")
	platform := r.URL.Query().Get("platform")

	adapter, err := adapters.forPlatform(platform)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	handle := domain.ConnectionHandle{AccountID: brokerAccountID}
	snapshot, err := adapter.GetAccountSnapshot(r.Context(), handle)
	if err != nil {
		if errors.Is(err, eabridge.ErrNoSession) {
			http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
			return
		}
		logger.Error("internalapi: get account snapshot failed", "brokerAccountId", brokerAccountID, "platform", platform, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(snapshot)
}

func handlePlaceOrder(w http.ResponseWriter, r *http.Request, adapters PlatformAdapters, logger *slog.Logger) {
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

	adapter, err := adapters.forPlatform(req.Platform)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	handle := domain.ConnectionHandle{AccountID: brokerAccountID}
	result, err := adapter.PlaceOrder(r.Context(), handle, req.Order)
	if err != nil {
		if errors.Is(err, eabridge.ErrNoSession) {
			http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
			return
		}
		logger.Error("internalapi: place order failed", "brokerAccountId", brokerAccountID, "platform", req.Platform, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(toOrderResultWire(result))
}

// handleModifyPosition changes a follower position's SL/TP -- TICKET-107,
// docs/08-copy-trading-engine.md §8.7. Mirrors handlePlaceOrder's
// decode/resolve-platform-adapter/call-adapter/encode-wire-result shape,
// including its errors.Is(eabridge.ErrNoSession) 404-vs-502 distinction.
func handleModifyPosition(w http.ResponseWriter, r *http.Request, adapters PlatformAdapters, logger *slog.Logger) {
	defer func() { _ = r.Body.Close() }()

	brokerAccountID := r.PathValue("brokerAccountId")
	positionID := r.PathValue("positionId")

	var req modifyPositionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
		return
	}

	adapter, err := adapters.forPlatform(req.Platform)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	handle := domain.ConnectionHandle{AccountID: brokerAccountID}
	result, err := adapter.ModifyPosition(r.Context(), handle, positionID, domain.SLTPChange{SLPrice: req.SLPrice, TPPrice: req.TPPrice})
	if err != nil {
		if errors.Is(err, eabridge.ErrNoSession) {
			http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
			return
		}
		logger.Error("internalapi: modify position failed", "brokerAccountId", brokerAccountID, "positionId", positionID, "platform", req.Platform, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(toOrderResultWire(result))
}

// handleGetOpenPositions is TICKET-109's reconciliation ground truth --
// docs/08-copy-trading-engine.md §8.9. Mirrors handleGetAccountSnapshot's
// own platform-resolution/errors.Is(eabridge.ErrNoSession) shape exactly.
func handleGetOpenPositions(w http.ResponseWriter, r *http.Request, adapters PlatformAdapters, logger *slog.Logger) {
	brokerAccountID := r.PathValue("brokerAccountId")
	platform := r.URL.Query().Get("platform")

	adapter, err := adapters.forPlatform(platform)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	handle := domain.ConnectionHandle{AccountID: brokerAccountID}
	positions, err := adapter.GetOpenPositions(r.Context(), handle)
	if err != nil {
		if errors.Is(err, eabridge.ErrNoSession) {
			http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
			return
		}
		logger.Error("internalapi: get open positions failed", "brokerAccountId", brokerAccountID, "platform", platform, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(positions)
}

// handleResolveSymbol verifies a user-typed broker symbol name against the
// live broker and returns its full trading spec -- TICKET-116's manual
// symbol-mapping fallback (TICKET-103's auto-suggestion probe list doesn't
// cover every possible broker symbol naming convention). ResolveSymbol/
// GetSymbolSpecification are account-agnostic (see mtadapter/symbols.go's own
// doc comment on domain.BrokerAdapter's interface shape) -- brokerAccountId
// is carried in the path purely for logging/REST consistency with this
// service's other account-scoped routes, not used to build a
// domain.ConnectionHandle.
func handleResolveSymbol(w http.ResponseWriter, r *http.Request, adapters PlatformAdapters, logger *slog.Logger) {
	brokerAccountID := r.PathValue("brokerAccountId")
	brokerSymbolName := r.PathValue("symbol")
	platform := r.URL.Query().Get("platform")

	adapter, err := adapters.forPlatform(platform)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	normalized, err := adapter.ResolveSymbol(r.Context(), brokerSymbolName)
	if err != nil {
		logger.Info("internalapi: resolve symbol failed", "brokerAccountId", brokerAccountID, "symbol", brokerSymbolName, "platform", platform, "error", err)
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	spec, err := adapter.GetSymbolSpecification(r.Context(), normalized)
	if err != nil {
		logger.Error("internalapi: get symbol specification failed", "brokerAccountId", brokerAccountID, "symbol", brokerSymbolName, "platform", platform, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(spec)
}

// handleClosePosition closes all (volumeLots omitted) or part (volumeLots
// set) of a follower position -- TICKET-107,
// docs/09-money-management-risk-formulas.md §9.5.
func handleClosePosition(w http.ResponseWriter, r *http.Request, adapters PlatformAdapters, logger *slog.Logger) {
	defer func() { _ = r.Body.Close() }()

	brokerAccountID := r.PathValue("brokerAccountId")
	positionID := r.PathValue("positionId")

	var req closePositionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON body: "+err.Error(), http.StatusBadRequest)
		return
	}

	adapter, err := adapters.forPlatform(req.Platform)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	handle := domain.ConnectionHandle{AccountID: brokerAccountID}
	result, err := adapter.ClosePosition(r.Context(), handle, positionID, req.VolumeLots)
	if err != nil {
		if errors.Is(err, eabridge.ErrNoSession) {
			http.Error(w, "no connected handle for broker account "+brokerAccountID, http.StatusNotFound)
			return
		}
		logger.Error("internalapi: close position failed", "brokerAccountId", brokerAccountID, "positionId", positionID, "platform", req.Platform, "error", err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(toOrderResultWire(result))
}
