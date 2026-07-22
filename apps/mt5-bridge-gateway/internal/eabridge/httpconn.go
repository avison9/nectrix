package eabridge

import (
	"errors"
	"sync"
	"time"
)

// errHTTPConnClosed is returned by httpPollConn's ReadMessage/WriteMessage
// once Close has run — the same "further use fails" contract a real
// *websocket.Conn gives Session after its own Close.
var errHTTPConnClosed = errors.New("eabridge: http-poll connection closed")

// httpPollConn implements wsConn (see session.go) over two buffered
// channels instead of a real socket, so Session/readLoop/call — every bit
// of request/response correlation, subscriber fan-out, and close semantics
// — run completely unchanged regardless of which transport actually carries
// the bytes to and from the EA (TICKET-121: MQL4 has no native Socket*()
// functions, so its EA speaks HTTP long-polling instead of a persistent
// WebSocket; TICKET-102's MT5 EA is unaffected, still real WebSocket).
//
//   - Gateway -> EA messages (hello_ack, *_request, order_command, ping):
//     Session.send -> WriteMessage -> outbox. The EA's own next POST
//     /ea/poll call drains outbox via drainOutbox.
//   - EA -> Gateway messages (trade_event, *_result, pong): the EA's own
//     POST /ea/events call pushes each one via pushInbound -> inbox ->
//     Session.readLoop's blocking ReadMessage.
type httpPollConn struct {
	outbox chan []byte
	inbox  chan []byte

	closeOnce sync.Once
	closed    chan struct{}
}

func newHTTPPollConn() *httpPollConn {
	return &httpPollConn{
		// Buffered generously — a burst of trade events or queued commands
		// must never block the handler goroutine that's pushing/draining
		// them on the EA's own poll cadence.
		outbox: make(chan []byte, 64),
		inbox:  make(chan []byte, 64),
		closed: make(chan struct{}),
	}
}

// ReadMessage blocks until the EA posts a message via /ea/events (pushInbound)
// or the connection closes. messageType is always reported as 1 (text) —
// Session never inspects it (see session.go's own wsConn interface), this
// exists only to satisfy the shared method signature.
func (c *httpPollConn) ReadMessage() (int, []byte, error) {
	select {
	case data, ok := <-c.inbox:
		if !ok {
			return 0, nil, errHTTPConnClosed
		}
		return 1, data, nil
	case <-c.closed:
		return 0, nil, errHTTPConnClosed
	}
}

// WriteMessage queues data for delivery on the EA's next /ea/poll call.
func (c *httpPollConn) WriteMessage(_ int, data []byte) error {
	select {
	case c.outbox <- append([]byte(nil), data...):
		return nil
	case <-c.closed:
		return errHTTPConnClosed
	}
}

func (c *httpPollConn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

// drainOutbox is the /ea/poll handler's own call — the "long" half of long-
// polling: waits up to maxWait for at least one queued message before
// returning, so an EA polling in a tight loop isn't hammering the gateway
// with empty responses, but never blocks past maxWait regardless (MQL4's
// WebRequest() is synchronous and blocks the EA's single thread for the
// call's full duration, so maxWait directly bounds how long the EA's own
// OnTimer tick stalls). Once at least one message is queued, drains
// everything else already sitting in outbox too (non-blocking), so several
// messages queued in the same window come back together in one poll.
func (c *httpPollConn) drainOutbox(maxWait time.Duration) [][]byte {
	var out [][]byte
	select {
	case msg := <-c.outbox:
		out = append(out, msg)
	case <-time.After(maxWait):
		return out
	case <-c.closed:
		return out
	}
	for {
		select {
		case msg := <-c.outbox:
			out = append(out, msg)
		default:
			return out
		}
	}
}

// pushInbound is the /ea/events handler's own call, one per message the EA
// posted in a single events request.
func (c *httpPollConn) pushInbound(data []byte) error {
	select {
	case c.inbox <- append([]byte(nil), data...):
		return nil
	case <-c.closed:
		return errHTTPConnClosed
	}
}
