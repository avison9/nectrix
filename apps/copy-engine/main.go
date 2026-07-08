package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	domain "github.com/avison9/nectrix/go-domain"
	redisclient "github.com/avison9/nectrix/redis-client/go"
)

const (
	serviceName  = "copy-engine"
	addr         = ":8090"
	drainSleep   = 10 * time.Second
	shutdownWait = 5 * time.Second
)

type healthResponse struct {
	Service string `json:"service"`
	Status  string `json:"status"`
}

func main() {
	// Referencing go-domain proves the shared-package wiring resolves via
	// go.work; real pipeline usage begins once TICKET-009 lands.
	var _ domain.NormalizedTradeEvent

	// TICKET-008 — proves the shared Redis client is genuinely reachable from
	// copy-engine (not just an unused library dependency), one real call at
	// startup; real dedup/rate-limit usage begins once TICKET-009 gives this
	// service actual pipeline logic to guard. Non-fatal: Redis being briefly
	// unavailable at startup shouldn't block this stub's health-check server
	// from coming up.
	redisClient, err := redisclient.New(redisclient.ConfigFromEnv())
	if err != nil {
		log.Printf("%s: redis client config error: %v", serviceName, err)
	} else {
		deduper := redisclient.NewDeduper(redisClient, time.Minute)
		seen, err := deduper.SeenBefore(context.Background(), serviceName+":startup-check")
		if err != nil {
			log.Printf("%s: redis reachability check failed: %v", serviceName, err)
		} else {
			log.Printf("%s: redis reachable (startup dedup check, seenBefore=%v)", serviceName, seen)
		}
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})

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

	ctx, cancel := context.WithTimeout(context.Background(), shutdownWait)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("%s: shutdown error: %v", serviceName, err)
	}
	log.Printf("%s: drained, exiting", serviceName)
}
