# deploy

Kustomize manifests for the 4 backend deployables (`core-app`, `copy-engine`, `broker-adapters`, `mt5-bridge-gateway`). Named `deploy/`, not `gitops/` — there is no live-reconciling GitOps controller (ArgoCD/Flux) yet, since there's no persistent cluster for one to manage (that's TICKET-003). CI applies these directly via `kubectl apply -k` against an ephemeral `kind` cluster created and torn down within the same job.

## Layout

```
base/
├── namespaces.yaml                 gateway, admin-portal, core-app, copy-engine, async, platform
├── core-app/                       Deployment + Service (namespace: core-app)
├── copy-engine/                    Deployment + Service (namespace: copy-engine), preStop-free —
├── broker-adapters/                drain handling is in-process (see each app's main.go)
└── mt5-bridge-gateway/             Deployment + Service (namespace: copy-engine)

overlays/
├── staging/        kustomization.yaml — CI patches in the built image tags via
│                   `kustomize edit set image ghcr.io/avison9/nectrix/<app>:<sha>`
└── production/     identical shape; deploy-production is gated behind the `production`
                    GitHub Environment's required-reviewer approval
```

`copy-engine`, `broker-adapters`, and `mt5-bridge-gateway` all live in the `copy-engine` namespace, matching the namespace grouping in `nectrix_plan/docs/16-deployment-architecture.md` §16.1. `core-app` gets its own namespace.

## Local testing

```
kubectl kustomize deploy/base                 # validate the base builds
kubectl kustomize deploy/overlays/staging      # validate an overlay builds
```

To actually deploy locally, you need `kind` and standalone `kustomize` (not the `kubectl kustomize` subcommand — that only supports `build`, not `edit set image`) on the host, plus images built and loaded via `kind load docker-image`. See `.github/workflows/main-pipeline.yml`'s `deploy-staging` job for the exact sequence CI runs.

## Design references

- `nectrix_plan/docs/16-deployment-architecture.md` §16.1, §16.3 — namespace layout and deploy pipeline this implements.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-002-cicd-pipeline.md` — the ticket that created this directory.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-003-infra-k8s-terraform.md` — where a real, persistent cluster (and a real GitOps controller) lands.
