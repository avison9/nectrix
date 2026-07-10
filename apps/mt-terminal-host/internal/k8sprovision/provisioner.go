// Package k8sprovision manages the real Kubernetes lifecycle of Nectrix-hosted MT5/MT4 terminal
// Deployments+Secrets, one pair per broker_accounts row — the first use of k8s.io/client-go in this
// monorepo. Every object is labeled nectrix.io/broker-account-id, so Kubernetes itself is the
// durable "which accounts currently have a running terminal" answer (see internal/reconcile's
// package doc for why that matters), not a separate tracking store this provisioner would have to
// keep consistent by hand.
//
// All writes use Server-Side Apply (metav1.ApplyOptions{Force: true}), not Get-then-Update — the
// same desired-state description can be sent every reconcile cycle without a stale-Update race, and
// a re-run after a partial failure (e.g. Secret applied, Deployment apply crashed) converges
// correctly on the next cycle rather than erroring on an already-exists object.
package k8sprovision

import (
	"context"
	"fmt"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	applyappsv1 "k8s.io/client-go/applyconfigurations/apps/v1"
	applycorev1 "k8s.io/client-go/applyconfigurations/core/v1"
	applymetav1 "k8s.io/client-go/applyconfigurations/meta/v1"
	"k8s.io/client-go/kubernetes"

	"github.com/avison9/nectrix/mt-terminal-host/internal/reconcile"
)

const (
	// labelBrokerAccountID is the ONE label every object this package manages carries — the
	// join key between a Kubernetes Deployment/Secret and a Core App broker_accounts.id.
	labelBrokerAccountID = "nectrix.io/broker-account-id"
	labelManagedBy       = "nectrix.io/managed-by"
	managedByValue       = "mt-terminal-host"

	// fieldManager identifies this process's own Server-Side Apply field ownership — distinct
	// from any other actor that might touch these objects (e.g. `kubectl apply` by hand during
	// debugging), so Force:true only ever overwrites fields THIS provisioner itself last set.
	fieldManager = "mt-terminal-host"
)

// Provisioner manages Deployment+Secret lifecycle for Nectrix-hosted terminals in one Kubernetes
// namespace (deploy/base/mt-terminal-host's RBAC scopes this process to exactly that namespace —
// see this package's own doc and that manifest for the enforced boundary).
type Provisioner struct {
	clientset   kubernetes.Interface
	namespace   string
	image       string // the terminal image reference, e.g. ghcr.io/avison9/nectrix/mt-terminal-image:<tag>
	gatewayHost string // apps/mt5-bridge-gateway's reachable host, injected into every terminal's Secret
	gatewayPort string
}

var _ reconcile.Provisioner = (*Provisioner)(nil)

func New(clientset kubernetes.Interface, namespace, image, gatewayHost, gatewayPort string) *Provisioner {
	return &Provisioner{
		clientset:   clientset,
		namespace:   namespace,
		image:       image,
		gatewayHost: gatewayHost,
		gatewayPort: gatewayPort,
	}
}

func secretName(accountID string) string     { return "mt-terminal-" + accountID }
func deploymentName(accountID string) string { return "mt-terminal-" + accountID }

func labelsFor(accountID string) map[string]string {
	return map[string]string{
		labelBrokerAccountID: accountID,
		labelManagedBy:       managedByValue,
	}
}

// EnsureTerminal server-side-applies the Secret then the Deployment for one account — idempotent,
// safe to call every reconcile cycle for an already-provisioned account, not just once at
// discovery (see this package's own doc on why Server-Side Apply is used throughout).
func (p *Provisioner) EnsureTerminal(ctx context.Context, account reconcile.AccountRef, creds reconcile.TerminalCredentials) error {
	if err := p.applySecret(ctx, account, creds); err != nil {
		return fmt.Errorf("k8sprovision: apply secret for %s: %w", account.ID, err)
	}
	if err := p.applyDeployment(ctx, account); err != nil {
		return fmt.Errorf("k8sprovision: apply deployment for %s: %w", account.ID, err)
	}
	return nil
}

func (p *Provisioner) applySecret(ctx context.Context, account reconcile.AccountRef, creds reconcile.TerminalCredentials) error {
	secretApply := applycorev1.Secret(secretName(account.ID), p.namespace).
		WithLabels(labelsFor(account.ID)).
		WithType(corev1.SecretTypeOpaque).
		WithStringData(map[string]string{
			"LOGIN":         creds.Login,
			"PASSWORD":      creds.Password,
			"SERVER":        creds.Server,
			"PAIRING_TOKEN": creds.PairingToken,
			"GATEWAY_HOST":  p.gatewayHost,
			"GATEWAY_PORT":  p.gatewayPort,
			"PLATFORM":      account.BrokerType,
		})
	_, err := p.clientset.CoreV1().Secrets(p.namespace).Apply(ctx, secretApply, metav1.ApplyOptions{FieldManager: fieldManager, Force: true})
	return err
}

func (p *Provisioner) applyDeployment(ctx context.Context, account reconcile.AccountRef) error {
	labels := labelsFor(account.ID)
	replicas := int32(1)

	container := applycorev1.Container().
		WithName("terminal").
		WithImage(p.image).
		WithEnvFrom(applycorev1.EnvFromSource().
			WithSecretRef(applycorev1.SecretEnvSource().WithName(secretName(account.ID)))).
		WithResources(applycorev1.ResourceRequirements().
			WithRequests(corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse("250m"),
				corev1.ResourceMemory: resource.MustParse("512Mi"),
			}).
			WithLimits(corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse("1"),
				corev1.ResourceMemory: resource.MustParse("1Gi"),
			}))

	podSpec := applycorev1.PodSpec().WithContainers(container)
	podTemplate := applycorev1.PodTemplateSpec().WithLabels(labels).WithSpec(podSpec)

	deploymentApply := applyappsv1.Deployment(deploymentName(account.ID), p.namespace).
		WithLabels(labels).
		WithSpec(applyappsv1.DeploymentSpec().
			WithReplicas(replicas).
			WithSelector(applymetav1.LabelSelector().WithMatchLabels(labels)).
			WithTemplate(podTemplate))

	_, err := p.clientset.AppsV1().Deployments(p.namespace).Apply(ctx, deploymentApply, metav1.ApplyOptions{FieldManager: fieldManager, Force: true})
	return err
}

// TeardownTerminal deletes both the Deployment and Secret for an account no longer listed by Core
// App (e.g. unlinked) — idempotent, a already-absent object is not an error.
func (p *Provisioner) TeardownTerminal(ctx context.Context, accountID string) error {
	if err := p.clientset.AppsV1().Deployments(p.namespace).Delete(ctx, deploymentName(accountID), metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("k8sprovision: delete deployment for %s: %w", accountID, err)
	}
	if err := p.clientset.CoreV1().Secrets(p.namespace).Delete(ctx, secretName(accountID), metav1.DeleteOptions{}); err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("k8sprovision: delete secret for %s: %w", accountID, err)
	}
	return nil
}

// ListProvisionedAccountIDs reads the real, current set of broker_accounts.id values with a live
// Deployment in this namespace right now — this IS "actual" for the reconcile loop, not a cache.
func (p *Provisioner) ListProvisionedAccountIDs(ctx context.Context) ([]string, error) {
	list, err := p.clientset.AppsV1().Deployments(p.namespace).List(ctx, metav1.ListOptions{
		LabelSelector: labelManagedBy + "=" + managedByValue,
	})
	if err != nil {
		return nil, fmt.Errorf("k8sprovision: list deployments: %w", err)
	}
	ids := make([]string, 0, len(list.Items))
	for _, d := range list.Items {
		if id, ok := d.Labels[labelBrokerAccountID]; ok {
			ids = append(ids, id)
		}
	}
	return ids, nil
}
