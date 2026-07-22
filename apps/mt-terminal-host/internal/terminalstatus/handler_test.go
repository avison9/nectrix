package terminalstatus

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/avison9/nectrix/mt-terminal-host/internal/k8sprovision"
)

type fakeLister struct {
	statuses []k8sprovision.TerminalPodStatus
	err      error
}

func (f *fakeLister) ListTerminalPodStatuses(ctx context.Context) ([]k8sprovision.TerminalPodStatus, error) {
	return f.statuses, f.err
}

func TestHandleStatus_RejectsMissingOrWrongToken(t *testing.T) {
	lister := &fakeLister{}
	ts := httptest.NewServer(NewMux(lister, "real-secret", nil))
	defer ts.Close()

	// No header at all.
	resp, err := http.Get(ts.URL + "/internal/terminals/status")
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 with no token, got %d", resp.StatusCode)
	}

	// Wrong token.
	req, _ := http.NewRequest("GET", ts.URL+"/internal/terminals/status", nil)
	req.Header.Set("X-Internal-Service-Token", "wrong-secret")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 with wrong token, got %d", resp.StatusCode)
	}
}

func TestHandleStatus_EmptySharedSecretRejectsEveryRequest(t *testing.T) {
	lister := &fakeLister{}
	ts := httptest.NewServer(NewMux(lister, "", nil))
	defer ts.Close()

	req, _ := http.NewRequest("GET", ts.URL+"/internal/terminals/status", nil)
	req.Header.Set("X-Internal-Service-Token", "")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 when sharedSecret is empty, got %d", resp.StatusCode)
	}
}

func TestHandleStatus_ReturnsRealPodStatusesAsJSON(t *testing.T) {
	transition := time.Date(2026, 7, 22, 12, 0, 0, 0, time.UTC)
	lister := &fakeLister{
		statuses: []k8sprovision.TerminalPodStatus{
			{
				BrokerAccountID:    "acct-1",
				PodName:            "mt-terminal-acct-1-abc",
				Phase:              "Running",
				Ready:              true,
				RestartCount:       0,
				LastTransitionTime: transition,
			},
			{
				BrokerAccountID:    "acct-2",
				PodName:            "mt-terminal-acct-2-def",
				Phase:              "Running",
				Ready:              false,
				RestartCount:       5,
				WaitingReason:      "CrashLoopBackOff",
				LastTransitionTime: transition,
			},
		},
	}
	ts := httptest.NewServer(NewMux(lister, "real-secret", nil))
	defer ts.Close()

	req, _ := http.NewRequest("GET", ts.URL+"/internal/terminals/status", nil)
	req.Header.Set("X-Internal-Service-Token", "real-secret")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var decoded statusResponse
	if err := json.NewDecoder(resp.Body).Decode(&decoded); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(decoded.Terminals) != 2 {
		t.Fatalf("expected 2 terminals, got %+v", decoded.Terminals)
	}
	if decoded.Terminals[0].BrokerAccountID != "acct-1" || !decoded.Terminals[0].Ready {
		t.Fatalf("unexpected first terminal: %+v", decoded.Terminals[0])
	}
	if decoded.Terminals[1].WaitingReason != "CrashLoopBackOff" || decoded.Terminals[1].RestartCount != 5 {
		t.Fatalf("unexpected second terminal: %+v", decoded.Terminals[1])
	}
	if decoded.Terminals[0].LastTransitionTime != "2026-07-22T12:00:00Z" {
		t.Fatalf("unexpected LastTransitionTime encoding: %q", decoded.Terminals[0].LastTransitionTime)
	}
}

func TestHandleStatus_ListerErrorReturns500(t *testing.T) {
	lister := &fakeLister{err: errors.New("kubernetes API unreachable")}
	ts := httptest.NewServer(NewMux(lister, "real-secret", nil))
	defer ts.Close()

	req, _ := http.NewRequest("GET", ts.URL+"/internal/terminals/status", nil)
	req.Header.Set("X-Internal-Service-Token", "real-secret")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	if resp.StatusCode != http.StatusInternalServerError {
		t.Fatalf("expected 500, got %d", resp.StatusCode)
	}
}
