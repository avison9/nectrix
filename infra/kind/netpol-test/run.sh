#!/usr/bin/env bash
# Real, hands-on proof of TICKET-003's AC3 — see infra/kind/README.md.
# Requires `kind` and `kubectl` on the host (see infra/terraform/README.md's
# "Host-level tools" note).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CLUSTER=nectrix-netpol-test

cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Creating kind cluster (default CNI disabled, for Calico)"
kind create cluster --name "$CLUSTER" --config netpol-test-cluster.yaml

echo "==> Installing Calico (pinned v3.28.0 — kindnet doesn't enforce NetworkPolicy)"
kubectl apply -f vendor/calico-v3.28.0.yaml
kubectl -n kube-system rollout status ds/calico-node --timeout=180s

echo "==> Applying test namespaces, mock broker, and the NetworkPolicy under test"
kubectl apply -f netpol-test/namespaces.yaml
kubectl apply -f netpol-test/live-broker-mock.yaml
kubectl apply -f netpol-test/network-policy.yaml
kubectl -n live-broker-sim rollout status deployment/live-broker-mock --timeout=120s

echo "==> Starting test pods"
kubectl run curl-staging -n staging --image=curlimages/curl:8.10.1 --restart=Never -- sleep 3600
kubectl run curl-production -n production --image=curlimages/curl:8.10.1 --restart=Never -- sleep 3600
kubectl wait --for=condition=Ready pod/curl-staging -n staging --timeout=90s
kubectl wait --for=condition=Ready pod/curl-production -n production --timeout=90s

echo "==> staging must be BLOCKED"
if kubectl exec -n staging curl-staging -- curl -m 3 -sf http://live-broker-mock.live-broker-sim.svc.cluster.local >/tmp/netpol-staging.out 2>&1; then
  echo "FAIL: staging reached live-broker-mock, expected block"
  exit 1
fi
echo "OK: staging blocked, as expected"

echo "==> production must be ALLOWED"
if ! kubectl exec -n production curl-production -- curl -m 3 -sf http://live-broker-mock.live-broker-sim.svc.cluster.local >/tmp/netpol-production.out 2>&1; then
  echo "FAIL: production could not reach live-broker-mock, expected allow"
  cat /tmp/netpol-production.out
  exit 1
fi
echo "OK: production allowed, as expected"

echo "==> AC3 proven: staging blocked, production allowed"
