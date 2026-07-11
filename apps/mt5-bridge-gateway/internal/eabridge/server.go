// Package eabridge is the WebSocket server MT5/MT4 Expert Advisors dial
// INTO — the inverse direction of TICKET-101's cTrader adapter, which
// dials OUT to Spotware's servers. MetaTrader has no first-party API a
// third-party SaaS can connect to arbitrary end-user accounts with
// (nectrix_plan/docs/07-auth-onboarding-broker-linking.md §7.7), so this
// package implements the chosen self-hosted EA-bridge strategy: a real EA
// attached to the user's own MT5/MT4 terminal dials this server and speaks
// the JSON-over-WebSocket protocol documented in wire.go.
//
// A session is only ever accepted for a pairingToken this process already
// knows about (registered by internal/pairing's discovery loop, which polls
// Core App) — see Server.RegisterPairing.
package eabridge

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/gorilla/websocket"
)

// handshakeTimeout bounds how long a freshly-upgraded socket has to send
// its hello message before the server gives up and closes it — protects
// against a connection that completes the WS upgrade but then never
// speaks (accidental TCP connect, port scan, stalled EA).
const handshakeTimeout = 15 * time.Second

// PairingInfo is what internal/pairing registers per pairingToken —
// everything Server needs to validate + attribute an inbound hello.
type PairingInfo struct {
	BrokerAccountID string
	ExpectedLogin   string
	ExpectedServer  string
	Platform        domain.BrokerType
}

// SessionEventHandler is notified as sessions come and go — used to report
// CONNECTED/DISCONNECTED back to Core App via the same StatusReporter
// contract TICKET-101's reconcile.Loop already established, reused as-is.
type SessionEventHandler interface {
	OnSessionEstablished(ctx context.Context, brokerAccountID string, platform domain.BrokerType)
	OnSessionLost(ctx context.Context, brokerAccountID string)
}

// Server owns the pairing-token registry and the live session set. One
// Server instance serves both MT5 and MT4 EAs (see this package's own doc
// comment) — Platform on each session/pairing distinguishes them, nothing
// else about the wire protocol or connection handling differs.
type Server struct {
	upgrader     websocket.Upgrader
	logger       *slog.Logger
	onTradeEvent func(context.Context, domain.NormalizedTradeEvent) error
	events       SessionEventHandler // may be nil (tests that don't care about status reporting)

	mu       sync.RWMutex
	pairings map[string]PairingInfo  // pairingToken -> info
	sessions map[string]*Session     // brokerAccountID -> live session
}

// NewServer builds a Server. onTradeEvent is wired as every session's first
// subscriber automatically (see handleWS) — in production this is
// tradesignals.Publisher.OnEvent, the same Kafka-publish path TICKET-101's
// cTrader adapter feeds. events may be nil if the caller doesn't need
// connection-status callbacks (e.g. eabridge's own unit tests).
func NewServer(onTradeEvent func(context.Context, domain.NormalizedTradeEvent) error, events SessionEventHandler, logger *slog.Logger) *Server {
	if logger == nil {
		logger = slog.Default()
	}
	return &Server{
		upgrader:     websocket.Upgrader{ReadBufferSize: 4096, WriteBufferSize: 4096, CheckOrigin: func(r *http.Request) bool { return true }},
		logger:       logger,
		onTradeEvent: onTradeEvent,
		events:       events,
		pairings:     make(map[string]PairingInfo),
		sessions:     make(map[string]*Session),
	}
}

// RegisterPairing makes pairingToken acceptable for one connecting EA
// session. Called by internal/pairing's discovery loop for every
// PENDING/CONNECTED MT5/MT4 broker_accounts row it learns about from Core
// App; re-registering an existing token (e.g. the next poll cycle finding
// the same still-pending account) is a harmless overwrite, not an error.
func (s *Server) RegisterPairing(token string, info PairingInfo) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pairings[token] = info
}

// UnregisterPairing removes a pairing token — called once Core App no
// longer lists the account (e.g. unlinked), so a stale/leaked token can't
// pair a new EA session after the fact.
func (s *Server) UnregisterPairing(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.pairings, token)
}

func (s *Server) lookupPairing(token string) (PairingInfo, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	info, ok := s.pairings[token]
	return info, ok
}

// Session returns the live session for a broker_accounts.id, if any EA is
// currently connected and paired for it.
func (s *Server) Session(brokerAccountID string) (*Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	sess, ok := s.sessions[brokerAccountID]
	return sess, ok
}

// AnySession returns an arbitrary live session for the given platform —
// used for account-agnostic-in-practice metadata calls (ResolveSymbol/
// GetSymbolSpecification, whose domain.BrokerAdapter signature carries no
// ConnectionHandle), mirroring internal/ctrader's own anyConnection()
// compromise for the identical interface-shape reason.
func (s *Server) AnySession(platform domain.BrokerType) (*Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, sess := range s.sessions {
		if sess.Platform() == platform {
			return sess, true
		}
	}
	return nil, false
}

// Handler is the http.Handler to mount at the EA's WebSocket URL (e.g.
// mux.Handle("/ea/ws", server.Handler())).
func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(s.handleWS)
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.logger.Warn("eabridge: websocket upgrade failed", "error", err, "remoteAddr", r.RemoteAddr)
		return
	}

	_ = conn.SetReadDeadline(time.Now().Add(handshakeTimeout))
	_, data, err := conn.ReadMessage()
	if err != nil {
		s.logger.Warn("eabridge: no hello received before handshake timeout", "error", err, "remoteAddr", r.RemoteAddr)
		_ = conn.Close()
		return
	}

	var hello helloMessage
	if err := json.Unmarshal(data, &hello); err != nil || hello.Type != msgTypeHello {
		s.rejectAndClose(conn, "expected a hello message first")
		return
	}

	info, ok := s.lookupPairing(hello.PairingToken)
	if !ok {
		s.rejectAndClose(conn, "unknown or expired pairing token")
		return
	}
	if info.ExpectedLogin != hello.Login || info.ExpectedServer != hello.Server {
		s.rejectAndClose(conn, "login/server does not match the account this pairing token was issued for")
		return
	}
	if hello.Platform != string(info.Platform) {
		s.rejectAndClose(conn, fmt.Sprintf("platform mismatch: pairing token is for %s", info.Platform))
		return
	}

	_ = conn.SetReadDeadline(time.Time{})
	session := newSession(info.BrokerAccountID, info.Platform, hello.Login, hello.Server, conn, s.logger)

	if s.onTradeEvent != nil {
		session.Subscribe(s.onTradeEvent)
	}

	ack, _ := json.Marshal(helloAckMessage{Type: msgTypeHelloAck, Accepted: true, BrokerAccountID: info.BrokerAccountID})
	if err := session.send(json.RawMessage(ack)); err != nil {
		s.logger.Warn("eabridge: failed to send hello_ack", "brokerAccountId", info.BrokerAccountID, "error", err)
		_ = conn.Close()
		return
	}

	s.mu.Lock()
	if old, exists := s.sessions[info.BrokerAccountID]; exists {
		// A new EA session for the same account supersedes any stale prior
		// one (e.g. the terminal restarted without a clean disconnect) —
		// closed outside the lock since Close() may block briefly.
		s.mu.Unlock()
		_ = old.Close()
		s.mu.Lock()
	}
	s.sessions[info.BrokerAccountID] = session
	s.mu.Unlock()

	s.logger.Info("eabridge: EA session established", "brokerAccountId", info.BrokerAccountID, "platform", info.Platform, "login", hello.Login, "server", hello.Server)

	// TICKET-103: readLoop is started FIRST, in its own goroutine, before
	// OnSessionEstablished runs — real, live-verified deadlock hazard found
	// while wiring symbol-mapping auto-suggestion: OnSessionEstablished may
	// need to issue a synchronous RequestSymbolSpec call (session.call,
	// blocking on <-ch until readLoop's resolvePending delivers a
	// response), but readLoop previously only started AFTER
	// OnSessionEstablished returned — no reader would ever exist to
	// deliver that response, hanging forever. This preserves handleWS's
	// existing "block until the session truly ends" behavior (<-done),
	// just reordered so a reader is always live before any handler code
	// that might need one runs.
	readLoopDone := make(chan struct{})
	go func() {
		session.readLoop(r.Context())
		close(readLoopDone)
	}()
	if s.events != nil {
		s.events.OnSessionEstablished(r.Context(), info.BrokerAccountID, info.Platform)
	}
	<-readLoopDone

	s.mu.Lock()
	if s.sessions[info.BrokerAccountID] == session {
		delete(s.sessions, info.BrokerAccountID)
	}
	s.mu.Unlock()

	s.logger.Info("eabridge: EA session lost", "brokerAccountId", info.BrokerAccountID)
	if s.events != nil {
		s.events.OnSessionLost(context.Background(), info.BrokerAccountID)
	}
}

func (s *Server) rejectAndClose(conn *websocket.Conn, reason string) {
	ack, _ := json.Marshal(helloAckMessage{Type: msgTypeHelloAck, Accepted: false, Reason: reason})
	_ = conn.WriteMessage(websocket.TextMessage, ack)
	s.logger.Warn("eabridge: rejected EA handshake", "reason", reason)
	_ = conn.Close()
}
