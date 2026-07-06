package main

import (
	"encoding/json"
	"log"
	"net/http"

	domain "github.com/avison9/nectrix/go-domain"
)

const (
	serviceName = "copy-engine"
	addr        = ":8090"
)

type healthResponse struct {
	Service string `json:"service"`
	Status  string `json:"status"`
}

func main() {
	// Referencing go-domain proves the shared-package wiring resolves via
	// go.work; real pipeline usage begins once TICKET-009 lands.
	var _ domain.NormalizedTradeEvent

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(healthResponse{Service: serviceName, Status: "ok"})
	})

	log.Printf("%s listening on %s", serviceName, addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
