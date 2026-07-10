//go:build integration

// Real, hands-on proof against a real Kubernetes cluster (see
// infra/kind/mt-terminal-host-test/run.sh) — not the fake clientset
// internal/k8sprovision's own unit tests use. Connects via KUBECONFIG (set
// by the kind test script to the real cluster's kubeconfig), impersonating
// the EXACT ServiceAccount identity deploy/base/mt-terminal-host/
// {serviceaccount,role,rolebinding}.yaml define — every API call in this
// test goes through the same RBAC enforcement path a real Pod running as
// that ServiceAccount would, proving both the reconcile logic AND the RBAC
// scoping are real, not just documented.
//
// External package (reconcile_test, not reconcile): internal/k8sprovision
// itself imports reconcile (for the Provisioner interface/AccountRef/
// TerminalCredentials types), so a same-package test file here that also
// imports k8sprovision would be a real import cycle — this file exercises
// Loop purely through its exported API (New + Run, never the unexported
// reconcileOnce) for exactly that reason.
package reconcile_test

import (
	"context"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"

	"github.com/avison9/nectrix/mt-terminal-host/internal/k8sprovision"
	"github.com/avison9/nectrix/mt-terminal-host/internal/reconcile"
)

const (
	kindTestNamespace      = "mt-terminals"
	kindTestServiceAccount = "system:serviceaccount:copy-engine:mt-terminal-host"
)

func realKindClientset(t *testing.T, impersonate string) kubernetes.Interface {
	t.Helper()
	kubeconfig := os.Getenv("KUBECONFIG")
	if kubeconfig == "" {
		t.Fatal("KUBECONFIG must be set to a real cluster's kubeconfig (see infra/kind/mt-terminal-host-test/run.sh)")
	}
	config, err := clientcmd.BuildConfigFromFlags("", kubeconfig)
	if err != nil {
		t.Fatalf("build kubeconfig: %v", err)
	}
	if impersonate != "" {
		config.Impersonate = rest.ImpersonationConfig{UserName: impersonate}
	}
	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		t.Fatalf("build clientset: %v", err)
	}
	return clientset
}

// mutableLister is a minimal reconcile.Lister a test can update mid-run —
// local to this file since the package-internal fakes in loop_test.go
// aren't reachable from this external test package.
type mutableLister struct {
	mu       sync.Mutex
	accounts []reconcile.AccountRef
}

func (l *mutableLister) ListMtBrokerAccounts(ctx context.Context) ([]reconcile.AccountRef, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]reconcile.AccountRef, len(l.accounts))
	copy(out, l.accounts)
	return out, nil
}

func (l *mutableLister) setAccounts(accounts []reconcile.AccountRef) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.accounts = accounts
}

type staticCredentialFetcher struct {
	byAccount map[string]reconcile.TerminalCredentials
}

func (f *staticCredentialFetcher) FetchTerminalCredentials(ctx context.Context, brokerAccountID string) (reconcile.TerminalCredentials, error) {
	creds, ok := f.byAccount[brokerAccountID]
	if !ok {
		return reconcile.TerminalCredentials{}, fmt.Errorf("no credentials for %s", brokerAccountID)
	}
	return creds, nil
}

// runOneReconcile triggers exactly one Loop.reconcileOnce pass via the
// public API: Run performs its first reconcile synchronously, before ever
// entering its ticker/select loop (see loop.go), so a short-lived context
// reliably captures one — and only one — pass deterministically.
func runOneReconcile(t *testing.T, loop *reconcile.Loop) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	loop.Run(ctx)
}

// TestKindReal_ReconcileProvisionsAndTearsDownRealDeploymentsAndSecrets proves
// EnsureTerminal/TeardownTerminal work against a genuine API server: real
// objects appear, carry the real labels, and the real API server performs
// the StringData->Data conversion the fake clientset (internal/k8sprovision's
// own unit tests) doesn't simulate — then confirms real teardown once the
// account is no longer listed.
func TestKindReal_ReconcileProvisionsAndTearsDownRealDeploymentsAndSecrets(t *testing.T) {
	clientset := realKindClientset(t, kindTestServiceAccount)
	provisioner := k8sprovision.New(
		clientset, kindTestNamespace,
		"ghcr.io/avison9/nectrix/mt-terminal-image:test",
		"mt5-bridge-gateway.copy-engine.svc.cluster.local", "8092")

	lister := &mutableLister{}
	lister.setAccounts([]reconcile.AccountRef{{ID: "kind-real-acct-1", Status: "PENDING", BrokerType: "MT5"}})
	creds := &staticCredentialFetcher{byAccount: map[string]reconcile.TerminalCredentials{
		"kind-real-acct-1": {Login: "12345", Password: "s3cr3t", Server: "Pepperstone-Demo", PairingToken: "tok-real"},
	}}

	loop := reconcile.New(lister, creds, provisioner, time.Hour, nil)
	runOneReconcile(t, loop)

	ctx := context.Background()
	const objectName = "mt-terminal-kind-real-acct-1"

	deployment, err := clientset.AppsV1().Deployments(kindTestNamespace).Get(ctx, objectName, metav1.GetOptions{})
	if err != nil {
		t.Fatalf("expected a real Deployment to exist in the real cluster: %v", err)
	}
	if deployment.Labels["nectrix.io/broker-account-id"] != "kind-real-acct-1" {
		t.Fatalf("unexpected deployment labels: %+v", deployment.Labels)
	}

	secret, err := clientset.CoreV1().Secrets(kindTestNamespace).Get(ctx, objectName, metav1.GetOptions{})
	if err != nil {
		t.Fatalf("expected a real Secret to exist in the real cluster: %v", err)
	}
	// The real API server converts StringData into (base64-decoded-back-to-plain)
	// Data on write — proof this really is a live server, not the fake
	// clientset internal/k8sprovision's own tests assert StringData against.
	if login := string(secret.Data["LOGIN"]); login != "12345" {
		t.Fatalf("expected real API server StringData->Data conversion; got LOGIN=%q", login)
	}

	lister.setAccounts(nil) // account unlinked / no longer listed by core-app
	runOneReconcile(t, loop)

	if _, err := clientset.AppsV1().Deployments(kindTestNamespace).Get(ctx, objectName, metav1.GetOptions{}); err == nil {
		t.Fatalf("expected the real Deployment to be deleted after teardown")
	}
	if _, err := clientset.CoreV1().Secrets(kindTestNamespace).Get(ctx, objectName, metav1.GetOptions{}); err == nil {
		t.Fatalf("expected the real Secret to be deleted after teardown")
	}
}

// TestKindReal_RBACForbidsAccessOutsideTheGrantedNamespace proves role.yaml's
// namespace scoping is real, not just documented: the exact same
// impersonated identity that freely manages Deployments/Secrets in
// mt-terminals (proven above) is REJECTED by the real API server for the
// identical operation against a different namespace — copy-engine, the very
// namespace this ServiceAccount itself lives in.
func TestKindReal_RBACForbidsAccessOutsideTheGrantedNamespace(t *testing.T) {
	clientset := realKindClientset(t, kindTestServiceAccount)
	ctx := context.Background()

	_, err := clientset.AppsV1().Deployments("copy-engine").List(ctx, metav1.ListOptions{})
	if err == nil {
		t.Fatalf("expected RBAC to forbid listing deployments outside mt-terminals, but it succeeded")
	}
	if !apierrors.IsForbidden(err) {
		t.Fatalf("expected a Forbidden error, got: %v", err)
	}
}
