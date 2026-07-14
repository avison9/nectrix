package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/avison9/nectrix/copy-engine/internal/httpapi"
	"github.com/avison9/nectrix/copy-engine/internal/moneymgmt"
	"github.com/avison9/nectrix/copy-engine/internal/observability"
	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	"github.com/avison9/nectrix/copy-engine/internal/remoteadapter"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	"github.com/avison9/nectrix/copy-engine/internal/tradesignals"
	"github.com/avison9/nectrix/event-contracts/go/eventconsumer"
	eventsv1 "github.com/avison9/nectrix/event-contracts/go/gen/nectrix/events/v1"
	domain "github.com/avison9/nectrix/go-domain"
	redisclient "github.com/avison9/nectrix/redis-client/go"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
)

const (
	serviceName               = "copy-engine"
	addr                      = ":8090"
	drainSleep                = 10 * time.Second
	shutdownWait              = 5 * time.Second
	dedupTTL                  = 5 * time.Minute
	copiedTradesTopic         = "copied-trades"
	tradeSignalsConsumerGroup = "copy-engine"

	// Default matches apps/core-app/db's 014-seed-dev-data.sql (context:dev,
	// `make db-seed-dev`) so local manual QA works out of the box: curl the
	// test-inject endpoint after seeding and it resolves against a real,
	// FK-satisfying copy_relationships row. Override via env for any other
	// environment/fixture.
	defaultMasterBrokerAccountID = "00000000-0000-0000-0000-000000000010"
)

func main() {
	ctx := context.Background()

	// TICKET-010 — otlpEndpoint being unreachable never blocks startup (the
	// SDK just logs export failures); docker-compose.yml points this at
	// Tempo, matching apps/core-app's OTEL_EXPORTER_OTLP_ENDPOINT default.
	otlpEndpoint := envOr("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318")
	shutdownTracing, err := observability.Init(ctx, envOr("OTEL_SERVICE_NAME", serviceName), otlpEndpoint)
	if err != nil {
		log.Fatalf("%s: observability init: %v", serviceName, err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = shutdownTracing(shutdownCtx)
	}()

	pool, err := pgxpool.New(ctx, postgresDSN())
	if err != nil {
		log.Fatalf("%s: postgres pool: %v", serviceName, err)
	}
	defer pool.Close()

	redisClient, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		log.Fatalf("%s: redis client config: %v", serviceName, err)
	}
	deduper := redisclient.NewDeduper(redisClient, dedupTTL)

	kafkaAddr := envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092")
	kafkaWriter := &kafka.Writer{
		Addr:     kafka.TCP(kafkaAddr),
		Topic:    copiedTradesTopic,
		Balancer: &kafka.Hash{},
	}
	defer func() { _ = kafkaWriter.Close() }()

	internalServiceToken := os.Getenv("INTERNAL_SERVICE_TOKEN")
	if internalServiceToken == "" {
		log.Fatalf("%s: INTERNAL_SERVICE_TOKEN is required", serviceName)
	}

	// TICKET-106: real cross-service dispatch -- broker-adapters (cTrader)
	// and mt5-bridge-gateway (MT5/MT4) are separate deployed services with
	// no shared code, so the follower's actual PlaceOrder call happens over
	// HTTP via each service's own new internal routes.
	router := remoteadapter.NewRouter(map[domain.BrokerType]remoteadapter.RemoteAdapter{
		domain.BrokerTypeCTrader: remoteadapter.NewCTraderHTTPClient(
			envOr("BROKER_ADAPTERS_INTERNAL_BASE_URL", "http://localhost:8091"), internalServiceToken, nil),
		domain.BrokerTypeMT5: remoteadapter.NewMTHTTPClient(
			envOr("MT5_BRIDGE_GATEWAY_INTERNAL_BASE_URL", "http://localhost:8092"), internalServiceToken, "MT5", nil),
		domain.BrokerTypeMT4: remoteadapter.NewMTHTTPClient(
			envOr("MT5_BRIDGE_GATEWAY_INTERNAL_BASE_URL", "http://localhost:8092"), internalServiceToken, "MT4", nil),
	})
	fx := moneymgmt.NewFrankfurterClient(nil, nil)

	pl := pipeline.New(pool, deduper, router, fx, kafkaWriter)

	// --- Existing stub-adapter path, unchanged in spirit:
	// /test/inject-trade-event and the in-process StreamTradeEvents
	// callback every existing integration test relies on for EVENT
	// INGESTION. TICKET-106: dispatch.go's own PlaceOrder/GetAccountSnapshot
	// calls now go exclusively through router (real HTTP, or a test's own
	// remoteadapter.LocalAdapter) -- this stub adapter's role shrinks to
	// simulating a master's trade-event stream only, so only a master
	// handle is needed here now (a follower handle was needed for the old
	// fixed-adapter/fixed-handle Pipeline constructor, which no longer
	// exists).
	adapter := stubadapter.New()

	masterHandle, err := adapter.Connect(ctx, domain.BrokerCredentials{
		BrokerType: adapter.BrokerType(),
		AccountID:  envOr("STUB_MASTER_BROKER_ACCOUNT_ID", defaultMasterBrokerAccountID),
	})
	if err != nil {
		log.Fatalf("%s: connect master handle: %v", serviceName, err)
	}

	sub, err := adapter.StreamTradeEvents(ctx, masterHandle, pl.HandleEvent)
	if err != nil {
		log.Fatalf("%s: stream trade events: %v", serviceName, err)
	}
	defer func() { _ = sub.Close() }()

	mux := httpapi.NewMux(serviceName, adapter, masterHandle)
	server := &http.Server{Addr: addr, Handler: mux}

	go func() {
		log.Printf("%s listening on %s", serviceName, addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("%s: %v", serviceName, err)
		}
	}()

	// --- NEW: real trade-signals Kafka consumer, published by
	// broker-adapters/mt5-bridge-gateway, driving the exact same
	// pl.HandleEvent the stub path above does. ---
	tradeSignalsReader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        []string{kafkaAddr},
		Topic:          tradesignals.Topic,
		GroupID:        tradeSignalsConsumerGroup,
		StartOffset:    kafka.FirstOffset,
		CommitInterval: 0, // synchronous commits -- required by eventconsumer.New
	})
	tradeSignalsDLQWriter := &kafka.Writer{
		Addr:     kafka.TCP(kafkaAddr),
		Topic:    tradesignals.Topic + ".dlq",
		Balancer: &kafka.Hash{},
	}
	tradeSignalsConsumer, err := eventconsumer.New(eventconsumer.Config[*eventsv1.NormalizedTradeEvent]{
		Reader:     tradeSignalsReader,
		DLQWriter:  tradeSignalsDLQWriter,
		NewMessage: func() *eventsv1.NormalizedTradeEvent { return &eventsv1.NormalizedTradeEvent{} },
		KeyFunc:    func(e *eventsv1.NormalizedTradeEvent) string { return e.GetEventId() },
		Handler: func(ctx context.Context, e *eventsv1.NormalizedTradeEvent) error {
			return pl.HandleEvent(ctx, tradesignals.FromProto(e))
		},
		Deduper:     deduper,
		RetryPolicy: eventconsumer.DefaultRetryPolicy(),
	})
	if err != nil {
		log.Fatalf("%s: build trade-signals consumer: %v", serviceName, err)
	}
	go func() {
		if err := tradeSignalsConsumer.Run(ctx); err != nil {
			log.Printf("%s: trade-signals consumer stopped: %v", serviceName, err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	<-stop

	// Connection-draining stub (TICKET-002 AC5): on a real rollout this
	// would finish in-flight signal processing and hand off shard ownership
	// before the process exits. terminationGracePeriodSeconds in the
	// Deployment manifest must exceed drainSleep + shutdownWait.
	log.Printf("%s draining: finishing in-flight signal processing, handing off shard ownership (stub)", serviceName)
	time.Sleep(drainSleep)

	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownWait)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("%s: shutdown error: %v", serviceName, err)
	}
	log.Printf("%s: drained, exiting", serviceName)
}

// postgresDSN mirrors apps/core-app's Spring datasource convention exactly
// (POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB, fixed nectrix_app username,
// POSTGRES_APP_PASSWORD with no default) — this is the first Go service to
// talk to Postgres, so there's no prior Go-side precedent to follow, only
// the Java one.
func postgresDSN() string {
	host := envOr("POSTGRES_HOST", "localhost")
	port := envOr("POSTGRES_PORT", "5432")
	db := envOr("POSTGRES_DB", "nectrix")
	password := os.Getenv("POSTGRES_APP_PASSWORD")
	return fmt.Sprintf("host=%s port=%s dbname=%s user=nectrix_app password=%s sslmode=disable", host, port, db, password)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
