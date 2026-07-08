// webhook-catcher is TICKET-010 AC4's stand-in for a real Slack/email
// notification channel — no real Slack webhook URL or SMTP credentials
// exist in this project (see infra/observability/alertmanager.yml's own
// comment). It's a small, purpose-built HTTP server, not a third-party
// image, so there's no external supply-chain trust question for something
// that only ever runs in local dev/CI.
//
// Captured bodies live in memory only (no volume/filesystem-permission
// concerns on the distroless nonroot runtime image below) — fine for a
// throwaway test receiver whose whole lifecycle is one docker-compose run.
//
// POST /alert    -- Alertmanager's webhook_configs target; records the raw
//                   request body and logs it.
// GET  /received -- returns every captured body so far, one JSON object per
//                   line, for infra/observability/verify.sh to poll against.
package main

import (
	"io"
	"log"
	"net/http"
	"sync"
)

const addr = ":9099"

var (
	mu       sync.Mutex
	received [][]byte
)

func main() {
	mux := http.NewServeMux()

	mux.HandleFunc("POST /alert", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		defer func() { _ = r.Body.Close() }()
		if err != nil {
			http.Error(w, "reading body: "+err.Error(), http.StatusBadRequest)
			return
		}

		mu.Lock()
		received = append(received, body)
		mu.Unlock()

		log.Printf("webhook-catcher: received alert POST (%d bytes)", len(body))
		w.WriteHeader(http.StatusOK)
	})

	mux.HandleFunc("GET /received", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "text/plain")
		for _, body := range received {
			_, _ = w.Write(body)
			_, _ = w.Write([]byte("\n"))
		}
	})

	log.Printf("webhook-catcher listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
