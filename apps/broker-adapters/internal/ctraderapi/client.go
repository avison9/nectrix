// Package ctraderapi is the low-level cTrader Open API client — TLS dial,
// wire framing, request/response correlation, and streamed-event dispatch.
// It knows nothing about this platform's own normalized domain types; that
// mapping lives one layer up, in internal/ctrader. Every payload type/field
// name/host/framing detail here is verified against Spotware's real,
// published sources (see packages/ctrader-proto/README.md), not recalled
// from memory — protobuf interop breaks silently and unhelpfully on a wrong
// field number, so nothing here is guessed.
package ctraderapi

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"log/slog"
	"net"
	"sync"
	"time"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	"github.com/google/uuid"
	"google.golang.org/protobuf/proto"
)

const (
	// DemoHost / LiveHost — confirmed via cTrader's own help centre
	// (help.ctrader.com/open-api/proxies-endpoints). Demo and live are
	// entirely separate connections; a credential for one is never valid
	// against the other.
	DemoHost = "demo.ctraderapi.com:5035"
	LiveHost = "live.ctraderapi.com:5035"

	// heartbeatInterval is comfortably under the documented 30s idle
	// timeout (help.ctrader.com/open-api/connection) — sent only if
	// nothing else was written in that window.
	heartbeatInterval = 20 * time.Second

	// maxFrameSize guards against a corrupt/hostile length prefix causing
	// an unbounded allocation — cTrader's own messages are documented as
	// comfortably under 1MB; this is a generous ceiling, not a tight limit.
	maxFrameSize = 4 << 20 // 4MiB

	// pendingRequestTimeout is how long Request() waits for a correlated
	// response before giving up — the connection itself may still be fine,
	// this just stops a caller blocking forever if a response is dropped.
	pendingRequestTimeout = 15 * time.Second
)

// Client is one TCP+TLS connection to a cTrader Open API host. Not safe to
// Dial concurrently reused across unrelated logical sessions — internal/ctrader
// holds one Client per ConnectionHandle.
type Client struct {
	conn   net.Conn
	reader *bufio.Reader

	writeMu     sync.Mutex // serializes frame writes on the shared conn
	lastWriteAt time.Time  // guards the heartbeat's "only if idle" rule

	pendingMu sync.Mutex
	pending   map[string]chan *openapi.ProtoMessage // clientMsgId -> waiting Request() call

	events chan *openapi.ProtoMessage // unsolicited messages (spot/execution events, errors with no matching clientMsgId)

	closeOnce sync.Once
	closed    chan struct{} // closed by Close() — signals every blocked call to give up

	readLoopDoneOnce sync.Once
	readLoopDone     chan struct{} // closed once by readLoop when it exits, for any reason (Close() or the remote end dropping) — the real "this Client is dead" signal

	logger *slog.Logger
}

// Dial opens a TLS connection to host (DemoHost or LiveHost), starts the
// background read loop, and returns a Client ready for ApplicationAuth.
// Reconnection is the caller's responsibility (internal/ctrader's
// reconnect/backoff loop) — Dial itself does not retry.
func Dial(ctx context.Context, host string, logger *slog.Logger) (*Client, error) {
	if logger == nil {
		logger = slog.Default()
	}
	dialer := &tls.Dialer{Config: &tls.Config{MinVersion: tls.VersionTLS12}}
	conn, err := dialer.DialContext(ctx, "tcp", host)
	if err != nil {
		return nil, fmt.Errorf("ctraderapi: dial %s: %w", host, err)
	}
	return newClient(conn, logger.With("host", host)), nil
}

// NewForTesting wires up a Client around an already-established conn,
// skipping the real TLS dial — for internal/ctrader's own tests (and any
// other package that needs a Client against a fake in-memory server, e.g.
// net.Pipe()). Real callers use Dial.
func NewForTesting(conn net.Conn, logger *slog.Logger) *Client {
	if logger == nil {
		logger = slog.Default()
	}
	return newClient(conn, logger)
}

// newClient wires up a Client around an already-established conn — the
// TLS/TCP-specific part of Dial, factored out so framing/correlation logic
// is testable against a plain net.Pipe() without a real (or fake-TLS)
// socket. logger must be non-nil; Dial supplies a default.
func newClient(conn net.Conn, logger *slog.Logger) *Client {
	c := &Client{
		conn:         conn,
		reader:       bufio.NewReader(conn),
		pending:      make(map[string]chan *openapi.ProtoMessage),
		events:       make(chan *openapi.ProtoMessage, 256),
		closed:       make(chan struct{}),
		readLoopDone: make(chan struct{}),
		logger:       logger.With("component", "ctraderapi"),
	}
	go c.readLoop()
	go c.heartbeatLoop()
	return c
}

// Events delivers every inbound message that wasn't a correlated Request()
// response — spot price ticks, execution events, and out-of-band errors.
// internal/ctrader is responsible for draining this promptly; it's buffered
// but not unbounded. Never closed — see Done() for connection-death signaling.
func (c *Client) Events() <-chan *openapi.ProtoMessage {
	return c.events
}

// Done is closed exactly once, the moment this Client stops being usable —
// either Close() was called, or (more commonly, in production) the read
// loop hit an error because the remote end dropped the connection. This is
// the real "reconnect now" signal for internal/ctrader's reconnect loop;
// Events() itself is never closed, so a bare `range` over it would never
// terminate on its own.
func (c *Client) Done() <-chan struct{} {
	return c.readLoopDone
}

// Close terminates the connection and stops the background loops. Safe to
// call more than once.
func (c *Client) Close() error {
	var err error
	c.closeOnce.Do(func() {
		close(c.closed)
		err = c.conn.Close()
	})
	return err
}

// Request sends req under payloadType, waits for the correlated response
// (matched by a client-generated clientMsgId — see ProtoMessage's own
// doc-comment in OpenApiCommonMessages.proto: "Request message id, assigned
// by the client that will be returned in the response"), and unmarshals it
// into resp. resp must be a pointer to the expected response message type.
func (c *Client) Request(ctx context.Context, payloadType uint32, req proto.Message, resp proto.Message) error {
	payload, err := proto.Marshal(req)
	if err != nil {
		return fmt.Errorf("ctraderapi: marshal request payload: %w", err)
	}

	clientMsgID := uuid.NewString()
	waiter := make(chan *openapi.ProtoMessage, 1)
	c.pendingMu.Lock()
	c.pending[clientMsgID] = waiter
	c.pendingMu.Unlock()
	defer func() {
		c.pendingMu.Lock()
		delete(c.pending, clientMsgID)
		c.pendingMu.Unlock()
	}()

	envelope := &openapi.ProtoMessage{
		PayloadType: proto.Uint32(payloadType),
		Payload:     payload,
		ClientMsgId: proto.String(clientMsgID),
	}
	if err := c.writeFrame(envelope); err != nil {
		return err
	}

	timeout := pendingRequestTimeout
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case msg := <-waiter:
		if msg.GetPayloadType() == uint32(openapiErrorPayloadType) {
			errRes := &openapi.ProtoErrorRes{}
			if err := proto.Unmarshal(msg.GetPayload(), errRes); err == nil {
				return fmt.Errorf("ctraderapi: %s: %s", errRes.GetErrorCode(), errRes.GetDescription())
			}
			return fmt.Errorf("ctraderapi: request failed with an unparseable error response")
		}
		return proto.Unmarshal(msg.GetPayload(), resp)
	case <-timer.C:
		return fmt.Errorf("ctraderapi: request %s timed out after %s", clientMsgID, timeout)
	case <-ctx.Done():
		return ctx.Err()
	case <-c.closed:
		return fmt.Errorf("ctraderapi: connection closed while awaiting response")
	}
}

// openapiErrorPayloadType is ProtoPayloadType_ERROR_RES (=50) — shared
// across every OA request, so a caller's Request() call surfaces a real
// cTrader-side error (bad token, unknown symbol, ...) as a Go error instead
// of a confusing "wrong message type" unmarshal failure.
const openapiErrorPayloadType = openapi.ProtoPayloadType_ERROR_RES

func (c *Client) writeFrame(msg *openapi.ProtoMessage) error {
	data, err := proto.Marshal(msg)
	if err != nil {
		return fmt.Errorf("ctraderapi: marshal envelope: %w", err)
	}

	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	var lengthPrefix [4]byte
	binary.BigEndian.PutUint32(lengthPrefix[:], uint32(len(data)))
	if _, err := c.conn.Write(lengthPrefix[:]); err != nil {
		return fmt.Errorf("ctraderapi: write length prefix: %w", err)
	}
	if _, err := c.conn.Write(data); err != nil {
		return fmt.Errorf("ctraderapi: write frame body: %w", err)
	}
	c.lastWriteAt = time.Now()
	return nil
}

// readLoop is the connection's single reader — cTrader's wire format is a
// 4-byte big-endian length prefix (confirmed against spotware/OpenApiPy's
// tcpProtocol.py, an Int32StringReceiver: "The length prefix is 4 bytes in
// network byte order") followed by that many bytes of a serialized
// ProtoMessage envelope.
func (c *Client) readLoop() {
	defer c.readLoopDoneOnce.Do(func() { close(c.readLoopDone) })
	for {
		var lengthPrefix [4]byte
		if _, err := io.ReadFull(c.reader, lengthPrefix[:]); err != nil {
			c.logger.Warn("ctraderapi read loop: connection closed", "error", err)
			return
		}
		length := binary.BigEndian.Uint32(lengthPrefix[:])
		if length > maxFrameSize {
			c.logger.Error("ctraderapi read loop: frame exceeds max size, closing connection", "length", length)
			_ = c.Close()
			return
		}

		body := make([]byte, length)
		if _, err := io.ReadFull(c.reader, body); err != nil {
			c.logger.Warn("ctraderapi read loop: connection closed mid-frame", "error", err)
			return
		}

		msg := &openapi.ProtoMessage{}
		if err := proto.Unmarshal(body, msg); err != nil {
			c.logger.Error("ctraderapi read loop: dropping unparseable frame", "error", err)
			continue
		}

		c.dispatch(msg)
	}
}

func (c *Client) dispatch(msg *openapi.ProtoMessage) {
	if msg.GetPayloadType() == uint32(openapi.ProtoPayloadType_HEARTBEAT_EVENT) {
		return // liveness-only, nothing to deliver
	}

	if clientMsgID := msg.GetClientMsgId(); clientMsgID != "" {
		c.pendingMu.Lock()
		waiter, ok := c.pending[clientMsgID]
		c.pendingMu.Unlock()
		if ok {
			waiter <- msg
			return
		}
	}

	select {
	case c.events <- msg:
	default:
		c.logger.Error("ctraderapi: events channel full, dropping message", "payloadType", msg.GetPayloadType())
	}
}

// heartbeatLoop sends ProtoHeartbeatEvent only if the connection has been
// idle — "Open API client can send this message when he needs to keep the
// connection open for a period without other messages longer than 30
// seconds" (OpenApiCommonMessages.proto's own doc-comment). A connection
// with regular real traffic (order placement, account-auth, ...) never
// needs this; a quiet StreamTradeEvents subscription does.
func (c *Client) heartbeatLoop() {
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			c.writeMu.Lock()
			idle := time.Since(c.lastWriteAt) >= heartbeatInterval
			c.writeMu.Unlock()
			if !idle {
				continue
			}
			heartbeat := &openapi.ProtoHeartbeatEvent{}
			payload, err := proto.Marshal(heartbeat)
			if err != nil {
				continue
			}
			envelope := &openapi.ProtoMessage{
				PayloadType: proto.Uint32(uint32(openapi.ProtoPayloadType_HEARTBEAT_EVENT)),
				Payload:     payload,
			}
			if err := c.writeFrame(envelope); err != nil {
				c.logger.Warn("ctraderapi: heartbeat write failed", "error", err)
			}
		case <-c.closed:
			return
		}
	}
}
