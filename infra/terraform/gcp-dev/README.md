# infra/terraform/gcp-dev

A real, persistent **dev** environment — one GCE VM, applied for real (unlike
`infra/terraform/{aws,gcp}`, which are deliberately never-applied plan/validate-only
per their own README). Exists because the $300/3-month GCP credit does not
stretch to `infra/terraform/gcp`'s managed architecture (GKE + Cloud SQL +
Memorystore Redis Cluster + Managed Kafka) — this is a cheaper, single-box
alternative, not a replacement for that module.

## Architecture

- **One `e2-standard-4` VM** (4 vCPU / 16GB, ~$120-130/mo all-in — VM + disk +
  static IP + DNS zone). Runs:
  - **k3s** (single-node) for the 9 app services (core-app, copy-engine,
    broker-adapters, mt5-bridge-gateway, mt-terminal-host, admin-portal, web,
    async, gateway) — reuses `deploy/base/*` + `deploy/overlays/dev` directly,
    the same Kustomize manifests staging/production use. Traefik (bundled)
    is the single reverse-proxy/TLS entrypoint for every subdomain.
  - **docker-compose** (`network_mode: host`) for stateful infra — Postgres,
    Redis, Kafka, kafka-ui, MinIO — mirroring local dev's own
    `docker-compose.yml`, reachable from k3s pods via headless Services
    pointing at the node's internal IP (see `deploy/overlays/dev/host-services`).
- **Artifact Registry** (`modules/artifact-registry`) — turns on the
  already-existing but dormant `vars.CLOUD_PROVIDER == 'gcp'` switch in
  `main-pipeline.yml`'s `resolve-registry`/`build-scan-push` jobs. No new
  image-push logic; setting the 4 repo variables below is what flips it on.
- **DNS** (`modules/dns`) — a **delegated child zone for `dev.<domain>`
  only**, not the apex domain. The apex zone (and any existing MX/root
  records) is never touched; only the `dev` subtree is delegated here.

## Credentials / manual steps needed

1. A real GCP project with the $300 credit attached — its project ID goes in
   `dev.tfvars`.
2. Enable APIs on that project: Compute Engine, Cloud DNS, IAM Credentials,
   Artifact Registry, IAP.
   ```
   gcloud services enable compute.googleapis.com dns.googleapis.com \
     iamcredentials.googleapis.com artifactregistry.googleapis.com \
     iap.googleapis.com --project=<project-id>
   ```
3. For the one-time `terraform apply` (bootstrap + this module): run
   `gcloud auth login && gcloud auth application-default login` yourself
   (interactive browser OAuth) — gives Terraform real Application Default
   Credentials for that session, revocable afterward with `gcloud auth revoke`.
   No long-lived JSON key needed.
4. Apply `bootstrap/` once by hand (creates the GCS state bucket), uncomment
   the `backend "gcs"` block in `backend.tf`, `terraform init -migrate-state`,
   then `terraform apply -var-file=dev.tfvars`.
5. After applying, set these repo variables (`gh variable set ...`) from the
   root module's outputs:
   | Repo variable | Terraform output |
   |---|---|
   | `CLOUD_PROVIDER` | `gcp` (literal) |
   | `GCP_WORKLOAD_IDENTITY_PROVIDER` | `ci_workload_identity_provider` |
   | `GCP_CI_PUSH_SERVICE_ACCOUNT` | `ci_push_service_account_email` |
   | `GCP_ARTIFACT_REGISTRY_URL` | `artifact_registry_url` |
   | `GCP_CI_DEPLOY_SERVICE_ACCOUNT` | `ci_deploy_service_account_email` |
   | `GCP_DEV_VM_NAME` / `GCP_DEV_VM_ZONE` | `vm_instance_name` / `var.zone` |
   | `GCP_DEV_VM_INTERNAL_IP` | `vm_internal_ip` |
6. DNS: at whichever registrar/DNS provider manages `nectrix.app` today, add
   an **NS record for the `dev` subdomain** pointing at the 4 name servers in
   the `dns_name_servers` output. This does not touch the apex domain's own
   records (root site, MX, etc.) at all.
7. GitHub repo **Secrets** (`gh secret set ...`) — real, stable values (this
   environment persists across deploys, unlike staging/production's
   throwaway-per-run ones), used by `db-migration-dev`/`deploy-dev`:

   | Secret | Used for |
   |---|---|
   | `DEV_POSTGRES_PASSWORD` | docker-compose Postgres superuser + Liquibase migrations |
   | `DEV_POSTGRES_APP_PASSWORD` | the `nectrix_app` role core-app/copy-engine actually connect as |
   | `DEV_MINIO_ROOT_PASSWORD` | docker-compose MinIO |
   | `DEV_JWT_SIGNING_SECRET` | core-app/admin-portal/web JWT verification (must match across all three) |
   | `DEV_INTERNAL_SERVICE_TOKEN` | core-app/copy-engine/broker-adapters internal-endpoint auth (must be byte-for-byte identical across all three) |
   | `DEV_MT_TERMINAL_PROVISIONER_TOKEN` | core-app's mt-terminal-host provisioning endpoint |
   | `DEV_CTRADER_CLIENT_ID` / `DEV_CTRADER_CLIENT_SECRET` | real cTrader OAuth linking — optional; broker-adapters runs fine without them, cTrader linking just won't work until set |

   Any random 32+ byte value works for the token-shaped ones, e.g.
   `openssl rand -base64 32`. Once set, `db-migration-dev`/`deploy-dev` both
   activate automatically (they're gated on `vars.GCP_DEV_VM_NAME` being set,
   step 5 above — no separate on/off switch needed).

## Fallback: GHCR instead of Artifact Registry

If the $300 credit runs out before a GCP startup-credit grant comes through,
`CLOUD_PROVIDER` can be unset again (or set to anything other than `gcp`) —
`main-pipeline.yml`'s `resolve-registry` job falls straight back to GHCR, no
code change needed. Re-enabling that path for a stopped/deleted dev VM only
requires making the GHCR packages public (Settings → Packages, per package,
since `deploy/overlays/dev` pulls without an `imagePullSecret`).

## Verification

Offline (no credentials): `make tf-fmt tf-validate tf-lint tf-checkov` covers
this module alongside `gcp`/`aws`. Real verification (needs steps 1-6 above)
is in the top-level plan's Verification section.
