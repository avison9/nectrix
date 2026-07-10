//go:build integration

// TICKET-101's real, hands-on verification that a StreamTradeEvents callback
// built on Publisher genuinely reaches Kafka — produce via Publisher.OnEvent,
// consume back on a dedicated topic (own topic per run, same reasoning as
// packages/event-contracts/go/eventconsumer's own integration test: a
// long-lived shared topic would replay every prior run's leftovers), and
// assert the round-tripped message matches what was sent.
package tradesignals_test

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/avison9/nectrix/broker-adapters/internal/tradesignals"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

var kafkaAddr = envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092")

func createDedicatedTopic(t *testing.T) string {
	t.Helper()
	topic := "test-trade-signals-" + uuid.NewString()
	conn, err := kafka.Dial("tcp", kafkaAddr)
	if err != nil {
		t.Fatalf("dial kafka: %v", err)
	}
	defer conn.Close()

	if err := conn.CreateTopics(kafka.TopicConfig{Topic: topic, NumPartitions: 3, ReplicationFactor: 1}); err != nil {
		t.Fatalf("create topic: %v", err)
	}

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		leaderConn, err := kafka.DialLeader(context.Background(), "tcp", kafkaAddr, topic, 0)
		if err == nil {
			leaderConn.Close()
			break
		}
		time.Sleep(200 * time.Millisecond)
	}

	t.Cleanup(func() {
		conn, err := kafka.Dial("tcp", kafkaAddr)
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.DeleteTopics(topic)
	})

	return topic
}

func TestPublisher_OnEvent_RealProduceConsumeRoundTrip(t *testing.T) {
	topic := createDedicatedTopic(t)

	writer := &kafka.Writer{Addr: kafka.TCP(kafkaAddr), Topic: topic, Balancer: &kafka.Hash{}}
	defer func() { _ = writer.Close() }()
	publisher := tradesignals.NewPublisher(writer)

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:     []string{kafkaAddr},
		Topic:       topic,
		GroupID:     "test-trade-signals-" + uuid.NewString(),
		StartOffset: kafka.FirstOffset,
	})
	defer func() { _ = reader.Close() }()

	fillPrice := 1.0851
	event := domain.NormalizedTradeEvent{
		EventID:               uuid.NewString(),
		MasterBrokerAccountID: "master-42",
		EventType:             domain.TradeEventPositionOpened,
		Position: domain.NormalizedPosition{
			BrokerPositionID: "777",
			Symbol:           domain.NormalizedSymbol{CanonicalCode: "EURUSD", AssetClass: domain.AssetClassFX},
			Direction:        domain.TradeDirectionBuy,
			VolumeLots:       1.0,
			OpenPrice:        1.0851,
			OpenedAt:         "2026-07-06T00:00:00Z",
		},
		FillPrice:         &fillPrice,
		ServerTimestamp:   "2026-07-06T00:00:00Z",
		ReceivedAtGateway: "2026-07-06T00:00:01Z",
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := publisher.OnEvent(ctx, event); err != nil {
		t.Fatalf("OnEvent() error = %v", err)
	}

	msg, err := reader.ReadMessage(ctx)
	if err != nil {
		t.Fatalf("ReadMessage() error = %v", err)
	}
	if string(msg.Key) != event.MasterBrokerAccountID {
		t.Fatalf("message key = %q, want %q (master_broker_account_id partition key)", msg.Key, event.MasterBrokerAccountID)
	}

	got := &eventsv1.NormalizedTradeEvent{}
	if err := proto.Unmarshal(msg.Value, got); err != nil {
		t.Fatalf("unmarshal consumed message: %v", err)
	}
	if got.EventId != event.EventID {
		t.Fatalf("consumed EventId = %q, want %q", got.EventId, event.EventID)
	}
	if got.EventType != eventsv1.TradeEventType_TRADE_EVENT_TYPE_POSITION_OPENED {
		t.Fatalf("consumed EventType = %v, want TRADE_EVENT_TYPE_POSITION_OPENED", got.EventType)
	}
	if got.Position.GetBrokerPositionId() != "777" {
		t.Fatalf("consumed Position.BrokerPositionId = %q, want 777", got.Position.GetBrokerPositionId())
	}
	if got.Position.GetSymbol().GetCanonicalCode() != "EURUSD" {
		t.Fatalf("consumed Position.Symbol.CanonicalCode = %q, want EURUSD", got.Position.GetSymbol().GetCanonicalCode())
	}
}
