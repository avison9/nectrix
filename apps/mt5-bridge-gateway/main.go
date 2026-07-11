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

	domain "github.com/avison9/nectrix/go-domain"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/coreappclient"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/dedupadapter"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/eabridge"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/mtadapter"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/pairing"
	"github.com/avison9/nectrix/mt5-bridge-gateway/internal/tradesignals"
	redisclient "github.com/avison9/nectrix/redis-client/go"
)

const (
	serviceName     = "mt5-bridge-gateway"
	addr            = ":8092"
	eaWebSocketPath = "/ea/ws"
	drainSleep      = 10 * time.Second
	shutdownWait    = 5 * time.Second
	dedupTTL        = 5 * time.Minute
	pairingInterval = 30 * time.Second
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

	redisClient, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		log.Fatalf("%s: redis client config: %v", serviceName, err)
	}
	deduper := redisclient.NewDeduper(redisClient, dedupTTL)

	kafkaWriter := tradesignals.NewWriter(envOr("KAFKA_HOST", "localhost") + ":" + envOr("KAFKA_PORT", "9092"))
	defer func() { _ = kafkaWriter.Close() }()
	publisher := tradesignals.NewPublisher(kafkaWriter)

	coreApp := coreappclient.New(
		envOr("CORE_APP_INTERNAL_BASE_URL", "http://localhost:8080"),
		internalServiceToken,
		nil,
	)

	// Unlike apps/broker-adapters (which dials OUT to cTrader and so drives
	// its own connection lifecycle via reconcile.Loop calling Connect), this
	// service is a WebSocket SERVER that real MT5/MT4 EAs dial INTO — the
	// eabridge.Server IS the connection lifecycle: publisher.OnEvent is
	// wired as every session's Kafka-publish subscriber the moment an EA
	// pairs, and statusHandler reports CONNECTED/DISCONNECTED to Core App
	// the moment a session is established/lost, reusing the exact
	// StatusReporter contract + endpoint TICKET-101 built.
	//
	// statusHandler is built before eaServer (which needs it) and before
	// the adapters (which eaServer needs) — TICKET-103's
	// SetSymbolResolvers call below needs both eaServer AND the adapters to
	// already exist, which would otherwise be a circular construction
	// dependency; building statusHandler first and wiring its resolvers in
	// after the adapters exist breaks that cycle.
	statusHandler := pairing.NewStatusHandler(coreApp, coreApp, logger)
	eaServer := eabridge.NewServer(publisher.OnEvent, statusHandler, logger)

	pairingLoop := pairing.New(coreApp, coreApp, eaServer, pairingInterval, logger)
	go pairingLoop.Run(ctx)

	// Dedup-wrapped MT5/MT4 domain.BrokerAdapter implementations — real,
	// tested, and BrokerType()-conformant, ready for whichever caller needs
	// to place/modify/close orders against a live EA session (Copy Engine's
	// own cross-service routing to broker-adapters/mt5-bridge-gateway is a
	// separate, not-yet-built concern — see internal/mtadapter's package
	// doc; internal/ctrader's own PlaceOrder is equally not yet reachable
	// from outside apps/broker-adapters today). Both also satisfy
	// domain.SymbolResolver for free (dedupadapter.Adapter embeds
	// domain.BrokerAdapter, promoting ResolveSymbol/GetSymbolSpecification).
	mt5Adapter := dedupadapter.New(mtadapter.NewMT5(eaServer), deduper)
	mt4Adapter := dedupadapter.New(mtadapter.NewMT4(eaServer), deduper)
	logger.Info("mt5-bridge-gateway: adapters ready", "platforms", []string{string(mt5Adapter.BrokerType()), string(mt4Adapter.BrokerType())})

	// Must happen before eaServer.Handler() is registered on the mux below
	// — i.e. before any real EA traffic can arrive and trigger
	// OnSessionEstablished.
	statusHandler.SetSymbolResolvers(map[domain.BrokerType]domain.SymbolResolver{
		domain.BrokerTypeMT5: mt5Adapter,
		domain.BrokerTypeMT4: mt4Adapter,
	})

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})
	mux.Handle(eaWebSocketPath, eaServer.Handler())

	server := &http.Server{Addr: addr, Handler: mux}

	go func() {
		log.Printf("%s listening on %s (EA WebSocket endpoint: %s)", serviceName, addr, eaWebSocketPath)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("%s: %v", serviceName, err)
		}
	}()

	<-ctx.Done()

	// Connection-draining stub (TICKET-002 AC5), mirroring
	// apps/broker-adapters' identical pattern: on a real rollout this would
	// finish in-flight signal processing before the process exits.
	// terminationGracePeriodSeconds in the Deployment manifest must exceed
	// drainSleep + shutdownWait. Live EA sessions themselves reconnect on
	// their own terminal-side retry loop once a new pod is ready — this
	// gateway doesn't need to hand off session ownership the way a
	// sharded consumer group would.
	log.Printf("%s draining: finishing in-flight signal processing (stub)", serviceName)
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
