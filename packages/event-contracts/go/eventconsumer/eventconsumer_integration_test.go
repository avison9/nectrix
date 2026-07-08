//go:build integration

// TICKET-007 real, hands-on verification against a live Kafka broker (docker-compose.yml) and
// Redis (for the dedup check) — AC1 (producer/consumer roundtrip with correct partition-key
// ordering, demonstrated across a consumer restart) and AC2 (a handler that always errors lands
// its message on "<topic>.dlq" after the configured retry count, not an infinite loop or a silent
// drop).
//
// Each test creates its OWN dedicated topic (deleted afterward) rather than reusing one of the
// real catalog topics — a brand-new consumer group with StartOffset=FirstOffset reads the ENTIRE
// topic history, not just what this test run produces, so sharing a long-lived topic across
// repeated local runs would replay every prior run's leftover messages and inflate the assertions
// (this exact bug was caught by hand in the Java sibling test — see
// packages/event-contracts/java's IdempotentConsumerIntegrationTest for the full story).
package eventconsumer_test

import (
	"context"
	"errors"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/avison9/nectrix/event-contracts/go/eventconsumer"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	redisclient "github.com/avison9/nectrix/redis-client/go"
	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

var (
	kafkaAddr = envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092")
	redisAddr = envOr("REDIS_HOST", "localhost") + ":" + envOr("REDIS_PORT", "6379")
)

func createDedicatedTopic(t *testing.T, partitions int) string {
	t.Helper()
	topic := "test-idempotent-consumer-" + uuid.NewString()
	conn, err := kafka.Dial("tcp", kafkaAddr)
	if err != nil {
		t.Fatalf("dial kafka: %v", err)
	}
	defer conn.Close()

	if err := conn.CreateTopics(
		kafka.TopicConfig{Topic: topic, NumPartitions: partitions, ReplicationFactor: 1},
		kafka.TopicConfig{Topic: topic + ".dlq", NumPartitions: 1, ReplicationFactor: 1},
	); err != nil {
		t.Fatalf("create topics: %v", err)
	}

	// CreateTopics returning success doesn't guarantee the new topic's metadata
	// has propagated to every broker connection yet — an immediate produce can
	// still see "Unknown Topic Or Partition". Wait until a fresh leader
	// connection for the new topic actually succeeds before proceeding.
	waitForTopic(t, topic)
	waitForTopic(t, topic+".dlq")

	t.Cleanup(func() {
		conn, err := kafka.Dial("tcp", kafkaAddr)
		if err != nil {
			return // best-effort cleanup only
		}
		defer conn.Close()
		_ = conn.DeleteTopics(topic, topic+".dlq")
	})

	return topic
}

func waitForTopic(t *testing.T, topic string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := kafka.DialLeader(context.Background(), "tcp", kafkaAddr, topic, 0)
		if err == nil {
			conn.Close()
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("topic %q did not become available within 10s", topic)
}

func newWriter(topic string) *kafka.Writer {
	return &kafka.Writer{Addr: kafka.TCP(kafkaAddr), Topic: topic, Balancer: &kafka.Hash{}}
}

func newReader(topic, groupID string) *kafka.Reader {
	return kafka.NewReader(kafka.ReaderConfig{
		Brokers:        []string{kafkaAddr},
		Topic:          topic,
		GroupID:        groupID,
		StartOffset:    kafka.FirstOffset,
		CommitInterval: 0, // synchronous commits — required by eventconsumer.New
	})
}

func brokerConnectionEvent(brokerAccountID string, sequence int) *eventsv1.BrokerConnectionEvent {
	return &eventsv1.BrokerConnectionEvent{
		Envelope: &eventsv1.EventEnvelope{
			EventId:       uuid.NewString(),
			OccurredAt:    time.Now().Format(time.RFC3339),
			SchemaVersion: "v1",
		},
		BrokerAccountId: brokerAccountID,
		EventType:       eventsv1.BrokerConnectionEventType_BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED,
		Detail:          proto.String("seq:" + strconv.Itoa(sequence)),
	}
}

func TestAC1_PerKeyOrderingSurvivesConsumerRestart(t *testing.T) {
	ctx := context.Background()
	topic := createDedicatedTopic(t, 3)
	keyA, keyB := "test-key-A", "test-key-B"
	groupID := "test-group-" + uuid.NewString()

	writer := newWriter(topic)
	for _, m := range []struct {
		key string
		seq int
	}{{keyA, 1}, {keyB, 1}, {keyA, 2}, {keyB, 2}, {keyA, 3}} {
		mustPublish(t, ctx, writer, m.key, brokerConnectionEvent(m.key, m.seq))
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	var mu sync.Mutex
	var observedForKeyA []int

	runConsumerUntil(t, ctx, topic, groupID, keyA, &mu, &observedForKeyA, 5)

	writer2 := newWriter(topic)
	mustPublish(t, ctx, writer2, keyA, brokerConnectionEvent(keyA, 4))
	if err := writer2.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	runConsumerUntil(t, ctx, topic, groupID, keyA, &mu, &observedForKeyA, 1)

	mu.Lock()
	defer mu.Unlock()
	want := []int{1, 2, 3, 4}
	if len(observedForKeyA) != len(want) {
		t.Fatalf("observedForKeyA = %v, want %v", observedForKeyA, want)
	}
	for i, v := range want {
		if observedForKeyA[i] != v {
			t.Fatalf("observedForKeyA = %v, want %v", observedForKeyA, want)
		}
	}
}

func runConsumerUntil(
	t *testing.T,
	ctx context.Context,
	topic, groupID, filterKey string,
	mu *sync.Mutex,
	observed *[]int,
	recordsToConsume int,
) {
	t.Helper()

	redisClient := redis.NewClient(&redis.Options{Addr: redisAddr})
	defer redisClient.Close()
	deduper := redisclient.NewDeduper(redisClient, 5*time.Minute)

	var consumedCount atomic.Int32
	reader := newReader(topic, groupID)
	dlqWriter := newWriter(topic + ".dlq")
	defer dlqWriter.Close()

	consumer, err := eventconsumer.New(eventconsumer.Config[*eventsv1.BrokerConnectionEvent]{
		Reader:      reader,
		DLQWriter:   dlqWriter,
		NewMessage:  func() *eventsv1.BrokerConnectionEvent { return &eventsv1.BrokerConnectionEvent{} },
		KeyFunc:     func(e *eventsv1.BrokerConnectionEvent) string { return e.GetEnvelope().GetEventId() },
		Deduper:     deduper,
		RetryPolicy: eventconsumer.DefaultRetryPolicy(),
		Handler: func(ctx context.Context, e *eventsv1.BrokerConnectionEvent) error {
			if e.GetBrokerAccountId() == filterKey {
				seq, err := strconv.Atoi(e.GetDetail()[len("seq:"):])
				if err != nil {
					return err
				}
				mu.Lock()
				*observed = append(*observed, seq)
				mu.Unlock()
			}
			consumedCount.Add(1)
			return nil
		},
	})
	if err != nil {
		t.Fatalf("new consumer: %v", err)
	}

	runCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	go func() { done <- consumer.Run(runCtx) }()

	deadline := time.Now().Add(20 * time.Second)
	for consumedCount.Load() < int32(recordsToConsume) && time.Now().Before(deadline) {
		time.Sleep(100 * time.Millisecond)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("consumer did not stop within 5s of cancellation")
	}
	if err := reader.Close(); err != nil {
		t.Logf("reader close: %v", err)
	}
}

func TestAC2_HandlerFailingEveryAttempt_LandsOnDLQAfterConfiguredRetries_NotInfiniteLoop(t *testing.T) {
	ctx := context.Background()
	topic := createDedicatedTopic(t, 3)
	key := "test-dlq-key"
	groupID := "test-dlq-group-" + uuid.NewString()
	maxAttempts := 3

	writer := newWriter(topic)
	mustPublish(t, ctx, writer, key, brokerConnectionEvent(key, 1))
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	redisClient := redis.NewClient(&redis.Options{Addr: redisAddr})
	defer redisClient.Close()
	deduper := redisclient.NewDeduper(redisClient, 5*time.Minute)

	var handlerInvocations atomic.Int32
	reader := newReader(topic, groupID)
	dlqWriter := newWriter(topic + ".dlq")
	defer dlqWriter.Close()

	consumer, err := eventconsumer.New(eventconsumer.Config[*eventsv1.BrokerConnectionEvent]{
		Reader:     reader,
		DLQWriter:  dlqWriter,
		NewMessage: func() *eventsv1.BrokerConnectionEvent { return &eventsv1.BrokerConnectionEvent{} },
		KeyFunc:    func(e *eventsv1.BrokerConnectionEvent) string { return e.GetEnvelope().GetEventId() },
		Deduper:    deduper,
		RetryPolicy: eventconsumer.RetryPolicy{
			MaxAttempts:    maxAttempts,
			InitialBackoff: 10 * time.Millisecond,
			MaxBackoff:     50 * time.Millisecond,
		},
		Handler: func(ctx context.Context, e *eventsv1.BrokerConnectionEvent) error {
			handlerInvocations.Add(1)
			return errors.New("always fails, for AC2's DLQ test")
		},
	})
	if err != nil {
		t.Fatalf("new consumer: %v", err)
	}

	runCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	go func() { done <- consumer.Run(runCtx) }()

	deadline := time.Now().Add(20 * time.Second)
	for handlerInvocations.Load() < int32(maxAttempts) && time.Now().Before(deadline) {
		time.Sleep(100 * time.Millisecond)
	}
	time.Sleep(500 * time.Millisecond) // let the final DLQ-publish + commit complete
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("consumer did not stop within 5s of cancellation")
	}
	if err := reader.Close(); err != nil {
		t.Logf("reader close: %v", err)
	}

	if got := handlerInvocations.Load(); got != int32(maxAttempts) {
		t.Fatalf("handlerInvocations = %d, want %d", got, maxAttempts)
	}

	record := consumeSingleDLQRecord(t, topic+".dlq", key)
	if record == nil {
		t.Fatal("no DLQ record found — message was silently dropped, or never routed to DLQ")
	}
	if got := headerValue(record, "x-dlq-original-topic"); got != topic {
		t.Errorf("x-dlq-original-topic = %q, want %q", got, topic)
	}
	if got := headerValue(record, "x-dlq-attempt-count"); got != strconv.Itoa(maxAttempts) {
		t.Errorf("x-dlq-attempt-count = %q, want %q", got, strconv.Itoa(maxAttempts))
	}
	if got := headerValue(record, "x-dlq-error-message"); got == "" {
		t.Errorf("x-dlq-error-message is empty, want a message containing the failure reason")
	}
}

func consumeSingleDLQRecord(t *testing.T, dlqTopic, expectedKey string) *kafka.Message {
	t.Helper()
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:     []string{kafkaAddr},
		Topic:       dlqTopic,
		GroupID:     "test-dlq-reader-" + uuid.NewString(),
		StartOffset: kafka.FirstOffset,
	})
	defer reader.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			return nil
		}
		if string(msg.Key) == expectedKey {
			return &msg
		}
	}
}

func headerValue(msg *kafka.Message, key string) string {
	for _, h := range msg.Headers {
		if h.Key == key {
			return string(h.Value)
		}
	}
	return ""
}

func mustPublish(t *testing.T, ctx context.Context, writer *kafka.Writer, key string, event *eventsv1.BrokerConnectionEvent) {
	t.Helper()
	// A just-created topic's metadata can take a moment to propagate to the
	// specific partition a given key hashes to (waitForTopic only confirms
	// partition 0 is ready, not necessarily the partition this key's Hash
	// balancer picks) — retry on "Unknown Topic Or Partition" rather than
	// failing immediately.
	deadline := time.Now().Add(10 * time.Second)
	for {
		err := eventconsumer.Publish(ctx, writer, key, event)
		if err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("publish: %v", err)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

