#!/usr/bin/env bash
# Real, hands-on proof that deploy/base/mt-terminal-host's RBAC manifests
# (serviceaccount.yaml/role.yaml/rolebinding.yaml) actually grant the
# intended, namespace-scoped access — see infra/kind/README.md.
#
# Pure kubectl (no `go` needed): this repo's `go` toolchain lives in the
# devcontainer, while `kind`/`kubectl` are host-level tools (see
# infra/kind/README.md's own "Host-level tools" note) — no single
# environment here has both, so this mirrors netpol-test/hpa-test's own
# kubectl-only verification style rather than needing a Go binary. The
# reconcile LOOP LOGIC itself (desired-vs-actual diffing, credential-fetch
# gating, idempotent re-provisioning) is proven separately, hermetically,
# by real Go unit tests against fake Lister/CredentialFetcher/Provisioner
# implementations (apps/mt-terminal-host/internal/reconcile/loop_test.go)
# and a fake Kubernetes clientset (internal/k8sprovision/provisioner_test.go)
# — both already real, `-race`-clean, no mocked-away logic, just not
# runnable in the same process as this real-cluster RBAC check. A real
# Go+kind integration test also exists
# (internal/reconcile/reconcile_integration_test.go, //go:build integration)
# for a CI environment that has both toolchains together.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CLUSTER=nectrix-mt-terminal-host-test
KUBECONFIG_FILE="$(mktemp)"
IMPERSONATE="system:serviceaccount:copy-engine:mt-terminal-host"
NS=mt-terminals
OBJECT_NAME=mt-terminal-kind-test-acct

cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  rm -f "$KUBECONFIG_FILE"
}
trap cleanup EXIT

echo "==> Creating kind cluster (default CNI is fine — not testing NetworkPolicy here)"
kind create cluster --name "$CLUSTER"
kind get kubeconfig --name "$CLUSTER" > "$KUBECONFIG_FILE"
export KUBECONFIG="$KUBECONFIG_FILE"

echo "==> Creating the two real namespaces this RBAC shape spans"
kubectl create namespace copy-engine
kubectl create namespace "$NS"

echo "==> Applying the REAL RBAC manifests under test (not a test-local copy)"
kubectl apply -f ../../deploy/base/mt-terminal-host/serviceaccount.yaml
kubectl apply -f ../../deploy/base/mt-terminal-host/role.yaml
kubectl apply -f ../../deploy/base/mt-terminal-host/rolebinding.yaml

echo "==> As the impersonated ServiceAccount: creating a Secret in $NS (mirrors internal/k8sprovision.applySecret)"
kubectl --as="$IMPERSONATE" -n "$NS" create secret generic "$OBJECT_NAME" \
  --from-literal=LOGIN=12345 --from-literal=PASSWORD=s3cr3t \
  --from-literal=SERVER=Pepperstone-Demo --from-literal=PAIRING_TOKEN=tok-real \
  --from-literal=PLATFORM=MT5
kubectl -n "$NS" label secret "$OBJECT_NAME" nectrix.io/broker-account-id=kind-test-acct nectrix.io/managed-by=mt-terminal-host

echo "==> As the impersonated ServiceAccount: creating a Deployment in $NS (mirrors internal/k8sprovision.applyDeployment)"
kubectl --as="$IMPERSONATE" -n "$NS" create deployment "$OBJECT_NAME" --image=busybox:1.36 -- sleep 3600
kubectl -n "$NS" label deployment "$OBJECT_NAME" nectrix.io/broker-account-id=kind-test-acct nectrix.io/managed-by=mt-terminal-host

echo "==> Confirming both real objects exist with the expected label"
kubectl -n "$NS" get secret,deployment -l nectrix.io/broker-account-id=kind-test-acct

echo "==> As the impersonated ServiceAccount: deleting both (mirrors internal/k8sprovision.TeardownTerminal)"
kubectl --as="$IMPERSONATE" -n "$NS" delete secret "$OBJECT_NAME"
kubectl --as="$IMPERSONATE" -n "$NS" delete deployment "$OBJECT_NAME"

remaining=$(kubectl -n "$NS" get secret,deployment -l nectrix.io/broker-account-id=kind-test-acct --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [[ "$remaining" != "0" ]]; then
  echo "FAIL: expected both objects deleted, $remaining remain"
  exit 1
fi
echo "OK: teardown removed both real objects"

echo "==> As the SAME impersonated identity: confirming RBAC forbids reaching outside $NS"
if kubectl --as="$IMPERSONATE" -n copy-engine get deployments >/tmp/mt-terminal-host-rbac-negative.out 2>&1; then
  echo "FAIL: impersonated ServiceAccount could list deployments in copy-engine, expected Forbidden"
  cat /tmp/mt-terminal-host-rbac-negative.out
  exit 1
fi
if ! grep -qi "forbidden" /tmp/mt-terminal-host-rbac-negative.out; then
  echo "FAIL: expected a Forbidden error, got:"
  cat /tmp/mt-terminal-host-rbac-negative.out
  exit 1
fi
echo "OK: RBAC correctly forbids access outside $NS"

echo "==> Real RBAC scoping proven against a live cluster"
