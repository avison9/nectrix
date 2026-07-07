// Package eventconsumer is TICKET-007's reusable idempotent-consumer + DLQ
// helper (docs/15-event-driven-architecture.md §15.4/§15.6), generic over any
// Protobuf event type sharing one Kafka topic. Mirrors
// packages/event-contracts/java's IdempotentConsumer — same semantics in
// both languages, not a bespoke design per language.
package eventconsumer

import (
	"context"
	"fmt"
	"math/rand"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/segmentio/kafka-go"
	"google.golang.org/protobuf/proto"
)

// RetryPolicy is exponential backoff with jitter, deliberately short-lived —
// the retry loop blocks this partition's next fetch, long backoff belongs on
// the DLQ side, not here.
type RetryPolicy struct {
	MaxAttempts    int
	InitialBackoff time.Duration
	MaxBackoff     time.Duration
}

// DefaultRetryPolicy returns {3 attempts, 200ms initial, 2s cap}.
func DefaultRetryPolicy() RetryPolicy {
	return RetryPolicy{MaxAttempts: 3, InitialBackoff: 200 * time.Millisecond, MaxBackoff: 2 * time.Second}
}

// backoffFor returns the backoff duration before attempt number `attempt` (1-indexed).
func (p RetryPolicy) backoffFor(attempt int) time.Duration {
	base := p.InitialBackoff
	for i := 1; i < attempt; i++ {
		base *= 2
		if base > p.MaxBackoff {
			base = p.MaxBackoff
			break
		}
	}
	if base > p.MaxBackoff {
		base = p.MaxBackoff
	}
	return time.Duration(rand.Int63n(int64(base) + 1))
}

// Handler is the business-logic callback invoked for each non-duplicate record. May return an
// error — Consumer retries per its configured RetryPolicy.
type Handler[T proto.Message] func(ctx context.Context, event T) error

// Config configures a Consumer. Reader and DLQWriter are caller-constructed so callers retain full
// control over broker addresses, consumer-group ID, TLS, etc. — this package only enforces the
// manual-commit invariant on Reader.
type Config[T proto.Message] struct {
	Reader      *kafka.Reader // must have CommitInterval == 0 (synchronous CommitMessages) — validated by New
	DLQWriter   *kafka.Writer // targets "<topic>.dlq"; caller sets Topic accordingly
	NewMessage  func() T      // factory — generics can't `new(T)` a proto.Message cleanly
	KeyFunc     func(T) string
	Handler     Handler[T]
	Deduper     domain.Deduper
	RetryPolicy RetryPolicy // zero value is invalid; use DefaultRetryPolicy() if unset
}

// Consumer runs the poll loop described in the package doc comment.
type Consumer[T proto.Message] struct {
	cfg Config[T]
}

// New validates cfg and returns a Consumer. Rejects a Reader configured with a nonzero
// CommitInterval — kafka-go switches to periodic background-batched commits in that mode, which
// would silently violate the "commit only after a known outcome" invariant this package promises.
func New[T proto.Message](cfg Config[T]) (*Consumer[T], error) {
	if cfg.Reader == nil {
		return nil, fmt.Errorf("eventconsumer: Reader is required")
	}
	if cfg.Reader.Config().CommitInterval != 0 {
		return nil, fmt.Errorf(
			"eventconsumer: Reader must be configured with CommitInterval: 0 (synchronous commit) — got %v",
			cfg.Reader.Config().CommitInterval)
	}
	if cfg.DLQWriter == nil {
		return nil, fmt.Errorf("eventconsumer: DLQWriter is required")
	}
	if cfg.NewMessage == nil || cfg.KeyFunc == nil || cfg.Handler == nil || cfg.Deduper == nil {
		return nil, fmt.Errorf("eventconsumer: NewMessage, KeyFunc, Handler, and Deduper are all required")
	}
	if cfg.RetryPolicy.MaxAttempts == 0 {
		cfg.RetryPolicy = DefaultRetryPolicy()
	}
	return &Consumer[T]{cfg: cfg}, nil
}

// Run blocks, fetching and processing records until ctx is cancelled (returns nil) or an
// unrecoverable error occurs (e.g. a DLQ publish failure — propagated uncommitted so restart
// naturally redelivers the record rather than silently losing it).
func (c *Consumer[T]) Run(ctx context.Context) error {
	for {
		msg, err := c.cfg.Reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("eventconsumer: fetch failed: %w", err)
		}
		if err := c.processMessage(ctx, msg); err != nil {
			return err
		}
	}
}

func (c *Consumer[T]) processMessage(ctx context.Context, msg kafka.Message) error {
	event := c.cfg.NewMessage()
	if err := proto.Unmarshal(msg.Value, event); err != nil {
		return c.routeToDLQAndCommit(ctx, msg, "deserialization failed: "+err.Error(), 0)
	}

	key := c.cfg.KeyFunc(event)
	duplicate, err := c.cfg.Deduper.SeenBefore(ctx, key)
	if err != nil {
		return fmt.Errorf("eventconsumer: dedup check failed: %w", err)
	}
	if duplicate {
		return c.commit(ctx, msg)
	}

	var lastErr error
	attempt := 0
	for attempt < c.cfg.RetryPolicy.MaxAttempts {
		attempt++
		if err := c.cfg.Handler(ctx, event); err != nil {
			lastErr = err
			if attempt < c.cfg.RetryPolicy.MaxAttempts {
				select {
				case <-time.After(c.cfg.RetryPolicy.backoffFor(attempt)):
				case <-ctx.Done():
					lastErr = ctx.Err()
					attempt = c.cfg.RetryPolicy.MaxAttempts // stop retrying, fall through to DLQ
				}
			}
			continue
		}
		return c.commit(ctx, msg)
	}
	return c.routeToDLQAndCommit(ctx, msg, describeFailure(lastErr), attempt)
}

func (c *Consumer[T]) routeToDLQAndCommit(ctx context.Context, msg kafka.Message, errMessage string, attemptCount int) error {
	dlqMsg := kafka.Message{
		Key:   msg.Key,
		Value: msg.Value,
		Headers: []kafka.Header{
			{Key: "x-dlq-original-topic", Value: []byte(msg.Topic)},
			{Key: "x-dlq-original-partition", Value: []byte(fmt.Sprintf("%d", msg.Partition))},
			{Key: "x-dlq-original-offset", Value: []byte(fmt.Sprintf("%d", msg.Offset))},
			{Key: "x-dlq-error-message", Value: []byte(errMessage)},
			{Key: "x-dlq-attempt-count", Value: []byte(fmt.Sprintf("%d", attemptCount))},
		},
	}
	// WriteMessages blocks until the broker acks (RequiredAcks default is
	// RequireOne) — if this errors, propagate uncommitted so restart
	// naturally redelivers rather than silently losing the record.
	if err := c.cfg.DLQWriter.WriteMessages(ctx, dlqMsg); err != nil {
		return fmt.Errorf("eventconsumer: failed to publish to DLQ: %w", err)
	}
	return c.commit(ctx, msg)
}

func (c *Consumer[T]) commit(ctx context.Context, msg kafka.Message) error {
	return c.cfg.Reader.CommitMessages(ctx, msg)
}

func describeFailure(err error) string {
	if err == nil {
		return "unknown failure"
	}
	return err.Error()
}
