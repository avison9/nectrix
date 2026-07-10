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

	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"

	"github.com/avison9/nectrix/mt-terminal-host/internal/coreappclient"
	"github.com/avison9/nectrix/mt-terminal-host/internal/k8sprovision"
	"github.com/avison9/nectrix/mt-terminal-host/internal/reconcile"
)

const (
	serviceName       = "mt-terminal-host"
	addr              = ":8093"
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
	terminalProvisionerToken := os.Getenv("MT_TERMINAL_PROVISIONER_TOKEN")
	if terminalProvisionerToken == "" {
		log.Fatalf("%s: MT_TERMINAL_PROVISIONER_TOKEN is required", serviceName)
	}
	terminalImage := os.Getenv("TERMINAL_IMAGE")
	if terminalImage == "" {
		log.Fatalf("%s: TERMINAL_IMAGE is required", serviceName)
	}
	terminalNamespace := envOr("TERMINAL_NAMESPACE", "mt-terminals")
	gatewayHost := envOr("GATEWAY_HOST", "mt5-bridge-gateway.copy-engine.svc.cluster.local")
	gatewayPort := envOr("GATEWAY_PORT", "8092")

	// In-cluster config only — this process is designed to run as a real Pod under the
	// ServiceAccount deploy/base/mt-terminal-host/serviceaccount.yaml grants RBAC to (see that
	// manifest for the exact, deliberately namespace-scoped permissions). It is not meant to run
	// against an out-of-cluster kubeconfig outside tests (internal/k8sprovision's own tests use a
	// fake clientset; infra/kind/mt-terminal-host-test drives this same binary against a real
	// local kind cluster's in-cluster config).
	k8sConfig, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("%s: in-cluster Kubernetes config: %v", serviceName, err)
	}
	clientset, err := kubernetes.NewForConfig(k8sConfig)
	if err != nil {
		log.Fatalf("%s: build Kubernetes clientset: %v", serviceName, err)
	}

	coreApp := coreappclient.New(
		envOr("CORE_APP_INTERNAL_BASE_URL", "http://localhost:8080"),
		internalServiceToken,
		terminalProvisionerToken,
		nil,
	)
	provisioner := k8sprovision.New(clientset, terminalNamespace, terminalImage, gatewayHost, gatewayPort)

	loop := reconcile.New(coreApp, coreApp, provisioner, reconcileInterval, logger)
	go loop.Run(ctx)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})

	server := &http.Server{Addr: addr, Handler: mux}

	go func() {
		log.Printf("%s listening on %s (reconciling terminals in namespace %q)", serviceName, addr, terminalNamespace)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("%s: %v", serviceName, err)
		}
	}()

	<-ctx.Done()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("%s: shutdown error: %v", serviceName, err)
	}
	log.Printf("%s: exiting", serviceName)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
