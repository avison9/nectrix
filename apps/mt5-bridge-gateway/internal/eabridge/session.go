package eabridge

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/gorilla/websocket"
)

// wsConn is the subset of *websocket.Conn Session needs — narrowed so tests
// can substitute a fake instead of a real socket where useful.
type wsConn interface {
	ReadMessage() (messageType int, p []byte, err error)
	WriteMessage(messageType int, data []byte) error
	Close() error
}

// Session is one live EA <-> Gateway WebSocket connection, already past the
// hello handshake and bound to a specific broker_accounts.id. Every
// domain.BrokerAdapter read/write call that needs a live round trip
// (snapshot, positions, symbol spec, order commands) goes through call(),
// which multiplexes concurrent requests over the single connection via a
// requestId correlation map — WebSocket only allows one writer at a time,
// but many callers may be in flight together (e.g. Copy Engine placing an
// order while the reconcile/pairing loop is health-checking).
type Session struct {
	brokerAccountID string
	platform        domain.BrokerType
	login           string
	server          string

	conn    wsConn
	writeMu sync.Mutex

	mu      sync.Mutex
	pending map[string]chan json.RawMessage
	closed  bool

	subMu       sync.Mutex
	subscribers map[int]func(context.Context, domain.NormalizedTradeEvent) error
	nextSubID   int

	logger *slog.Logger
}

var requestSeq int64

func newRequestID() string {
	return fmt.Sprintf("req-%d", atomic.AddInt64(&requestSeq, 1))
}

func newSession(brokerAccountID string, platform domain.BrokerType, login, server string, conn wsConn, logger *slog.Logger) *Session {
	if logger == nil {
		logger = slog.Default()
	}
	return &Session{
		brokerAccountID: brokerAccountID,
		platform:        platform,
		login:           login,
		server:          server,
		conn:            conn,
		pending:         make(map[string]chan json.RawMessage),
		subscribers:     make(map[int]func(context.Context, domain.NormalizedTradeEvent) error),
		logger:          logger,
	}
}

// BrokerAccountID, Platform expose the identity this session was paired
// under — used by Server's registry and by adapters filtering "any session
// of my platform" for account-agnostic symbol metadata calls.
func (s *Session) BrokerAccountID() string     { return s.brokerAccountID }
func (s *Session) Platform() domain.BrokerType { return s.platform }

// Subscribe registers an additional trade-event callback, alongside
// whichever one the Server itself wired at session-creation time to publish
// to Kafka. Mirrors domain.BrokerAdapter.StreamTradeEvents' subscription
// contract — Close() stops delivery to this specific callback without
// touching the underlying WebSocket connection (owned by the EA's own
// lifecycle, not by any one subscriber).
func (s *Session) Subscribe(onEvent func(context.Context, domain.NormalizedTradeEvent) error) domain.Subscription {
	s.subMu.Lock()
	id := s.nextSubID
	s.nextSubID++
	s.subscribers[id] = onEvent
	s.subMu.Unlock()
	return &subscription{session: s, id: id}
}

type subscription struct {
	session *Session
	id      int
}

func (sub *subscription) Close() error {
	sub.session.subMu.Lock()
	delete(sub.session.subscribers, sub.id)
	sub.session.subMu.Unlock()
	return nil
}

// send writes one JSON message. WebSocket permits only one writer at a
// time, hence writeMu serializing every send regardless of caller.
func (s *Session) send(v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("eabridge: marshal message: %w", err)
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.conn.WriteMessage(websocket.TextMessage, data)
}

// call sends payload and blocks until a response envelope with the same
// requestId arrives (routed by readLoop), ctx is cancelled, or the session
// closes — whichever comes first.
func (s *Session) call(ctx context.Context, requestID string, payload any) (json.RawMessage, error) {
	ch := make(chan json.RawMessage, 1)

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, fmt.Errorf("eabridge: session %s is closed", s.brokerAccountID)
	}
	s.pending[requestID] = ch
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.pending, requestID)
		s.mu.Unlock()
	}()

	if err := s.send(payload); err != nil {
		return nil, fmt.Errorf("eabridge: send request: %w", err)
	}

	select {
	case resp := <-ch:
		return resp, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// RequestSnapshot asks the EA for a live AccountInfoDouble(...) read.
func (s *Session) RequestSnapshot(ctx context.Context) (domain.AccountSnapshot, error) {
	id := newRequestID()
	raw, err := s.call(ctx, id, snapshotRequestMessage{Type: msgTypeSnapshotRequest, RequestID: id})
	if err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("eabridge: request snapshot: %w", err)
	}
	var res snapshotResultMessage
	if err := json.Unmarshal(raw, &res); err != nil {
		return domain.AccountSnapshot{}, fmt.Errorf("eabridge: decode snapshot result: %w", err)
	}
	if res.Error != "" {
		return domain.AccountSnapshot{}, fmt.Errorf("eabridge: EA reported snapshot error: %s", res.Error)
	}
	return domain.AccountSnapshot{
		BrokerAccountID: s.brokerAccountID,
		Currency:        res.Currency,
		Balance:         res.Balance,
		Equity:          res.Equity,
		UsedMargin:      res.UsedMargin,
		FreeMargin:      res.FreeMargin,
		MarginLevelPct:  res.MarginLevelPct,
		AsOf:            res.AsOf,
	}, nil
}

// RequestPositions asks the EA for its full PositionsTotal()/PositionGetX(...) sweep.
func (s *Session) RequestPositions(ctx context.Context) ([]domain.NormalizedPosition, error) {
	id := newRequestID()
	raw, err := s.call(ctx, id, positionsRequestMessage{Type: msgTypePositionsRequest, RequestID: id})
	if err != nil {
		return nil, fmt.Errorf("eabridge: request positions: %w", err)
	}
	var res positionsResultMessage
	if err := json.Unmarshal(raw, &res); err != nil {
		return nil, fmt.Errorf("eabridge: decode positions result: %w", err)
	}
	if res.Error != "" {
		return nil, fmt.Errorf("eabridge: EA reported positions error: %s", res.Error)
	}
	positions := make([]domain.NormalizedPosition, 0, len(res.Positions))
	for _, w := range res.Positions {
		p, err := positionFromWire(w)
		if err != nil {
			return nil, fmt.Errorf("eabridge: decode position in positions result: %w", err)
		}
		positions = append(positions, p)
	}
	return positions, nil
}

// SymbolSpecResult is what RequestSymbolSpec returns — the broker-reported
// trading parameters for one symbol, domain.SymbolSpec minus the
// caller-supplied NormalizedSymbol half (the adapter layer fills that in,
// since it's the one that owns canonical-code derivation).
type SymbolSpecResult struct {
	BrokerSymbolName string
	ContractSize     float64
	LotStep          float64
	MinLot           float64
	MaxLot           float64
	PipSize          float64
	Digits           int
	MarginCurrency   string
}

// RequestSymbolSpec asks the EA for SymbolInfoDouble/Integer/String(...) on
// one broker-native symbol name (e.g. "EURUSD.a").
func (s *Session) RequestSymbolSpec(ctx context.Context, brokerSymbolName string) (SymbolSpecResult, error) {
	id := newRequestID()
	raw, err := s.call(ctx, id, symbolSpecRequestMessage{Type: msgTypeSymbolSpecReq, RequestID: id, BrokerSymbolName: brokerSymbolName})
	if err != nil {
		return SymbolSpecResult{}, fmt.Errorf("eabridge: request symbol spec: %w", err)
	}
	var res symbolSpecResultMessage
	if err := json.Unmarshal(raw, &res); err != nil {
		return SymbolSpecResult{}, fmt.Errorf("eabridge: decode symbol spec result: %w", err)
	}
	if res.Error != "" {
		return SymbolSpecResult{}, fmt.Errorf("eabridge: EA reported symbol spec error for %q: %s", brokerSymbolName, res.Error)
	}
	return SymbolSpecResult{
		BrokerSymbolName: res.BrokerSymbolName,
		ContractSize:     res.ContractSize,
		LotStep:          res.LotStep,
		MinLot:           res.MinLot,
		MaxLot:           res.MaxLot,
		PipSize:          res.PipSize,
		Digits:           res.Digits,
		MarginCurrency:   res.MarginCurrency,
	}, nil
}

// SendOrderCommand issues a PLACE/MODIFY/CLOSE and waits for the EA's
// order_result. A rejected order (Success: false) is a normal, successful
// round trip — not a Go error — mirroring domain.BrokerAdapter's own
// contract (see internal/ctrader/orders.go's identical convention).
func (s *Session) SendOrderCommand(ctx context.Context, cmd OrderCommand) (domain.NormalizedOrderResult, error) {
	id := newRequestID()
	raw, err := s.call(ctx, id, cmd.toWire(id))
	if err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("eabridge: send order command: %w", err)
	}
	var res orderResultMessage
	if err := json.Unmarshal(raw, &res); err != nil {
		return domain.NormalizedOrderResult{}, fmt.Errorf("eabridge: decode order result: %w", err)
	}
	return orderResultFromWire(res), nil
}

// Ping sends a heartbeat and waits for pong — used by HealthCheck to prove
// the socket is actually alive, not merely present in the session registry.
func (s *Session) Ping(ctx context.Context) error {
	id := newRequestID()
	_, err := s.call(ctx, id, pingMessage{Type: msgTypePing, RequestID: id})
	if err != nil {
		return fmt.Errorf("eabridge: ping: %w", err)
	}
	return nil
}

// Close tears down the connection and fails every in-flight call. Safe to
// call more than once.
func (s *Session) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	for id, ch := range s.pending {
		close(ch)
		delete(s.pending, id)
	}
	s.mu.Unlock()
	return s.conn.Close()
}

// readLoop is the single reader for this connection (WebSocket requires
// exactly one), dispatching every inbound frame either to a pending
// request's waiting channel (by requestId) or to the trade-event
// subscribers. Runs until the connection errors/closes, then tears down the
// session so Server's registry and any callers blocked in call() unblock
// promptly instead of hanging until their own ctx times out.
func (s *Session) readLoop(ctx context.Context) {
	defer func() { _ = s.Close() }()
	for {
		_, data, err := s.conn.ReadMessage()
		if err != nil {
			return
		}

		var env envelope
		if err := json.Unmarshal(data, &env); err != nil {
			s.logger.Error("eabridge: malformed frame", "brokerAccountId", s.brokerAccountID, "error", err)
			continue
		}

		switch env.Type {
		case msgTypeTradeEvent:
			s.handleTradeEvent(ctx, data)
		case msgTypeSnapshotResult, msgTypePositionsResult, msgTypeSymbolSpecRes, msgTypeOrderResult, msgTypePong:
			s.resolvePending(env.RequestID, data)
		case msgTypeHello:
			// A second hello on an already-established session is a
			// protocol violation, not a fatal error — ignored rather than
			// torn down, since the connection itself is still healthy.
			s.logger.Warn("eabridge: unexpected hello on established session", "brokerAccountId", s.brokerAccountID)
		default:
			s.logger.Warn("eabridge: unknown message type", "brokerAccountId", s.brokerAccountID, "type", env.Type)
		}
	}
}

func (s *Session) resolvePending(requestID string, data []byte) {
	if requestID == "" {
		return
	}
	s.mu.Lock()
	ch, ok := s.pending[requestID]
	s.mu.Unlock()
	if !ok {
		// Late/duplicate response after the caller's ctx already gave up —
		// not an error, just discarded.
		return
	}
	ch <- json.RawMessage(append([]byte(nil), data...))
}

func (s *Session) handleTradeEvent(ctx context.Context, data []byte) {
	var msg tradeEventMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		s.logger.Error("eabridge: decode trade_event", "brokerAccountId", s.brokerAccountID, "error", err)
		return
	}
	event, err := tradeEventFromWire(s.brokerAccountID, msg)
	if err != nil {
		s.logger.Error("eabridge: map trade_event", "brokerAccountId", s.brokerAccountID, "error", err)
		return
	}

	s.subMu.Lock()
	callbacks := make([]func(context.Context, domain.NormalizedTradeEvent) error, 0, len(s.subscribers))
	for _, cb := range s.subscribers {
		callbacks = append(callbacks, cb)
	}
	s.subMu.Unlock()

	for _, cb := range callbacks {
		if err := cb(ctx, event); err != nil {
			s.logger.Error("eabridge: trade event subscriber failed", "brokerAccountId", s.brokerAccountID, "eventId", event.EventID, "error", err)
		}
	}
}
