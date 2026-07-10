package main

import (
	"context"
	"encoding/json"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/avison9/nectrix/broker-adapters/internal/coreappclient"
	"github.com/avison9/nectrix/broker-adapters/internal/ctrader"
	"github.com/avison9/nectrix/broker-adapters/internal/dedupadapter"
	"github.com/avison9/nectrix/broker-adapters/internal/internalapi"
	"github.com/avison9/nectrix/broker-adapters/internal/reconcile"
	"github.com/avison9/nectrix/broker-adapters/internal/tradesignals"
	redisclient "github.com/avison9/nectrix/redis-client/go"
)

const (
	serviceName       = "broker-adapters"
	addr              = ":8091"
	drainSleep        = 10 * time.Second
	shutdownWait      = 5 * time.Second
	dedupTTL          = 5 * time.Minute
	reconcileInterval = 30 * time.Second
)

type healthResponse struct {
	Service string `json:"service"`
	Status  string `json:"status"`
}

func main() {
	ctx, stopSignals := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stopSignals()

	logger := slog.Default()

	internalServiceToken := os.Getenv("INTERNAL_SERVICE_TOKEN")
	if internalServiceToken == "" {
		log.Fatalf("%s: INTERNAL_SERVICE_TOKEN is required", serviceName)
	}
	ctraderClientID := os.Getenv("CTRADER_CLIENT_ID")
	ctraderClientSecret := os.Getenv("CTRADER_CLIENT_SECRET")
	if ctraderClientID == "" || ctraderClientSecret == "" {
		log.Fatalf("%s: CTRADER_CLIENT_ID/CTRADER_CLIENT_SECRET are required", serviceName)
	}

	redisClient, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		log.Fatalf("%s: redis client config: %v", serviceName, err)
	}
	deduper := redisclient.NewDeduper(redisClient, dedupTTL)

	kafkaWriter := tradesignals.NewWriter(envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092"))
	defer func() { _ = kafkaWriter.Close() }()
	publisher := tradesignals.NewPublisher(kafkaWriter)

	rawAdapter := ctrader.New(ctraderClientID, ctraderClientSecret, ctrader.WithLogger(logger))
	adapter := dedupadapter.New(rawAdapter, deduper)

	coreApp := coreappclient.New(
		envOr("CORE_APP_INTERNAL_BASE_URL", "http://localhost:8080"),
		internalServiceToken,
		nil,
	)

	// adapter satisfies domain.SymbolResolver for free -- dedupadapter.Adapter
	// embeds domain.BrokerAdapter, promoting ResolveSymbol/GetSymbolSpecification.
	loop := reconcile.New(coreApp, coreApp, coreApp, adapter, coreApp, adapter, publisher.OnEvent, reconcileInterval, logger)
	go loop.Run(ctx)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})
	mux.Handle("/internal/", internalapi.NewMux(rawAdapter, internalServiceToken, logger))

	server := &http.Server{Addr: addr, Handler: mux}

	go func() {
		log.Printf("%s listening on %s", serviceName, addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("%s: %v", serviceName, err)
		}
	}()

	<-ctx.Done()

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

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
