# infra/kind

Local `kind` clusters used purely to *test* TICKET-003's acceptance criteria hands-on, where that's possible without a real cloud account. Separate from `deploy/` (the real Kustomize manifests) — these are throwaway test harnesses, not deploy targets.

## netpol-test (AC3)

Proves the NetworkPolicy shape `deploy/base/copy-engine/network-policy.yaml` (+ `deploy/overlays/production/patch-network-policy-live-allowed.yaml`) applies for real: a pod in a `staging`-labeled namespace cannot reach a mocked live-broker endpoint; a pod in a `production`-labeled namespace can.

```
make kind-netpol-test
```

What it does (see `netpol-test/run.sh`):
1. Creates a `kind` cluster with the default CNI (kindnet) disabled — kindnet does **not** enforce `NetworkPolicy` at all, silently.
2. Installs Calico (pinned, committed manifest: `vendor/calico-v3.28.0.yaml`), which does.
3. Applies `netpol-test/namespaces.yaml` (`staging`, `production`, `live-broker-sim`), a mock broker Service (`netpol-test/live-broker-mock.yaml`), and the policy under test (`netpol-test/network-policy.yaml`).
4. Runs a curl pod in each of `staging`/`production`, asserts `staging` is blocked and `production` is allowed.
5. Tears the cluster down.

**Why one cluster with two namespaces, not two real clusters**: AC3's literal wording ("a pod in the staging namespace... in production") is read here as a test-harness simplification for "staging's cluster" vs. "production's cluster" — it proves the policy *shape*, which is exactly what each real per-environment cluster (once one exists) would also enforce via the same manifests.

## hpa-test (AC4, HPA half)

Proves `deploy/base/core-app/hpa.yaml`'s shape (3–20 replicas, 70% CPU target) actually works once metrics-server is present.

```
make kind-hpa-test
```

What it does (see `hpa-test/run.sh`):
1. Creates a plain `kind` cluster (default CNI is fine — not testing NetworkPolicy here).
2. Installs metrics-server (pinned, committed manifest: `vendor/metrics-server-v0.7.2.yaml`, patched with `--kubelet-insecure-tls` — required for kind specifically, not real clusters, since kind's kubelet serving certs aren't signed by a CA metrics-server trusts by default).
3. Applies `hpa-test/deployment.yaml` — a standalone placeholder (`busybox` burning CPU via a `yes` loop) with the *same* HPA shape as `deploy/base/core-app/hpa.yaml`, deliberately **not** `deploy/base/core-app` itself (its image tag, `REPLACED_BY_CI`, is only ever resolved by CI's `kustomize edit set image`, and isn't pullable in an isolated local cluster).
4. Polls `kubectl get hpa` until it reports a real percentage (not `<unknown>`).
5. Tears the cluster down.

**What this doesn't prove**: real cluster-autoscaler node-level scaling is inherently cloud-specific (reacting to unschedulable pods by adding real nodes) — that's validated only via Terraform static analysis (`make tf-validate`, `make tf-lint`), never executed for real. See `infra/terraform/README.md`.

## Host-level tools

`kind` and `kubectl` are host-level tools here, same precedent as `deploy/README.md`'s local-testing section — Docker-in-Docker for `kind` inside the devcontainer isn't practical.
