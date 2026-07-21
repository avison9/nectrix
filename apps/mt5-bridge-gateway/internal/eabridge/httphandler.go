package eabridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// TICKET-121 — the HTTP long-polling counterpart to handleWS, for MT4's
// EA (MQL4 has no native Socket*() functions, so it speaks WebRequest()
// long-polling instead of a persistent WebSocket — see this package's own
// README section). Three routes, mounted separately since each is a
// distinct request/response shape (unlike the single WS upgrade):
//
//	POST /ea/hello  — same handshake handleWS does, returns a sessionToken
//	                  instead of upgrading the connection.
//	POST /ea/poll   — the EA's own long-poll for pending gateway->EA
//	                  messages (hello_ack was already delivered by /ea/hello
//	                  itself; this carries *_request/order_command/ping).
//	POST /ea/events — the EA pushes one EA->gateway message per call
//	                  (trade_event/*_result/pong).
//
// A live HTTP-backed session is otherwise indistinguishable from a
// WebSocket one to every other part of this package — Session/readLoop/
// call/the Server.sessions registry/SessionEventHandler callbacks all run
// completely unchanged, since httpPollConn satisfies the same wsConn
// interface handleWS's real *websocket.Conn does (see httpconn.go).
const (
	httpPollMaxWait        = 20 * time.Second
	httpSessionIdleTimeout = 90 * time.Second
	httpSweepInterval      = 15 * time.Second
)

// httpEASession is what /ea/poll and /ea/events look up by sessionToken —
// deliberately NOT the same registry as Server.sessions (keyed by
// brokerAccountID, one live session max): a sessionToken is this specific
// HTTP-transport connection's own identity, needed because HTTP has no
// persistent socket handleWS could otherwise dispatch on directly.
type httpEASession struct {
	token           string
	brokerAccountID string
	conn            *httpPollConn
	lastPollAt      time.Time
}

func newSessionToken() string {
	buf := make([]byte, 24)
	_, _ = rand.Read(buf) // crypto/rand.Read never errors on a valid buffer
	return hex.EncodeToString(buf)
}

// helloAckHTTPMessage is helloAckMessage plus sessionToken — the one wire
// difference from the WebSocket handshake's own hello_ack, since HTTP has
// no persistent connection identity to hang subsequent poll/events calls
// off of otherwise.
type helloAckHTTPMessage struct {
	Type            string `json:"type"`
	Accepted        bool   `json:"accepted"`
	BrokerAccountID string `json:"brokerAccountId,omitempty"`
	SessionToken    string `json:"sessionToken,omitempty"`
	Reason          string `json:"reason,omitempty"`
}

// HelloHandler is the http.Handler to mount at POST /ea/hello.
func (s *Server) HelloHandler() http.Handler {
	return http.HandlerFunc(s.handleHTTPHello)
}

// PollHandler is the http.Handler to mount at POST /ea/poll.
func (s *Server) PollHandler() http.Handler {
	return http.HandlerFunc(s.handleHTTPPoll)
}

// EventsHandler is the http.Handler to mount at POST /ea/events.
func (s *Server) EventsHandler() http.Handler {
	return http.HandlerFunc(s.handleHTTPEvents)
}

func (s *Server) handleHTTPHello(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var hello helloMessage
	if err := json.NewDecoder(r.Body).Decode(&hello); err != nil || hello.Type != msgTypeHello {
		s.writeHelloAckHTTP(w, false, "", "", "expected a hello message")
		return
	}

	info, ok := s.lookupPairing(hello.PairingToken)
	if !ok {
		s.writeHelloAckHTTP(w, false, "", "", "unknown or expired pairing token")
		return
	}
	if info.ExpectedLogin != hello.Login || info.ExpectedServer != hello.Server {
		s.writeHelloAckHTTP(w, false, "", "", "login/server does not match the account this pairing token was issued for")
		return
	}
	if hello.Platform != string(info.Platform) {
		s.writeHelloAckHTTP(w, false, "", "", fmt.Sprintf("platform mismatch: pairing token is for %s", info.Platform))
		return
	}

	conn := newHTTPPollConn()
	session := newSession(info.BrokerAccountID, info.Platform, hello.Login, hello.Server, conn, s.logger)
	if s.onTradeEvent != nil {
		session.Subscribe(s.onTradeEvent)
	}
	token := newSessionToken()

	s.mu.Lock()
	if old, exists := s.sessions[info.BrokerAccountID]; exists {
		// Same "a new session supersedes any stale prior one" rule handleWS
		// applies — closed outside the lock since Close() may block briefly.
		s.mu.Unlock()
		_ = old.Close()
		s.mu.Lock()
	}
	s.sessions[info.BrokerAccountID] = session
	s.mu.Unlock()

	s.httpMu.Lock()
	s.httpSessions[token] = &httpEASession{
		token:           token,
		brokerAccountID: info.BrokerAccountID,
		conn:            conn,
		lastPollAt:      time.Now(),
	}
	s.httpMu.Unlock()

	s.logger.Info("eabridge: EA session established (http)", "brokerAccountId", info.BrokerAccountID, "platform", info.Platform, "login", hello.Login, "server", hello.Server)

	// Same ordering discipline as handleWS (TICKET-103's own deadlock
	// finding, see that method's comment): readLoop must already be running
	// before OnSessionEstablished, which may issue a synchronous call()
	// that blocks waiting for a reader to deliver its response.
	go func() {
		session.readLoop(context.Background())

		s.mu.Lock()
		if s.sessions[info.BrokerAccountID] == session {
			delete(s.sessions, info.BrokerAccountID)
		}
		s.mu.Unlock()

		s.httpMu.Lock()
		delete(s.httpSessions, token)
		s.httpMu.Unlock()

		s.logger.Info("eabridge: EA session lost (http)", "brokerAccountId", info.BrokerAccountID)
		if s.events != nil {
			s.events.OnSessionLost(context.Background(), info.BrokerAccountID)
		}
	}()
	if s.events != nil {
		s.events.OnSessionEstablished(r.Context(), info.BrokerAccountID, info.Platform)
	}

	s.writeHelloAckHTTP(w, true, info.BrokerAccountID, token, "")
}

func (s *Server) writeHelloAckHTTP(w http.ResponseWriter, accepted bool, brokerAccountID, sessionToken, reason string) {
	if !accepted {
		s.logger.Warn("eabridge: rejected EA HTTP handshake", "reason", reason)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(helloAckHTTPMessage{
		Type:            msgTypeHelloAck,
		Accepted:        accepted,
		BrokerAccountID: brokerAccountID,
		SessionToken:    sessionToken,
		Reason:          reason,
	})
}

type pollRequest struct {
	SessionToken string `json:"sessionToken"`
}

type pollResponse struct {
	Messages []json.RawMessage `json:"messages"`
}

func (s *Server) handleHTTPPoll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req pollRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "malformed request", http.StatusBadRequest)
		return
	}
	sess, ok := s.touchHTTPSession(req.SessionToken)
	if !ok {
		http.Error(w, "unknown session token", http.StatusUnauthorized)
		return
	}

	raws := sess.conn.drainOutbox(httpPollMaxWait)
	messages := make([]json.RawMessage, len(raws))
	for i, raw := range raws {
		messages[i] = json.RawMessage(raw)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(pollResponse{Messages: messages})
}

type eventsRequest struct {
	SessionToken string          `json:"sessionToken"`
	Message      json.RawMessage `json:"message"`
}

func (s *Server) handleHTTPEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req eventsRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "malformed request", http.StatusBadRequest)
		return
	}
	sess, ok := s.touchHTTPSession(req.SessionToken)
	if !ok {
		http.Error(w, "unknown session token", http.StatusUnauthorized)
		return
	}
	if err := sess.conn.pushInbound(req.Message); err != nil {
		http.Error(w, "session closed", http.StatusGone)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// touchHTTPSession looks up a sessionToken and, if found, records this call
// as proof of life (see sweepIdleHTTPSessions) — every /ea/poll and
// /ea/events call counts, not just poll, so an EA that's busy pushing a
// burst of trade events via /ea/events still counts as alive even if its
// next scheduled poll is still a moment away.
func (s *Server) touchHTTPSession(token string) (*httpEASession, bool) {
	s.httpMu.Lock()
	defer s.httpMu.Unlock()
	sess, ok := s.httpSessions[token]
	if ok {
		sess.lastPollAt = time.Now()
	}
	return sess, ok
}

// sweepIdleHTTPSessions runs for the lifetime of the process (started once
// from NewServer), closing any HTTP-backed session that hasn't been heard
// from within httpSessionIdleTimeout — unlike a real WebSocket, an HTTP
// long-poll session has no transport-level "connection dropped" signal
// (the EA's terminal could simply stop running), so this is the only way a
// dead session's brokerAccountID is ever freed up for a future reconnect
// and OnSessionLost ever fires for it.
func (s *Server) sweepIdleHTTPSessions() {
	ticker := time.NewTicker(httpSweepInterval)
	defer ticker.Stop()
	for range ticker.C {
		s.httpMu.Lock()
		var stale []*httpEASession
		for token, sess := range s.httpSessions {
			if time.Since(sess.lastPollAt) > httpSessionIdleTimeout {
				stale = append(stale, sess)
				delete(s.httpSessions, token)
			}
		}
		s.httpMu.Unlock()

		for _, sess := range stale {
			s.logger.Warn("eabridge: closing idle http EA session (no poll/events within timeout)", "brokerAccountId", sess.brokerAccountID)
			// Close makes the session's own readLoop's blocked ReadMessage
			// return an error, which runs the exact same teardown
			// (sessions registry cleanup + OnSessionLost) the hello
			// handler's own goroutine already sets up for any other cause
			// of session death.
			_ = sess.conn.Close()
		}
	}
}
