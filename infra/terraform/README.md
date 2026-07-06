# infra/terraform

Terraform IaC for a real, persistent managed Kubernetes cluster + managed Postgres (Multi-AZ) + managed Redis + networking + object storage, written for **both AWS and GCP** so either can be chosen later (TICKET-003). Azure is not covered.

**No real `terraform apply` has been run against either cloud.** This is deliberate — see `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-003-infra-k8s-terraform.md`'s AC1: "`terraform apply` provisions a working cluster... in a fresh cloud account" is the one acceptance criterion this ticket does **not** exercise for real, since doing so requires a real account and incurs real cost. Everything here is proven correct through fully offline tooling instead (below). Running the real `terraform apply` is a manual step for whoever owns the target cloud account, whenever they're ready to provision (and pay for) real infrastructure.

## Layout

```
infra/terraform/
├── aws/    # EKS, RDS Multi-AZ Postgres, ElastiCache Redis, VPC, S3, ECR, IRSA, GitHub OIDC, WAFv2
├── gcp/    # GKE, Cloud SQL Postgres (regional HA), Memorystore Redis, VPC, GCS, Artifact Registry, Workload Identity (+ Federation), Cloud Armor
└── .checkov.yaml
```

Each cloud dir is a self-contained root module: `versions.tf`, `providers.tf`, `backend.tf`, `variables.tf`, `main.tf`, `outputs.tf`, `envs/{dev,staging,production}.tfvars`, `modules/*`, `bootstrap/` (one-off, see below), `.tflint.hcl`.

## Container registry: ECR / Artifact Registry, not just GHCR

CI (`main-pipeline.yml`) pushes to GHCR today — fine for the ephemeral `kind` cluster used in CI (images are injected directly via `kind load docker-image`, never actually pulled over the network), but a real EKS/GKE cluster's nodes *do* pull over the network, and GHCR packages default to private with no `imagePullSecret` configured anywhere in `deploy/`. So each cloud also gets its own native registry:

- **AWS**: `aws/modules/ecr` — one repository per deployable, `scan_on_push`, immutable tags, a lifecycle policy expiring old images. EKS nodes pull natively via the node IAM role (`AmazonEC2ContainerRegistryReadOnly`, already attached in `modules/eks`) — no Kubernetes Secret needed.
- **GCP**: `gcp/modules/artifact-registry` — one Docker-format repository holding all 4 images (distinguished by path, not by repository, unlike ECR). The GKE node pool gets its own dedicated service account (`modules/gke`, not the project's default Compute Engine SA) bound to `roles/artifactregistry.reader` — again, no Kubernetes Secret needed.

**CI push access uses federated identity, not long-lived cloud credentials in GitHub Secrets**:
- AWS: `aws/modules/github-oidc` — a GitHub Actions OIDC provider + an IAM role trusted only for `repo:avison9/nectrix:ref:refs/heads/main`, scoped to just `ecr:PutImage`/etc. on this project's repositories.
- GCP: `gcp/modules/artifact-registry`'s Workload Identity Federation pool/provider — same repo+ref restriction, bound to a dedicated `ci-ar-push` service account with `roles/artifactregistry.writer` only.

`main-pipeline.yml`'s `build-scan-push` job has the push steps already written, gated behind repo variables (`AWS_ECR_PUSH_ROLE_ARN`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, etc.) that don't exist yet — so they're no-ops today and don't affect the pipeline's already-green status. Once a cloud is actually applied, set those variables from the relevant module's outputs (`ecr_repository_urls`/`ci_ecr_push_role_arn` or `artifact_registry_url`/`ci_workload_identity_provider`/`ci_artifact_registry_push_sa`), and update the `deploy-staging`/`deploy-production` jobs' `kustomize edit set image` step to target the cloud registry's URL instead of `ghcr.io`.

## Object storage: MinIO is local-dev-only

A deliberate deviation from `docs/13-technology-stack.md` §13.2's "MinIO now" MVP guidance: **real environments use managed object storage from day one** — `aws/modules/s3-storage` (S3) and `gcp/modules/gcs-storage` (GCS) — not self-hosted MinIO. MinIO stays exactly where it already is: docker-compose for local dev, and `deploy/components/local-minio` for local `kind` testing. It is never part of the `staging`/`production` Kustomize overlays.

## Environments = Terraform workspaces

```
cd infra/terraform/aws   # or gcp
terraform init -backend=false   # offline — see "Offline verification" below
terraform validate
```

Real environment isolation, once a real account exists:
```
terraform init
terraform workspace new dev && terraform workspace new staging && terraform workspace new production
terraform workspace select dev
terraform apply -var-file=envs/dev.tfvars
```

Each `envs/<env>.tfvars` also sets `environment = "<env>"`, checked in `main.tf` against `terraform.workspace` via a `check` block — applying the wrong `.tfvars` against the wrong workspace fails loudly instead of silently corrupting the wrong environment's state.

## Backend: local today, remote later

`backend.tf` in each cloud dir uses `backend "local" {}` — state is namespaced per-workspace automatically under `terraform.tfstate.d/<workspace>/`, which is all "isolated workspaces/state" requires while no real account exists.

Each `bootstrap/` directory is a separate, tiny root module (its own local state, never migrated) that creates the *remote* backend's storage — an S3 bucket + DynamoDB lock table for AWS, a GCS bucket for GCP. It exists standalone specifically to solve the chicken-and-egg problem (you can't point a backend at a bucket that doesn't exist yet). Run it once, by hand, against a real account:
```
cd infra/terraform/aws/bootstrap   # or gcp/bootstrap
terraform init && terraform apply
```
Then uncomment the `backend "s3" { ... }` / `backend "gcs" { ... }` block in the parent dir's `backend.tf` and run `terraform init -migrate-state`.

## Offline verification (no cloud credentials, ever)

```
terraform fmt -check -recursive infra/terraform
cd infra/terraform/{aws,gcp} && terraform init -backend=false && terraform validate
tflint --init && tflint
checkov -d infra/terraform --config-file infra/terraform/.checkov.yaml
```
Or via `make`: `make tf-fmt tf-validate tf-lint tf-checkov`.

**Why this is genuinely offline:** `terraform validate` needs the provider plugin schema (downloaded from the public Terraform Registry during `init` over plain HTTPS — no cloud credentials) but never calls an AWS/GCP API itself; `-backend=false` skips backend/state initialization entirely. `tflint`'s `deep_check` mode is the one thing that calls real cloud APIs, and it's off in both `.tflint.hcl` files. `checkov` only ever parses HCL statically, regardless of flags.

## Host-level tools

Terraform, `tflint`, and `checkov` are **host-level** tools for this ticket, not devcontainer tools — same precedent already established for `kind`/`kubectl`/standalone `kustomize` in `deploy/README.md` (Docker-in-Docker for these inside the devcontainer isn't practical). The mandatory build/test/lint toolchain (Java, Go, Node) stays in the devcontainer, unchanged.

`tflint` isn't installed via this repo's tooling — Homebrew's `tflint` formula wasn't resolvable in this environment and its own tap failed to clone, so it's a manual one-time install (see the [official install instructions](https://github.com/terraform-linters/tflint?tab=readme-ov-file#installation)). `make tf-lint` assumes it's already on `PATH`.

## Terraform ↔ Kustomize boundary

Cloud resources (VPC, cluster control plane + node-pool scaling bounds, managed Postgres/Redis, object storage + its IAM/Workload-Identity access, WAF/Cloud Armor policy, IAM roles for in-cluster controllers) are Terraform's job. Everything that runs *in* the cluster (namespaces, MinIO for local-only, NetworkPolicy, HPA, the cluster-autoscaler controller Deployment itself, Ingress objects) is Kustomize's job — see `deploy/README.md`.
