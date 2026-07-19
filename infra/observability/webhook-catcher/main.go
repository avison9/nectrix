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
// GET  /received -- returns every captured alert body so far, one JSON
//                   object per line, for infra/observability/verify.sh to
//                   poll against.
//
// TICKET-118 — the same stand-in role for outbound email, since no real SES/
// GCP-Mail credentials exist locally either (SesEmailSender is a documented
// no-op when unconfigured). A dev-only EmailSender implementation POSTs here
// instead of calling AWS SES, so an invitation email's real link is visible
// locally without needing real cloud credentials.
//
// POST /email  -- the dev EmailSender's target; records the raw JSON body
//                 ({"recipient_email":..., "subject":..., "body":...}).
// GET  /emails -- returns every captured email body so far, one JSON object
//                 per line, newest last (same shape as /received).
package main

import (
	"io"
	"log"
	"net/http"
	"sync"
)

const addr = ":9099"

type store struct {
	mu       sync.Mutex
	received [][]byte
}

func (s *store) add(body []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.received = append(s.received, body)
}

func (s *store) writeAll(w http.ResponseWriter) {
	s.mu.Lock()
	defer s.mu.Unlock()
	w.Header().Set("Content-Type", "text/plain")
	for _, body := range s.received {
		_, _ = w.Write(body)
		_, _ = w.Write([]byte("\n"))
	}
}

func captureHandler(s *store, label string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		defer func() { _ = r.Body.Close() }()
		if err != nil {
			http.Error(w, "reading body: "+err.Error(), http.StatusBadRequest)
			return
		}
		s.add(body)
		log.Printf("webhook-catcher: received %s POST (%d bytes)", label, len(body))
		w.WriteHeader(http.StatusOK)
	}
}

func main() {
	alerts := &store{}
	emails := &store{}

	mux := http.NewServeMux()

	mux.HandleFunc("POST /alert", captureHandler(alerts, "alert"))
	mux.HandleFunc("GET /received", func(w http.ResponseWriter, r *http.Request) {
		alerts.writeAll(w)
	})

	mux.HandleFunc("POST /email", captureHandler(emails, "email"))
	mux.HandleFunc("GET /emails", func(w http.ResponseWriter, r *http.Request) {
		emails.writeAll(w)
	})

	log.Printf("webhook-catcher listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
