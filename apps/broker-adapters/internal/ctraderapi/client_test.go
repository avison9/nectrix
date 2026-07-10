package ctraderapi

import (
	"context"
	"encoding/binary"
	"io"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	"google.golang.org/protobuf/proto"
)

// testLogger discards output — the framing/dispatch tests assert on real
// protocol behavior, not log content.
func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// fakeServer wraps one end of a net.Pipe() with the same 4-byte-BE
// length-prefixed ProtoMessage framing a real cTrader server uses, so tests
// exercise Client's actual wire format — not a mock of it.
type fakeServer struct {
	conn net.Conn
}

func (s *fakeServer) readFrame(t *testing.T) *openapi.ProtoMessage {
	t.Helper()
	var lengthPrefix [4]byte
	if _, err := io.ReadFull(s.conn, lengthPrefix[:]); err != nil {
		t.Fatalf("fakeServer: read length prefix: %v", err)
	}
	length := binary.BigEndian.Uint32(lengthPrefix[:])
	body := make([]byte, length)
	if _, err := io.ReadFull(s.conn, body); err != nil {
		t.Fatalf("fakeServer: read body: %v", err)
	}
	msg := &openapi.ProtoMessage{}
	if err := proto.Unmarshal(body, msg); err != nil {
		t.Fatalf("fakeServer: unmarshal envelope: %v", err)
	}
	return msg
}

func (s *fakeServer) writeFrame(t *testing.T, msg *openapi.ProtoMessage) {
	t.Helper()
	data, err := proto.Marshal(msg)
	if err != nil {
		t.Fatalf("fakeServer: marshal envelope: %v", err)
	}
	var lengthPrefix [4]byte
	binary.BigEndian.PutUint32(lengthPrefix[:], uint32(len(data)))
	if _, err := s.conn.Write(lengthPrefix[:]); err != nil {
		t.Fatalf("fakeServer: write length prefix: %v", err)
	}
	if _, err := s.conn.Write(data); err != nil {
		t.Fatalf("fakeServer: write body: %v", err)
	}
}

func newTestClient(t *testing.T) (*Client, *fakeServer) {
	t.Helper()
	clientConn, serverConn := net.Pipe()
	c := newClient(clientConn, testLogger())
	t.Cleanup(func() { _ = c.Close() })
	return c, &fakeServer{conn: serverConn}
}

// TestRequestResponseRoundTrip proves the real wire format end-to-end: a
// real ApplicationAuthReq is framed with the documented 4-byte big-endian
// length prefix, decoded correctly server-side, and the correlated response
// (matched by clientMsgId, per ProtoMessage's own doc-comment) is unmarshaled
// back into the caller's response struct.
func TestRequestResponseRoundTrip(t *testing.T) {
	c, server := newTestClient(t)

	type result struct {
		err error
	}
	done := make(chan result, 1)
	go func() {
		req := &openapi.ProtoOAApplicationAuthReq{ClientId: proto.String("ignored-in-this-test"), ClientSecret: proto.String("also-ignored")}
		resp := &openapi.ProtoOAApplicationAuthRes{}
		err := c.Request(context.Background(), uint32(openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_REQ), req, resp)
		done <- result{err: err}
	}()

	frame := server.readFrame(t)
	if frame.GetPayloadType() != uint32(openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_REQ) {
		t.Fatalf("server saw payloadType %d, want PROTO_OA_APPLICATION_AUTH_REQ (%d)", frame.GetPayloadType(), openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_REQ)
	}
	if frame.GetClientMsgId() == "" {
		t.Fatal("server saw an empty clientMsgId — Request() must always set one for correlation")
	}
	sentReq := &openapi.ProtoOAApplicationAuthReq{}
	if err := proto.Unmarshal(frame.GetPayload(), sentReq); err != nil {
		t.Fatalf("server: unmarshal request payload: %v", err)
	}
	if sentReq.GetClientId() != "ignored-in-this-test" {
		t.Fatalf("server saw clientId %q, want %q", sentReq.GetClientId(), "ignored-in-this-test")
	}

	// Real server behavior: the response echoes the same clientMsgId.
	respPayload, err := proto.Marshal(&openapi.ProtoOAApplicationAuthRes{})
	if err != nil {
		t.Fatalf("marshal response: %v", err)
	}
	server.writeFrame(t, &openapi.ProtoMessage{
		PayloadType: proto.Uint32(uint32(openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_RES)),
		Payload:     respPayload,
		ClientMsgId: frame.ClientMsgId,
	})

	select {
	case r := <-done:
		if r.err != nil {
			t.Fatalf("Request() returned error: %v", r.err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Request() did not return within 5s")
	}
}

// TestRequestSurfacesRealErrorResponse proves a real ProtoErrorRes (the
// shape cTrader sends for a bad token, unknown symbol, etc.) surfaces as a
// Go error carrying the real errorCode/description, not a generic unmarshal
// failure.
func TestRequestSurfacesRealErrorResponse(t *testing.T) {
	c, server := newTestClient(t)

	type result struct {
		err error
	}
	done := make(chan result, 1)
	go func() {
		req := &openapi.ProtoOAAccountAuthReq{CtidTraderAccountId: proto.Int64(1), AccessToken: proto.String("expired")}
		resp := &openapi.ProtoOAAccountAuthRes{}
		err := c.Request(context.Background(), uint32(openapi.ProtoOAPayloadType_PROTO_OA_ACCOUNT_AUTH_REQ), req, resp)
		done <- result{err: err}
	}()

	frame := server.readFrame(t)

	errPayload, err := proto.Marshal(&openapi.ProtoErrorRes{
		ErrorCode:   proto.String("OA_AUTH_TOKEN_EXPIRED"),
		Description: proto.String("the access token has expired"),
	})
	if err != nil {
		t.Fatalf("marshal error response: %v", err)
	}
	server.writeFrame(t, &openapi.ProtoMessage{
		PayloadType: proto.Uint32(uint32(openapi.ProtoPayloadType_ERROR_RES)),
		Payload:     errPayload,
		ClientMsgId: frame.ClientMsgId,
	})

	select {
	case r := <-done:
		if r.err == nil {
			t.Fatal("Request() returned nil error for a real ProtoErrorRes response")
		}
		if got := r.err.Error(); !strings.Contains(got, "OA_AUTH_TOKEN_EXPIRED") || !strings.Contains(got, "expired") {
			t.Fatalf("error %q does not surface the real errorCode/description", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Request() did not return within 5s")
	}
}

// TestUnsolicitedEventsAreDispatchedSeparately proves a message with no
// clientMsgId (a real streamed ProtoOASpotEvent/ProtoOAExecutionEvent) lands
// on Events(), never on a pending Request() waiter.
func TestUnsolicitedEventsAreDispatchedSeparately(t *testing.T) {
	c, server := newTestClient(t)

	spotPayload, err := proto.Marshal(&openapi.ProtoOASpotEvent{
		CtidTraderAccountId: proto.Int64(1),
		SymbolId:            proto.Int64(1),
	})
	if err != nil {
		t.Fatalf("marshal spot event: %v", err)
	}
	server.writeFrame(t, &openapi.ProtoMessage{
		PayloadType: proto.Uint32(uint32(openapi.ProtoOAPayloadType_PROTO_OA_SPOT_EVENT)),
		Payload:     spotPayload,
		// No ClientMsgId — this is what makes it "unsolicited".
	})

	select {
	case msg := <-c.Events():
		if msg.GetPayloadType() != uint32(openapi.ProtoOAPayloadType_PROTO_OA_SPOT_EVENT) {
			t.Fatalf("Events() delivered payloadType %d, want PROTO_OA_SPOT_EVENT", msg.GetPayloadType())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("spot event did not arrive on Events() within 5s")
	}
}

// TestHeartbeatEventsAreNotForwarded proves a real ProtoHeartbeatEvent never
// reaches Events() or a pending Request() — it's liveness-only.
func TestHeartbeatEventsAreNotForwarded(t *testing.T) {
	c, server := newTestClient(t)

	heartbeatPayload, err := proto.Marshal(&openapi.ProtoHeartbeatEvent{})
	if err != nil {
		t.Fatalf("marshal heartbeat: %v", err)
	}
	server.writeFrame(t, &openapi.ProtoMessage{
		PayloadType: proto.Uint32(uint32(openapi.ProtoPayloadType_HEARTBEAT_EVENT)),
		Payload:     heartbeatPayload,
	})

	// Send a real, distinguishable event right after — if the heartbeat had
	// been (wrongly) forwarded, it would arrive first.
	spotPayload, err := proto.Marshal(&openapi.ProtoOASpotEvent{CtidTraderAccountId: proto.Int64(42), SymbolId: proto.Int64(1)})
	if err != nil {
		t.Fatalf("marshal spot event: %v", err)
	}
	server.writeFrame(t, &openapi.ProtoMessage{
		PayloadType: proto.Uint32(uint32(openapi.ProtoOAPayloadType_PROTO_OA_SPOT_EVENT)),
		Payload:     spotPayload,
	})

	select {
	case msg := <-c.Events():
		if msg.GetPayloadType() == uint32(openapi.ProtoPayloadType_HEARTBEAT_EVENT) {
			t.Fatal("heartbeat event was forwarded to Events() — it must be swallowed")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("event did not arrive on Events() within 5s")
	}
}
