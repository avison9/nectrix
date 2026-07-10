package ctrader

import (
	"context"
	"encoding/binary"
	"io"
	"log/slog"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/avison9/nectrix/broker-adapters/internal/ctraderapi"
	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	domain "github.com/avison9/nectrix/go-domain"
	"google.golang.org/protobuf/proto"
)

// fakeCTraderServer is a minimal, real-protocol-framed stand-in for
// cTrader's Open API server — same 4-byte-BE length-prefixed ProtoMessage
// wire format ctraderapi.Client speaks, driven from a handler function per
// test so each test only has to describe the specific exchange it cares
// about. This proves CTraderAdapter's real orchestration logic (which
// requests it sends, in what order, how it maps real responses) — not just
// that ctraderapi's framing works in isolation (already covered by
// ctraderapi's own tests).
type fakeCTraderServer struct {
	t    *testing.T
	conn net.Conn

	mu       sync.Mutex
	handlers map[uint32]func(clientMsgID string, payload []byte) *openapi.ProtoMessage
}

func newFakeCTraderServer(t *testing.T) (*ctraderapi.Client, *fakeCTraderServer) {
	t.Helper()
	clientConn, serverConn := net.Pipe()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	client := ctraderapi.NewForTesting(clientConn, logger)
	t.Cleanup(func() { _ = client.Close() })

	server := &fakeCTraderServer{t: t, conn: serverConn, handlers: make(map[uint32]func(string, []byte) *openapi.ProtoMessage)}
	go server.serve()
	t.Cleanup(func() { _ = serverConn.Close() })
	return client, server
}

// on registers a canned response for a given request payloadType. handler
// receives the real inbound payload bytes so a test can assert on what
// CTraderAdapter actually sent, and returns the *response* payload bytes to
// echo back (already-marshaled) plus the response's own payloadType.
func (s *fakeCTraderServer) on(payloadType uint32, handler func(clientMsgID string, payload []byte) *openapi.ProtoMessage) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[payloadType] = handler
}

func (s *fakeCTraderServer) serve() {
	reader := s.conn
	for {
		var lengthPrefix [4]byte
		if _, err := io.ReadFull(reader, lengthPrefix[:]); err != nil {
			return
		}
		length := binary.BigEndian.Uint32(lengthPrefix[:])
		body := make([]byte, length)
		if _, err := io.ReadFull(reader, body); err != nil {
			return
		}
		msg := &openapi.ProtoMessage{}
		if err := proto.Unmarshal(body, msg); err != nil {
			s.t.Errorf("fakeCTraderServer: unmarshal envelope: %v", err)
			return
		}

		s.mu.Lock()
		handler, ok := s.handlers[msg.GetPayloadType()]
		s.mu.Unlock()
		if !ok {
			s.t.Errorf("fakeCTraderServer: no handler registered for payloadType %d", msg.GetPayloadType())
			continue
		}
		resp := handler(msg.GetClientMsgId(), msg.GetPayload())
		if resp == nil {
			continue // handler chose not to respond (used to simulate a dropped/ignored request)
		}
		resp.ClientMsgId = msg.ClientMsgId
		s.writeFrame(resp)
	}
}

// push sends an unsolicited message (no clientMsgId) — a real streamed
// event, e.g. ProtoOAExecutionEvent/ProtoOASpotEvent.
func (s *fakeCTraderServer) push(payloadType uint32, payload proto.Message) {
	data, err := proto.Marshal(payload)
	if err != nil {
		s.t.Fatalf("fakeCTraderServer: marshal pushed payload: %v", err)
	}
	s.writeFrame(&openapi.ProtoMessage{PayloadType: proto.Uint32(payloadType), Payload: data})
}

func (s *fakeCTraderServer) writeFrame(msg *openapi.ProtoMessage) {
	data, err := proto.Marshal(msg)
	if err != nil {
		s.t.Fatalf("fakeCTraderServer: marshal envelope: %v", err)
	}
	var lengthPrefix [4]byte
	binary.BigEndian.PutUint32(lengthPrefix[:], uint32(len(data)))
	if _, err := s.conn.Write(lengthPrefix[:]); err != nil {
		return // connection torn down (test cleanup) — nothing to report
	}
	_, _ = s.conn.Write(data)
}

// respond is a small helper for handlers that just need to echo back a
// fixed response message under a fixed payloadType.
func respond(payloadType uint32, msg proto.Message) func(string, []byte) *openapi.ProtoMessage {
	return func(string, []byte) *openapi.ProtoMessage {
		data, _ := proto.Marshal(msg)
		return &openapi.ProtoMessage{PayloadType: proto.Uint32(payloadType), Payload: data}
	}
}

// eurusdSymbolID/eurusdLotSize are the fake server's fixture data — a real
// standard-FX-shaped symbol (100000 units/lot).
const (
	eurusdSymbolID = int64(1)
	eurusdLotSize  = int64(10000000) // "in cents" — 100000.00 units/lot
)

func newTestAdapter(t *testing.T, dial func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error)) *CTraderAdapter {
	t.Helper()
	return New("test-client-id", "test-client-secret", WithDialFunc(dial), WithLogger(slog.New(slog.NewTextHandler(io.Discard, nil))))
}

// wireStandardHandshake registers the handlers every Connect() call needs
// regardless of what the test itself is exercising: app auth, account auth,
// symbol list (for populateSymbolCache), and an empty reconcile (for
// subscribeOpenPositionSpots — no open positions in these tests unless a
// test overrides it after this call).
func wireStandardHandshake(server *fakeCTraderServer) {
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_REQ), respond(uint32(openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_RES), &openapi.ProtoOAApplicationAuthRes{}))
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_ACCOUNT_AUTH_REQ), respond(uint32(openapi.ProtoOAPayloadType_PROTO_OA_ACCOUNT_AUTH_RES), &openapi.ProtoOAAccountAuthRes{CtidTraderAccountId: proto.Int64(42)}))
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOLS_LIST_REQ), respond(uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOLS_LIST_RES), &openapi.ProtoOASymbolsListRes{
		CtidTraderAccountId: proto.Int64(42),
		Symbol:              []*openapi.ProtoOALightSymbol{{SymbolId: proto.Int64(eurusdSymbolID), SymbolName: proto.String("EURUSD")}},
	}))
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_REQ), respond(uint32(openapi.ProtoOAPayloadType_PROTO_OA_RECONCILE_RES), &openapi.ProtoOAReconcileRes{CtidTraderAccountId: proto.Int64(42)}))
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOL_BY_ID_REQ), respond(uint32(openapi.ProtoOAPayloadType_PROTO_OA_SYMBOL_BY_ID_RES), &openapi.ProtoOASymbolByIdRes{
		CtidTraderAccountId: proto.Int64(42),
		Symbol:              []*openapi.ProtoOASymbol{{SymbolId: proto.Int64(eurusdSymbolID), Digits: proto.Int32(5), PipPosition: proto.Int32(4), LotSize: proto.Int64(eurusdLotSize), MinVolume: proto.Int64(1000), MaxVolume: proto.Int64(1000000000), StepVolume: proto.Int64(1000)}},
	}))
}

func connectTestAccount(t *testing.T, a *CTraderAdapter) domain.ConnectionHandle {
	t.Helper()
	handle, err := a.Connect(context.Background(), domain.BrokerCredentials{
		BrokerType:          domain.BrokerTypeCTrader,
		AccountID:           "test-broker-account-id",
		AccessToken:         "test-access-token",
		CtidTraderAccountID: 42,
	})
	if err != nil {
		t.Fatalf("Connect() failed: %v", err)
	}
	return handle
}

func TestConnect_RealHandshakeSequence(t *testing.T) {
	client, server := newFakeCTraderServer(t)
	wireStandardHandshake(server)

	a := newTestAdapter(t, func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error) {
		return client, nil
	})

	handle := connectTestAccount(t, a)
	if handle.BrokerType != domain.BrokerTypeCTrader {
		t.Fatalf("handle.BrokerType = %v, want CTRADER", handle.BrokerType)
	}

	health, err := a.HealthCheck(context.Background(), handle)
	if err != nil {
		t.Fatalf("HealthCheck() failed: %v", err)
	}
	if !health.Connected {
		t.Fatal("HealthCheck().Connected = false after a successful Connect()")
	}
}

func TestGetAccountSnapshot_RealComputation(t *testing.T) {
	client, server := newFakeCTraderServer(t)
	wireStandardHandshake(server)
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_TRADER_REQ), respond(uint32(openapi.ProtoOAPayloadType_PROTO_OA_TRADER_RES), &openapi.ProtoOATraderRes{
		CtidTraderAccountId: proto.Int64(42),
		Trader: &openapi.ProtoOATrader{
			CtidTraderAccountId: proto.Int64(42),
			Balance:             proto.Int64(10000_00), // $10,000.00
			MoneyDigits:         proto.Uint32(2),
			DepositAssetId:      proto.Int64(1),
		},
	}))

	a := newTestAdapter(t, func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error) {
		return client, nil
	})
	handle := connectTestAccount(t, a)

	snapshot, err := a.GetAccountSnapshot(context.Background(), handle)
	if err != nil {
		t.Fatalf("GetAccountSnapshot() failed: %v", err)
	}
	if snapshot.Balance != 10000.0 {
		t.Fatalf("Balance = %v, want 10000.0 (real moneyDigits scaling: 1000000/10^2)", snapshot.Balance)
	}
	if snapshot.Equity != snapshot.Balance {
		t.Fatalf("Equity = %v, want == Balance (no open positions, so no unrealized P&L)", snapshot.Equity)
	}
}

func TestStreamTradeEvents_RealPositionOpenedEvent(t *testing.T) {
	client, server := newFakeCTraderServer(t)
	wireStandardHandshake(server)

	a := newTestAdapter(t, func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error) {
		return client, nil
	})
	handle := connectTestAccount(t, a)

	received := make(chan domain.NormalizedTradeEvent, 1)
	sub, err := a.StreamTradeEvents(context.Background(), handle, func(ctx context.Context, event domain.NormalizedTradeEvent) error {
		received <- event
		return nil
	})
	if err != nil {
		t.Fatalf("StreamTradeEvents() failed: %v", err)
	}
	defer func() { _ = sub.Close() }()

	// A real cTrader ORDER_FILLED execution event, opening a new 1.00-lot
	// BUY position on EURUSD — no ClosePositionDetail (this is an opening
	// fill, not a closing one), matching mapExecutionEvent's real
	// distinguishing logic.
	server.push(uint32(openapi.ProtoOAPayloadType_PROTO_OA_EXECUTION_EVENT), &openapi.ProtoOAExecutionEvent{
		CtidTraderAccountId: proto.Int64(42),
		ExecutionType:       openapi.ProtoOAExecutionType_ORDER_FILLED.Enum(),
		Position: &openapi.ProtoOAPosition{
			PositionId:     proto.Int64(555),
			PositionStatus: openapi.ProtoOAPositionStatus_POSITION_STATUS_OPEN.Enum(),
			Price:          proto.Float64(1.0850),
			TradeData: &openapi.ProtoOATradeData{
				SymbolId:  proto.Int64(eurusdSymbolID),
				Volume:    proto.Int64(eurusdLotSize), // exactly 1.00 lot
				TradeSide: openapi.ProtoOATradeSide_BUY.Enum(),
			},
			Swap:       proto.Int64(0),
			Commission: proto.Int64(0),
		},
		Deal: &openapi.ProtoOADeal{
			DealId:             proto.Int64(999),
			OrderId:            proto.Int64(888),
			PositionId:         proto.Int64(555),
			Volume:             proto.Int64(eurusdLotSize),
			FilledVolume:       proto.Int64(eurusdLotSize),
			SymbolId:           proto.Int64(eurusdSymbolID),
			CreateTimestamp:    proto.Int64(time.Now().UnixMilli()),
			ExecutionTimestamp: proto.Int64(time.Now().UnixMilli()),
			ExecutionPrice:     proto.Float64(1.0850),
			TradeSide:          openapi.ProtoOATradeSide_BUY.Enum(),
			DealStatus:         openapi.ProtoOADealStatus_FILLED.Enum(),
		},
	})

	select {
	case event := <-received:
		if event.EventType != domain.TradeEventPositionOpened {
			t.Fatalf("EventType = %v, want POSITION_OPENED", event.EventType)
		}
		if event.Position.VolumeLots != 1.0 {
			t.Fatalf("Position.VolumeLots = %v, want 1.0 (real lot-size scaling)", event.Position.VolumeLots)
		}
		if event.Position.Symbol.CanonicalCode != "EURUSD" {
			t.Fatalf("Position.Symbol.CanonicalCode = %q, want EURUSD (real symbol cache resolution)", event.Position.Symbol.CanonicalCode)
		}
		if event.Position.Direction != domain.TradeDirectionBuy {
			t.Fatalf("Position.Direction = %v, want BUY", event.Position.Direction)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no event received on StreamTradeEvents' callback within 5s")
	}
}

func TestPlaceOrder_RealOrderSubmissionAndMapping(t *testing.T) {
	client, server := newFakeCTraderServer(t)
	wireStandardHandshake(server)

	var capturedOrder openapi.ProtoOANewOrderReq
	server.on(uint32(openapi.ProtoOAPayloadType_PROTO_OA_NEW_ORDER_REQ), func(clientMsgID string, payload []byte) *openapi.ProtoMessage {
		if err := proto.Unmarshal(payload, &capturedOrder); err != nil {
			t.Fatalf("unmarshal captured NewOrderReq: %v", err)
		}
		ackPayload, err := proto.Marshal(&openapi.ProtoOAExecutionEvent{
			CtidTraderAccountId: proto.Int64(42),
			ExecutionType:       openapi.ProtoOAExecutionType_ORDER_FILLED.Enum(),
			Position: &openapi.ProtoOAPosition{
				PositionId:     proto.Int64(777),
				PositionStatus: openapi.ProtoOAPositionStatus_POSITION_STATUS_OPEN.Enum(),
				TradeData: &openapi.ProtoOATradeData{
					SymbolId:  proto.Int64(eurusdSymbolID),
					Volume:    proto.Int64(eurusdLotSize / 2),
					TradeSide: openapi.ProtoOATradeSide_BUY.Enum(),
				},
				Swap: proto.Int64(0),
			},
			Deal: &openapi.ProtoOADeal{
				DealId:             proto.Int64(1000),
				OrderId:            proto.Int64(2000),
				PositionId:         proto.Int64(777),
				Volume:             proto.Int64(eurusdLotSize / 2),
				FilledVolume:       proto.Int64(eurusdLotSize / 2),
				SymbolId:           proto.Int64(eurusdSymbolID),
				CreateTimestamp:    proto.Int64(time.Now().UnixMilli()),
				ExecutionTimestamp: proto.Int64(time.Now().UnixMilli()),
				ExecutionPrice:     proto.Float64(1.0851),
				TradeSide:          openapi.ProtoOATradeSide_BUY.Enum(),
				DealStatus:         openapi.ProtoOADealStatus_FILLED.Enum(),
			},
		})
		if err != nil {
			t.Fatalf("marshal execution event ack: %v", err)
		}
		return &openapi.ProtoMessage{PayloadType: proto.Uint32(uint32(openapi.ProtoOAPayloadType_PROTO_OA_EXECUTION_EVENT)), Payload: ackPayload}
	})

	a := newTestAdapter(t, func(ctx context.Context, host string, logger *slog.Logger) (*ctraderapi.Client, error) {
		return client, nil
	})
	handle := connectTestAccount(t, a)

	result, err := a.PlaceOrder(context.Background(), handle, domain.NormalizedOrderRequest{
		IdempotencyKey: "idem-key-1",
		Symbol:         domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
		Direction:      domain.TradeDirectionBuy,
		VolumeLots:     0.5,
		ClientOrderTag: "idem-key-1",
	})
	if err != nil {
		t.Fatalf("PlaceOrder() failed: %v", err)
	}
	if !result.Success {
		t.Fatalf("result.Success = false, RejectReason = %q", result.RejectReason)
	}
	if result.BrokerPositionID != "777" {
		t.Fatalf("BrokerPositionID = %q, want 777", result.BrokerPositionID)
	}
	if result.FilledPrice == nil || *result.FilledPrice != 1.0851 {
		t.Fatalf("FilledPrice = %v, want 1.0851", result.FilledPrice)
	}

	// Real assertion on what was actually sent over the wire — half a lot
	// at eurusdLotSize should be exactly half of eurusdLotSize raw units,
	// and the idempotency key must round-trip as cTrader's ClientOrderId.
	wantVolume := eurusdLotSize / 2
	if capturedOrder.GetVolume() != wantVolume {
		t.Fatalf("sent Volume = %d, want %d (0.5 lots at real lot size)", capturedOrder.GetVolume(), wantVolume)
	}
	if capturedOrder.GetClientOrderId() != "idem-key-1" {
		t.Fatalf("sent ClientOrderId = %q, want the real idempotency key", capturedOrder.GetClientOrderId())
	}
}
