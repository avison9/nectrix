package k8sprovision

import (
	"context"
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"

	"github.com/avison9/nectrix/mt-terminal-host/internal/reconcile"
)

const testNamespace = "mt-terminals"

func newTestProvisioner() *Provisioner {
	clientset := fake.NewClientset()
	return New(clientset, testNamespace, "ghcr.io/avison9/nectrix/mt-terminal-image:test", "mt5-bridge-gateway.copy-engine.svc.cluster.local", "8092")
}

func TestEnsureTerminal_CreatesLabeledSecretAndDeployment(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	account := reconcile.AccountRef{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"}
	creds := reconcile.TerminalCredentials{Login: "12345", Password: "s3cr3t", Server: "Pepperstone-Demo", PairingToken: "tok-1"}

	if err := p.EnsureTerminal(ctx, account, creds); err != nil {
		t.Fatalf("EnsureTerminal: %v", err)
	}

	secret, err := p.clientset.CoreV1().Secrets(testNamespace).Get(ctx, secretName("acct-1"), metav1.GetOptions{})
	if err != nil {
		t.Fatalf("expected secret to exist: %v", err)
	}
	if secret.Labels[labelBrokerAccountID] != "acct-1" || secret.Labels[labelManagedBy] != managedByValue {
		t.Fatalf("unexpected secret labels: %+v", secret.Labels)
	}
	// The real Kubernetes API server converts StringData into (base64) Data at write time; the
	// fake clientset's tracker doesn't simulate that admission-time behavior, so this asserts on
	// StringData directly — exactly what EnsureTerminal actually sent.
	if secret.StringData["LOGIN"] != "12345" || secret.StringData["PASSWORD"] != "s3cr3t" {
		t.Fatalf("unexpected secret data: %+v", secret.StringData)
	}
	if secret.StringData["GATEWAY_HOST"] != "mt5-bridge-gateway.copy-engine.svc.cluster.local" {
		t.Fatalf("unexpected gateway host in secret: %q", secret.StringData["GATEWAY_HOST"])
	}
	if secret.StringData["PLATFORM"] != "MT5" {
		t.Fatalf("unexpected platform in secret: %q", secret.StringData["PLATFORM"])
	}

	deployment, err := p.clientset.AppsV1().Deployments(testNamespace).Get(ctx, deploymentName("acct-1"), metav1.GetOptions{})
	if err != nil {
		t.Fatalf("expected deployment to exist: %v", err)
	}
	if deployment.Labels[labelBrokerAccountID] != "acct-1" {
		t.Fatalf("unexpected deployment labels: %+v", deployment.Labels)
	}
	if got := *deployment.Spec.Replicas; got != 1 {
		t.Fatalf("expected 1 replica, got %d", got)
	}
	if len(deployment.Spec.Template.Spec.Containers) != 1 || deployment.Spec.Template.Spec.Containers[0].Image != "ghcr.io/avison9/nectrix/mt-terminal-image:test" {
		t.Fatalf("unexpected container spec: %+v", deployment.Spec.Template.Spec.Containers)
	}
}

func TestEnsureTerminal_IsIdempotent(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	account := reconcile.AccountRef{ID: "acct-1", Status: "PENDING", BrokerType: "MT5"}
	creds := reconcile.TerminalCredentials{Login: "1", Password: "p", Server: "S", PairingToken: "t"}

	for i := 0; i < 3; i++ {
		if err := p.EnsureTerminal(ctx, account, creds); err != nil {
			t.Fatalf("EnsureTerminal call %d: %v", i, err)
		}
	}

	ids, err := p.ListProvisionedAccountIDs(ctx)
	if err != nil {
		t.Fatalf("ListProvisionedAccountIDs: %v", err)
	}
	if len(ids) != 1 || ids[0] != "acct-1" {
		t.Fatalf("expected exactly one provisioned account after 3 idempotent calls, got %v", ids)
	}
}

func TestTeardownTerminal_RemovesSecretAndDeployment(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	account := reconcile.AccountRef{ID: "acct-1", Status: "PENDING", BrokerType: "MT4"}
	creds := reconcile.TerminalCredentials{Login: "1", Password: "p", Server: "S", PairingToken: "t"}

	if err := p.EnsureTerminal(ctx, account, creds); err != nil {
		t.Fatalf("EnsureTerminal: %v", err)
	}
	if err := p.TeardownTerminal(ctx, "acct-1"); err != nil {
		t.Fatalf("TeardownTerminal: %v", err)
	}

	if _, err := p.clientset.CoreV1().Secrets(testNamespace).Get(ctx, secretName("acct-1"), metav1.GetOptions{}); err == nil {
		t.Fatalf("expected secret to be deleted")
	}
	if _, err := p.clientset.AppsV1().Deployments(testNamespace).Get(ctx, deploymentName("acct-1"), metav1.GetOptions{}); err == nil {
		t.Fatalf("expected deployment to be deleted")
	}
}

func TestTeardownTerminal_OnAlreadyAbsentAccount_IsNotAnError(t *testing.T) {
	p := newTestProvisioner()
	if err := p.TeardownTerminal(context.Background(), "never-existed"); err != nil {
		t.Fatalf("TeardownTerminal on absent account: %v", err)
	}
}

func TestListProvisionedAccountIDs_ReflectsMultipleAccounts(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	creds := reconcile.TerminalCredentials{Login: "1", Password: "p", Server: "S", PairingToken: "t"}

	for _, id := range []string{"acct-a", "acct-b", "acct-c"} {
		account := reconcile.AccountRef{ID: id, Status: "PENDING", BrokerType: "MT5"}
		if err := p.EnsureTerminal(ctx, account, creds); err != nil {
			t.Fatalf("EnsureTerminal(%s): %v", id, err)
		}
	}

	if err := p.TeardownTerminal(ctx, "acct-b"); err != nil {
		t.Fatalf("TeardownTerminal(acct-b): %v", err)
	}

	ids, err := p.ListProvisionedAccountIDs(ctx)
	if err != nil {
		t.Fatalf("ListProvisionedAccountIDs: %v", err)
	}
	got := map[string]bool{}
	for _, id := range ids {
		got[id] = true
	}
	if !got["acct-a"] || got["acct-b"] || !got["acct-c"] || len(got) != 2 {
		t.Fatalf("unexpected provisioned account set: %v", ids)
	}
}
