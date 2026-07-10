// Package mtadapter implements domain.BrokerAdapter for both MT5 and MT4,
// backed by a live internal/eabridge.Server session. TICKET-102's plan
// calls for MT5 and MT4 to "share ~90% of the underlying Go code... differing
// only in a platform tag and BrokerType()" — rather than duplicating that
// 90% across two packages, both platforms are the same Adapter type here,
// constructed via NewMT5/NewMT4, differing only in the brokerType field
// threaded through every call.
//
// Unlike internal/ctrader (TICKET-101), which dials OUT and so owns
// Connect's real network handshake, this adapter never dials anywhere:
// Connect just checks whether internal/eabridge.Server already has a live,
// paired EA session for the given broker_accounts.id (established
// independently, the moment a real EA on the user's own terminal connects
// in — see internal/pairing for how that session gets paired in the first
// place). A "not yet paired" Connect failure is expected and routine for a
// PENDING account, not exceptional — whichever caller retries Connect on an
// interval (mirroring reconcile.Loop's own retry-every-cycle behavior)
// naturally converges once the user attaches their EA.
package mtadapter

import (
	"context"
	"fmt"

	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"

	domain "github.com/avison9/nectrix/go-domain"
)

// Adapter is a domain.BrokerAdapter for MT5 or MT4, selected at
// construction time via NewMT5/NewMT4.
type Adapter struct {
	server     *eabridge.Server
	brokerType domain.BrokerType
	symbols    *symbolCache
}

var _ domain.BrokerAdapter = (*Adapter)(nil)

// NewMT5 builds the MT5 adapter over server.
func NewMT5(server *eabridge.Server) *Adapter { return newAdapter(server, domain.BrokerTypeMT5) }

// NewMT4 builds the MT4 adapter over the SAME server instance — one
// process, one WebSocket listener, serving both platforms (see
// apps/mt5-bridge-gateway/README.md).
func NewMT4(server *eabridge.Server) *Adapter { return newAdapter(server, domain.BrokerTypeMT4) }

func newAdapter(server *eabridge.Server, brokerType domain.BrokerType) *Adapter {
	return &Adapter{server: server, brokerType: brokerType, symbols: newSymbolCache()}
}

func (a *Adapter) BrokerType() domain.BrokerType { return a.brokerType }

// Connect succeeds only once a real EA session is already paired for this
// account — see package doc. It never dials out.
func (a *Adapter) Connect(ctx context.Context, credentials domain.BrokerCredentials) (domain.ConnectionHandle, error) {
	if _, ok := a.server.Session(credentials.AccountID); !ok {
		return domain.ConnectionHandle{}, fmt.Errorf("mtadapter(%s): no EA session paired for broker account %s yet", a.brokerType, credentials.AccountID)
	}
	return domain.ConnectionHandle{ID: credentials.AccountID, BrokerType: a.brokerType, AccountID: credentials.AccountID}, nil
}

// Disconnect clears this adapter's interest in the account — it does not
// close the real EA socket, which the EA's own terminal lifecycle owns
// (detaching the EA, closing the terminal, or losing network are the real
// disconnect triggers, observed via eabridge.Server's session-lost path).
func (a *Adapter) Disconnect(ctx context.Context, handle domain.ConnectionHandle) error {
	return nil
}

func (a *Adapter) HealthCheck(ctx context.Context, handle domain.ConnectionHandle) (domain.ConnectionHealth, error) {
	sess, ok := a.server.Session(handle.AccountID)
	if !ok {
		return domain.ConnectionHealth{Connected: false, Detail: "no live EA session"}, nil
	}
	if err := sess.Ping(ctx); err != nil {
		return domain.ConnectionHealth{Connected: false, Detail: err.Error()}, nil
	}
	return domain.ConnectionHealth{Connected: true}, nil
}

func (a *Adapter) GetAccountSnapshot(ctx context.Context, handle domain.ConnectionHandle) (domain.AccountSnapshot, error) {
	sess, err := a.sessionFor(handle)
	if err != nil {
		return domain.AccountSnapshot{}, err
	}
	return sess.RequestSnapshot(ctx)
}

func (a *Adapter) GetOpenPositions(ctx context.Context, handle domain.ConnectionHandle) ([]domain.NormalizedPosition, error) {
	sess, err := a.sessionFor(handle)
	if err != nil {
		return nil, err
	}
	return sess.RequestPositions(ctx)
}

// StreamTradeEvents adds an additional subscriber to the live session's
// trade-event feed, on top of whichever one internal/eabridge.Server itself
// already wired at session-establishment time (in production, the
// Kafka-publish callback — see main.go). Provided for domain.BrokerAdapter
// conformance and direct testability; production wiring doesn't need to
// call this explicitly (mirrors this ticket's own plan: "the pairing loop
// doesn't need to call Connect/StreamTradeEvents like cTrader's reconcile
// does").
func (a *Adapter) StreamTradeEvents(ctx context.Context, handle domain.ConnectionHandle, onEvent func(context.Context, domain.NormalizedTradeEvent) error) (domain.Subscription, error) {
	sess, err := a.sessionFor(handle)
	if err != nil {
		return nil, err
	}
	return sess.Subscribe(onEvent), nil
}

func (a *Adapter) PlaceOrder(ctx context.Context, handle domain.ConnectionHandle, order domain.NormalizedOrderRequest) (domain.NormalizedOrderResult, error) {
	sess, err := a.sessionFor(handle)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	return sess.SendOrderCommand(ctx, eabridge.OrderCommand{
		Action:          eabridge.OrderActionPlace,
		Symbol:          order.Symbol,
		Direction:       order.Direction,
		VolumeLots:      order.VolumeLots,
		SLPrice:         order.SLPrice,
		TPPrice:         order.TPPrice,
		MaxSlippagePips: order.MaxSlippagePips,
		ClientOrderTag:  order.ClientOrderTag,
	})
}

func (a *Adapter) ModifyPosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, changes domain.SLTPChange) (domain.NormalizedOrderResult, error) {
	sess, err := a.sessionFor(handle)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	return sess.SendOrderCommand(ctx, eabridge.OrderCommand{
		Action:     eabridge.OrderActionModify,
		PositionID: positionID,
		SLPrice:    changes.SLPrice,
		TPPrice:    changes.TPPrice,
	})
}

func (a *Adapter) ClosePosition(ctx context.Context, handle domain.ConnectionHandle, positionID string, volume *float64) (domain.NormalizedOrderResult, error) {
	sess, err := a.sessionFor(handle)
	if err != nil {
		return domain.NormalizedOrderResult{}, err
	}
	return sess.SendOrderCommand(ctx, eabridge.OrderCommand{
		Action:          eabridge.OrderActionClose,
		PositionID:      positionID,
		CloseVolumeLots: volume,
	})
}

func (a *Adapter) sessionFor(handle domain.ConnectionHandle) (*eabridge.Session, error) {
	sess, ok := a.server.Session(handle.AccountID)
	if !ok {
		return nil, fmt.Errorf("mtadapter(%s): no live EA session for broker account %s", a.brokerType, handle.AccountID)
	}
	return sess, nil
}
