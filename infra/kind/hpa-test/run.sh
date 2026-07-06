#!/usr/bin/env bash
# Real, hands-on proof of TICKET-003's AC4 HPA half — see infra/kind/README.md.
# Real cluster-autoscaler node-level scaling is cloud-specific and stays a
# static-analysis-only claim (infra/terraform/{aws,gcp} `terraform validate` +
# `tflint`); this proves the HPA+metrics-server mechanism itself, using the
# exact same HPA shape (3-20 replicas, 70% CPU target) as the real
# deploy/base/core-app/hpa.yaml.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CLUSTER=nectrix-hpa-test

cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Creating kind cluster (default CNI is fine here — not testing NetworkPolicy)"
kind create cluster --name "$CLUSTER"

echo "==> Installing metrics-server (pinned v0.7.2, patched with --kubelet-insecure-tls for kind)"
kubectl apply -f vendor/metrics-server-v0.7.2.yaml
kubectl -n kube-system rollout status deployment/metrics-server --timeout=300s

echo "==> Applying the HPA demo placeholder (same shape as deploy/base/core-app/hpa.yaml)"
kubectl apply -f hpa-test/deployment.yaml
kubectl rollout status deployment/hpa-demo --timeout=120s

echo "==> Waiting for the HPA to report real metrics (not <unknown>) — the container burns CPU on its own"
for i in $(seq 1 24); do
  utilization=$(kubectl get hpa hpa-demo -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null || true)
  if [[ -n "$utilization" ]]; then
    echo "OK: HPA reports live CPU utilization: ${utilization}%"
    kubectl get hpa hpa-demo
    exit 0
  fi
  sleep 5
done

echo "FAIL: HPA never reported live metrics within the timeout"
kubectl get hpa hpa-demo -o yaml
exit 1
