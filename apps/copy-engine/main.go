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
	"github.com/avison9/nectrix/copy-engine/internal/pipeline"
	"github.com/avison9/nectrix/copy-engine/internal/stubadapter"
	domain "github.com/avison9/nectrix/go-domain"
	redisclient "github.com/avison9/nectrix/redis-client/go"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
)

const (
	serviceName       = "copy-engine"
	addr              = ":8090"
	drainSleep        = 10 * time.Second
	shutdownWait      = 5 * time.Second
	dedupTTL          = 5 * time.Minute
	copiedTradesTopic = "copied-trades"

	// Defaults match apps/core-app/db's 014-seed-dev-data.sql (context:dev,
	// `make db-seed-dev`) so local manual QA works out of the box: curl the
	// test-inject endpoint after seeding and it resolves against a real,
	// FK-satisfying copy_relationships row. Override via env for any other
	// environment/fixture.
	defaultMasterBrokerAccountID   = "00000000-0000-0000-0000-000000000010"
	defaultFollowerBrokerAccountID = "00000000-0000-0000-0000-000000000011"
)

func main() {
	ctx := context.Background()

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

	kafkaWriter := &kafka.Writer{
		Addr:     kafka.TCP(envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092")),
		Topic:    copiedTradesTopic,
		Balancer: &kafka.Hash{},
	}
	defer func() { _ = kafkaWriter.Close() }()

	adapter := stubadapter.New()

	masterHandle, err := adapter.Connect(ctx, domain.BrokerCredentials{
		BrokerType: adapter.BrokerType(),
		AccountID:  envOr("STUB_MASTER_BROKER_ACCOUNT_ID", defaultMasterBrokerAccountID),
	})
	if err != nil {
		log.Fatalf("%s: connect master handle: %v", serviceName, err)
	}
	followerHandle, err := adapter.Connect(ctx, domain.BrokerCredentials{
		BrokerType: adapter.BrokerType(),
		AccountID:  envOr("STUB_FOLLOWER_BROKER_ACCOUNT_ID", defaultFollowerBrokerAccountID),
	})
	if err != nil {
		log.Fatalf("%s: connect follower handle: %v", serviceName, err)
	}

	pl := pipeline.New(pool, deduper, adapter, followerHandle, kafkaWriter)

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
