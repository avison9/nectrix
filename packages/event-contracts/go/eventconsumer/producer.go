package eventconsumer

import (
	"context"

	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// Publish is a thin typed wrapper for a keyed publish of a proto message — the Go equivalent of
// packages/event-contracts/java's EventProducer#send. writer is caller-constructed (broker
// addresses, target topic, etc.), kept symmetric with Config's caller-constructed Reader/DLQWriter.
func Publish[T proto.Message](ctx context.Context, writer *kafka.Writer, key string, message T) error {
	value, err := proto.Marshal(message)
	if err != nil {
		return err
	}
	return writer.WriteMessages(ctx, kafka.Message{Key: []byte(key), Value: value})
}
