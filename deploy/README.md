# deploy

Kustomize manifests for everything that runs *in* a Nectrix cluster — the 4 backend deployables (`core-app`, `copy-engine`, `broker-adapters`, `mt5-bridge-gateway`), the still-placeholder `gateway`/`admin-portal`/`async` workloads, HPA, NetworkPolicy, and (AWS-only) the cluster-autoscaler controller. Named `deploy/`, not `gitops/` — there is no live-reconciling GitOps controller (ArgoCD/Flux) yet, since there's no persistent cluster for one to manage. CI applies these directly via `kubectl apply -k` against an ephemeral `kind` cluster created and torn down within the same job.

Cloud *resources* (the cluster itself, managed Postgres/Redis, networking, object storage, WAF) are Terraform's job, not this directory's — see `infra/terraform/README.md`.

## Layout

```
base/
├── namespaces.yaml                 gateway, admin-portal, core-app, copy-engine, async, platform
├── core-app/                       Deployment + Service + HPA (3-20 replicas, 70% CPU — namespace: core-app)
├── copy-engine/                    Deployment + Service + NetworkPolicy (namespace: copy-engine)
├── broker-adapters/                (shares copy-engine's NetworkPolicy — same namespace)
├── mt5-bridge-gateway/             Deployment + Service (namespace: copy-engine)
├── gateway/                        placeholder Deployment + Service + Ingress (namespace: gateway)
├── admin-portal/                   placeholder Deployment + Service + Ingress (namespace: admin-portal)
├── async/                          placeholder worker Deployment, no Service/Ingress (namespace: async)
└── platform-observability/         Prometheus/Grafana/Loki/Tempo/Alertmanager Deployment+Service+ConfigMap (namespace: platform, TICKET-010) — offline-`kubectl kustomize`-validated only, same "written but never applied" boundary as infra/terraform (no persistent cluster exists to apply it to yet)

components/
├── cloud-aws/          cluster-autoscaler controller + ALB Ingress annotations — opt in via a staging/production overlay's `components:`
├── cloud-gcp/          BackendConfig (Cloud Armor) + GCE Ingress annotations — same opt-in mechanism
└── local-minio/        MinIO StatefulSet+Service — local-dev/kind-only, never staging/production (see infra/terraform/README.md's "Object storage" note)

overlays/
├── staging/        kustomization.yaml — CI patches in the built image tags via
│                   `kustomize edit set image ghcr.io/avison9/nectrix/<app>:<sha>`;
│                   NetworkPolicy stays at base's demo-only default
├── production/     same shape, gated behind the `production` GitHub Environment's
│                   required-reviewer approval; patches in live-broker NetworkPolicy access
│                   (patch-network-policy-live-allowed.yaml)
└── local/           base + components/local-minio, for local kind testing only (see infra/kind/README.md)
```

`copy-engine`, `broker-adapters`, and `mt5-bridge-gateway` all live in the `copy-engine` namespace, matching the namespace grouping in `nectrix_plan/docs/16-deployment-architecture.md` §16.1. `core-app` gets its own namespace.

Neither `cloud-aws` nor `cloud-gcp` is wired into `staging`/`production`'s `components:` list yet (both currently `[]`) — that's a one-line addition once a real cloud is actually chosen (see `infra/terraform/README.md`).

## Local testing

```
kubectl kustomize deploy/base                 # validate the base builds
kubectl kustomize deploy/overlays/staging      # validate an overlay builds
kubectl kustomize deploy/overlays/local        # validate the local (MinIO-included) overlay builds
```

To actually deploy locally, you need `kind` and standalone `kustomize` (not the `kubectl kustomize` subcommand — that only supports `build`, not `edit set image`) on the host, plus images built and loaded via `kind load docker-image`. See `.github/workflows/main-pipeline.yml`'s `deploy-staging` job for the exact sequence CI runs, and `infra/kind/README.md` for the AC3/AC4 hands-on test harnesses (`make kind-netpol-test`, `make kind-hpa-test`).

## Design references

- `nectrix_plan/docs/16-deployment-architecture.md` §16.1, §16.2, §16.3 — namespace layout, network policy, and deploy pipeline this implements.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-002-cicd-pipeline.md` — the ticket that created this directory.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-003-infra-k8s-terraform.md` — the ticket that added `components/`, HPA, NetworkPolicy, and the real cloud infra in `infra/terraform/`. A real GitOps controller (ArgoCD/Flux) reconciling a real persistent cluster is a natural next step once one exists.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-010-observability-stack.md` — the ticket that added `platform-observability/`. See root `README.md`'s Observability section — the real, hands-on-verified surface is `docker-compose.yml`/`infra/observability/`, not this directory.
