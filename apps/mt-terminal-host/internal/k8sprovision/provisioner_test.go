package k8sprovision

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
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

// The fake clientset has no scheduler/controller to turn a Deployment into a real Pod (unlike a
// real API server), so ListTerminalPodStatuses's own tests create Pod objects directly — this
// helper builds one with the same labels EnsureTerminal's applyDeployment would give its own
// managed Pod, so the label-selector logic under test is exercised exactly as it would be for real.
func newTestPod(accountID, podName string, phase corev1.PodPhase, ready bool, restartCount int32, waitingReason string) *corev1.Pod {
	containerStatus := corev1.ContainerStatus{
		Name:         terminalContainerName,
		Ready:        ready,
		RestartCount: restartCount,
	}
	if waitingReason != "" {
		containerStatus.State = corev1.ContainerState{
			Waiting: &corev1.ContainerStateWaiting{Reason: waitingReason},
		}
	}
	readyStatus := corev1.ConditionFalse
	if ready {
		readyStatus = corev1.ConditionTrue
	}
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      podName,
			Namespace: testNamespace,
			Labels:    labelsFor(accountID),
		},
		Status: corev1.PodStatus{
			Phase: phase,
			Conditions: []corev1.PodCondition{
				{Type: corev1.PodReady, Status: readyStatus, LastTransitionTime: metav1.NewTime(time.Now())},
			},
			ContainerStatuses: []corev1.ContainerStatus{containerStatus},
		},
	}
}

func TestListTerminalPodStatuses_ReturnsEmptyWhenNoPods(t *testing.T) {
	p := newTestProvisioner()
	statuses, err := p.ListTerminalPodStatuses(context.Background())
	if err != nil {
		t.Fatalf("ListTerminalPodStatuses: %v", err)
	}
	if len(statuses) != 0 {
		t.Fatalf("expected no statuses, got %+v", statuses)
	}
}

func TestListTerminalPodStatuses_ReflectsRunningHealthyPod(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	pod := newTestPod("acct-1", "mt-terminal-acct-1-abc123", corev1.PodRunning, true, 0, "")
	if _, err := p.clientset.CoreV1().Pods(testNamespace).Create(ctx, pod, metav1.CreateOptions{}); err != nil {
		t.Fatalf("create test pod: %v", err)
	}

	statuses, err := p.ListTerminalPodStatuses(ctx)
	if err != nil {
		t.Fatalf("ListTerminalPodStatuses: %v", err)
	}
	if len(statuses) != 1 {
		t.Fatalf("expected exactly 1 status, got %+v", statuses)
	}
	got := statuses[0]
	if got.BrokerAccountID != "acct-1" || got.Phase != "Running" || !got.Ready || got.RestartCount != 0 || got.WaitingReason != "" {
		t.Fatalf("unexpected status: %+v", got)
	}
}

// A pod stuck restarting still reports Phase=Running (Kubernetes keeps restarting the container
// within the same Pod) — WaitingReason is what actually surfaces the crash loop, matching
// TerminalPodStatus's own doc comment on exactly this distinction.
func TestListTerminalPodStatuses_DetectsCrashLoopBackOff(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	pod := newTestPod("acct-2", "mt-terminal-acct-2-def456", corev1.PodRunning, false, 7, "CrashLoopBackOff")
	if _, err := p.clientset.CoreV1().Pods(testNamespace).Create(ctx, pod, metav1.CreateOptions{}); err != nil {
		t.Fatalf("create test pod: %v", err)
	}

	statuses, err := p.ListTerminalPodStatuses(ctx)
	if err != nil {
		t.Fatalf("ListTerminalPodStatuses: %v", err)
	}
	if len(statuses) != 1 {
		t.Fatalf("expected exactly 1 status, got %+v", statuses)
	}
	got := statuses[0]
	if got.Ready || got.RestartCount != 7 || got.WaitingReason != "CrashLoopBackOff" {
		t.Fatalf("unexpected status: %+v", got)
	}
}

func TestListTerminalPodStatuses_ReflectsMultipleAccountsIndependently(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	pods := []*corev1.Pod{
		newTestPod("acct-a", "mt-terminal-acct-a", corev1.PodRunning, true, 0, ""),
		newTestPod("acct-b", "mt-terminal-acct-b", corev1.PodPending, false, 0, "ContainerCreating"),
	}
	for _, pod := range pods {
		if _, err := p.clientset.CoreV1().Pods(testNamespace).Create(ctx, pod, metav1.CreateOptions{}); err != nil {
			t.Fatalf("create test pod %s: %v", pod.Name, err)
		}
	}

	statuses, err := p.ListTerminalPodStatuses(ctx)
	if err != nil {
		t.Fatalf("ListTerminalPodStatuses: %v", err)
	}
	byAccount := map[string]TerminalPodStatus{}
	for _, s := range statuses {
		byAccount[s.BrokerAccountID] = s
	}
	if len(byAccount) != 2 {
		t.Fatalf("expected 2 accounts, got %+v", statuses)
	}
	if byAccount["acct-a"].Phase != "Running" || !byAccount["acct-a"].Ready {
		t.Fatalf("unexpected acct-a status: %+v", byAccount["acct-a"])
	}
	if byAccount["acct-b"].Phase != "Pending" || byAccount["acct-b"].WaitingReason != "ContainerCreating" {
		t.Fatalf("unexpected acct-b status: %+v", byAccount["acct-b"])
	}
}

func TestListTerminalPodStatuses_IgnoresPodsWithoutManagedByLabel(t *testing.T) {
	p := newTestProvisioner()
	ctx := context.Background()
	unrelated := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "some-other-pod",
			Namespace: testNamespace,
			Labels:    map[string]string{"app": "not-a-terminal"},
		},
		Status: corev1.PodStatus{Phase: corev1.PodRunning},
	}
	if _, err := p.clientset.CoreV1().Pods(testNamespace).Create(ctx, unrelated, metav1.CreateOptions{}); err != nil {
		t.Fatalf("create unrelated pod: %v", err)
	}

	statuses, err := p.ListTerminalPodStatuses(ctx)
	if err != nil {
		t.Fatalf("ListTerminalPodStatuses: %v", err)
	}
	if len(statuses) != 0 {
		t.Fatalf("expected the unrelated pod to be excluded by the label selector, got %+v", statuses)
	}
}
