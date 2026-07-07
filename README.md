# Nectrix

Nectrix is a multi-tenant SaaS trade-copying and portfolio-management platform for retail forex/CFD traders. A "Master" trader's live trades are detected and proportionally replicated into "Follower" accounts in near real time, sized according to each follower's own risk settings. Brokers in scope for launch are **cTrader** (Open API) and **MetaTrader 5** (MT5), behind a broker-abstraction layer designed for future brokers.

Nectrix is a technology provider, not a money manager: it never custodies client funds, only trade-execution permissions granted by each broker account. Monetization is performance fees (high-water-mark based, master sets a %, platform takes a cut), Stripe subscriptions, and IB/partner referral commissions. Onboarding is invitation-only — there is no public self-registration; Admins provision Masters directly, and Followers join only by accepting a Master-issued invite. A social/marketplace layer (leaderboards, master profiles) sits on top of the copy engine, alongside a separate Admin Portal used by Admins/Support and Masters.

## Architecture at a glance

Nectrix is a **modular monolith with selectively extracted services**, not a full microservices mesh:

- **Core App** (Java 21 + Spring Boot) — a single deployable with hard internal module boundaries (enforced via ArchUnit) for Auth, Onboarding, Social, Billing, Admin, Analytics-read, and Notifications. No module may reach into another's database/ORM layer directly.
- **Copy Engine** and **Broker Adapter workers** (Go) — separate deployables from day one, handling the sub-second-latency streaming connections to brokers, sharded by master-account hash.
- **MT5 Bridge Gateway** — a Go gateway process paired with an MQL5 Expert Advisor, since MT5 has no first-party public API.
- All cross-module and cross-service side effects flow through **Kafka** events, not synchronous calls. A `BrokerAdapter` interface normalizes broker-specific behavior so the Copy Engine only ever deals with canonical domain types.
- **Postgres** is the only system of record (no secondary NoSQL store — semi-structured data lives in JSONB columns). **Redis** is hot-path cache/session state only, never system of record. **MinIO** (S3-compatible) stores agreements, fee reports, and statements.
- Clients (Web, iOS, Android) and the separate Admin Portal reach the backend through an API Gateway/BFF.

## Tech stack

| Layer | Choice |
|---|---|
| Core App | Java 21 + Spring Boot |
| Copy Engine / Broker Adapters / MT5 Bridge Gateway | Go (+ MQL5 for the MT5 EA itself) |
| System of record | PostgreSQL 17 |
| Cache / hot state | Redis (Cluster mode at scale) |
| Message bus | Kafka |
| Analytics/OLAP (later phase) | ClickHouse (Postgres materialized views for MVP) |
| Object storage | MinIO (S3-compatible) |
| Follower web app | Next.js (React) + TypeScript + Tailwind |
| Admin Portal | Separate Next.js + TypeScript deployable |
| Mobile (Phase 2) | React Native, sharing a TS logic layer with the web app |
| Infra | Docker, Kubernetes (EKS/GKE/AKS), Terraform, GitHub Actions/GitLab CI → ArgoCD/Flux GitOps |
| Observability | OpenTelemetry, Prometheus/Grafana, Loki/ELK, Tempo/Jaeger |
| Payments | Stripe |

Full rationale for each choice: `../nectrix_plan/docs/13-technology-stack.md`.

## Roadmap

- **Phase 0 — Foundation** *(current)*: repo scaffolding ✅, CI/CD ✅, K8s/Terraform ✅, DB schema/migrations ✅, auth/RBAC, message bus, Redis caching, a stub broker adapter, observability, secrets/envelope encryption, and an empty Admin Portal shell. Nothing user-facing yet.
- **Phase 1 — MVP**: real cTrader/MT5 adapters, the full copy engine (sizing, risk guard, partial-close/SL-TP sync, drawdown protection, reconciliation), invitation-based onboarding, broker IB links, both fee-collection methods, a basic leaderboard, the web dashboard, and Admin Portal MVP features.
- **Phase 2 — V2**: native mobile apps, multi-master portfolios/follow-requests, reverse-copy, reviews, demo-preview copy, partner/IB program dashboard, dispute tooling, feature flags, exportable statements, fraud queue, table partitioning.
- **Phase 3 — Enterprise**: MT5 Manager API broker partnerships, multi-region/active-active DR, white-label, negotiated take-rates, full KYC/AML, and additive AI features (strategy analysis, portfolio optimization, anomaly detection).

Full plan, design docs, and per-ticket acceptance criteria live in the sibling `nectrix_plan/` directory (`docs/` for the SRS/TDD, `phases/` for the ticket-by-ticket build plan). This README will be updated as each phase/ticket lands.

## Local development environment

Local dev is Docker-only and self-contained — no Java, Go, or Node toolchain needs to be installed on the host, only Docker. This is deliberate: the same containers used for local dev are the ones exercised in CI and, ultimately, the basis for what ships to Kubernetes, so there's no separate "prod Dockerfile" that can drift from what's actually run day to day.

### Infra services

`docker-compose.yml` at the repo root brings up the four infra dependencies used across every phase-0-and-later ticket:

| Service | Port(s) | Purpose |
|---|---|---|
| Postgres 17 | 5432 | System of record |
| Redis 7 | 6379 | Cache / hot-path state |
| Kafka (KRaft, single broker) | 9092 (external), 29092 (in-network) | Event bus |
| Kafka UI | 8081 | Browser UI for inspecting topics/messages |
| MinIO | 9000 (API), 9001 (console) | S3-compatible object storage |

```
cp .env.example .env      # then set POSTGRES_PASSWORD and MINIO_ROOT_PASSWORD — no defaults are shipped
make up                   # start all infra services
make ps                   # check health
make logs                 # tail logs
make down                 # stop everything
make clean                # stop and wipe local data volumes
```

### Toolchain (Java / Go / Node)

Application toolchains live in a VS Code Dev Container (`.devcontainer/`), not on the host:

- Java 21 + Gradle 9.6.1
- Go 1.26.4 + protoc/protoc-gen-go + golangci-lint
- Node 22 + npm
- Docker CLI (via docker-outside-of-docker, for building/testing images from inside the container)

Open this repo in VS Code with the **Dev Containers** extension and "Reopen in Container" — it builds the toolchain image and joins the same Docker network as the infra services above, reachable from inside the container at `postgres:5432`, `redis:6379`, `kafka:29092`, `minio:9000`. Ports `8080` (core-app), `8090`–`8092` (the three Go services), `3000` (web), and `3001` (admin-portal) are also forwarded to the host.

## Repository layout

TICKET-001 (repo scaffolding) is done. The monorepo is polyglot on purpose — one JS-only tool doesn't fit Java/Go/TypeScript, so each language ecosystem uses its own idiomatic tooling:

```
apps/
├── core-app/          Java 21 + Gradle multi-module Spring Boot app (+ Dockerfile)
│   ├── bootstrap/         entrypoint + ArchUnit module-boundary tests
│   ├── modules/           auth, invitations, social, billing, admin, analytics, notifications
│   └── archunit-fixtures/ permanent fixture proving the boundary rule actually fires
├── copy-engine/       Go — :8090, in-process SIGTERM drain handler (+ Dockerfile)
├── broker-adapters/   Go — :8091, in-process SIGTERM drain handler (+ Dockerfile)
├── mt5-bridge-gateway/ Go — :8092 (+ Dockerfile)
├── web/               Next.js + TS + Tailwind, follower-facing, :3000
└── admin-portal/      Next.js + TS + Tailwind, separate deployable, :3001

packages/
├── go-domain/         shared Go structs (normalized domain types) + Deduper idempotency interface
├── event-contracts/   one canonical .proto, generated into Go and Java, with a shared round-trip test
├── domain-model/      TypeScript mirror of the normalized types, for frontend DX
└── api-client/        stub — populated once real HTTP APIs exist

deploy/                Kustomize manifests for everything that runs in-cluster
├── base/               namespaces + per-app Deployment/Service/HPA/NetworkPolicy
├── components/         cloud-aws, cloud-gcp, local-minio — opt-in per overlay
└── overlays/
    ├── staging/        image tags patched in by CI at deploy time
    ├── production/     same shape, gated behind manual approval
    └── local/          + MinIO, for local kind testing only

infra/
├── terraform/          real cloud infra (EKS/GKE, managed Postgres/Redis, VPC, S3/GCS, WAF) — aws/ + gcp/, plan-only for now
└── kind/                local kind test harnesses proving NetworkPolicy + HPA work (make kind-netpol-test, kind-hpa-test)

.github/workflows/
├── _build-test.yml     reusable lint/build/test jobs, shared by both workflows below
├── pr-checks.yml        on: pull_request — path-filtered per stack
└── main-pipeline.yml    on: push(main) — full pipeline through production
```

Each app/package has its own README pointing back to the relevant `nectrix_plan/docs/` sections. Module/app boundaries are enforced automatically, not just by convention: ArchUnit blocks any Core App module from importing another's `repository`/`domain` package directly, and `eslint-plugin-boundaries` blocks `web` and `admin-portal` from importing each other.

### Commands

```
make core-app-build   # ./gradlew build (Java)
make core-app-test    # ./gradlew test — includes the ArchUnit boundary checks
make go-build         # go build across all 5 Go modules
make go-test          # go test across all 5 Go modules
make go-lint          # golangci-lint across all 5 Go modules
make proto-gen        # regenerate Go code from packages/event-contracts/proto
make ts-install       # npm install across the TS workspace
make ts-build         # turbo run build (web, admin-portal, domain-model, api-client)
make ts-lint          # turbo run lint, including the boundaries rule
make tf-fmt           # terraform fmt -check, both clouds
make tf-validate      # terraform init -backend=false + validate, both clouds
make tf-lint          # tflint, both clouds
make tf-checkov       # static analysis, both clouds
make kind-netpol-test # real kind+Calico hands-on proof of the staging/production network policy
make kind-hpa-test    # real kind+metrics-server hands-on proof of HPA wiring
```

## CI/CD pipeline

TICKET-002 is done. Every PR runs lint/build/test (path-filtered per stack — a PR touching only `apps/web` doesn't trigger a Gradle build); merging to `main` runs the full pipeline unattended through a real GitHub Environment approval gate:

```
PR:    changes (path-filter) → build-test (core-app / go-apps / ts-apps, as relevant)

main:  build-test → integration-test (ephemeral Postgres/Redis/Kafka via docker-compose)
       → build-scan-push (4 backends: build image → Trivy scan, CRITICAL/HIGH gated → push to GHCR)
       → db-migration-staging (no-op placeholder until TICKET-004)
       → deploy-staging (ephemeral kind cluster: deploy, verify the connection-draining
         hook fires, E2E smoke check, teardown — all in one job, since the cluster only
         exists on that job's runner)
       → db-migration-production (no-op placeholder)
       → deploy-production (same shape, behind a required-reviewer approval gate)
```

Notes:
- **No persistent cluster yet** (that's TICKET-003) — staging/production "deploys" are ephemeral `kind` clusters created and torn down within a single CI job, using `kubectl apply -k` against `deploy/overlays/{staging,production}`. Once a real cluster exists, a real GitOps controller (ArgoCD/Flux) reconciling it continuously is the natural next step.
- **Connection-draining** (`copy-engine`, `broker-adapters`): handled by an in-process `SIGTERM` handler in each app (see `main.go`), not a Kubernetes `preStop` hook — the distroless runtime images have no shell to exec into, and (discovered the hard way) `kubectl logs` doesn't reliably capture an exec hook's output anyway.
- **Registry**: `ghcr.io/avison9/nectrix/<app>:<commit-sha>`, packages default to private on first push. Fine for CI's ephemeral `kind` cluster (images are injected directly, never pulled over the network), but a real EKS/GKE cluster needs a cloud-native registry to pull from — a single `vars.CLOUD_PROVIDER` repo variable (`aws`/`gcp`, unset today) switches the whole pipeline (build-scan-push's mirror push *and* both deploy jobs' pull/deploy source) over to ECR/Artifact Registry once real infra exists (see `infra/terraform/README.md`'s "Container registry" section).
- **Branch protection** on `main` requires all `pr-checks.yml` job names as status checks (configured via the GitHub API, not committed YAML — a skipped job counts as passing).
- **Production approval**: the `production` GitHub Environment requires a review from `avison9` before `deploy-production` proceeds — configured via the GitHub API/UI, not code.

## Infrastructure (Terraform + real Kubernetes)

TICKET-003 is done. `infra/terraform/{aws,gcp}/` writes the real, persistent infrastructure a real environment needs — managed Kubernetes (EKS/GKE), managed Postgres (RDS Multi-AZ / Cloud SQL regional HA), managed Redis (ElastiCache / Memorystore), VPC/networking, object storage (S3/GCS), a container registry (ECR/Artifact Registry, with federated-identity CI push access — see "Container registry" in `infra/terraform/README.md`), and WAF (WAFv2 / Cloud Armor) — for **both** AWS and GCP, in separate directories, so either can be picked later.

**No real `terraform apply` has been run against either cloud** — that's a deliberate, explicit decision (see `infra/terraform/README.md`): no real account, no real cost. Verification is fully offline instead:

```
make tf-fmt        # terraform fmt -check, both clouds
make tf-validate   # terraform init -backend=false + validate, both clouds — no credentials
make tf-lint       # tflint, both clouds (deep_check stays off — no cloud API calls)
make tf-checkov    # static analysis (checkov), both clouds — 129 passed / 0 failed
```

Where an acceptance criterion is genuinely testable without a real cloud account, it's proven hands-on instead of just declared:

```
make kind-netpol-test   # real kind + Calico cluster: staging blocked from a mock live-broker endpoint, production allowed
make kind-hpa-test      # real kind + metrics-server cluster: HPA reports live CPU utilization, not <unknown>
```

Notes:
- **Object storage is a deliberate deviation from `docs/13-technology-stack.md` §13.2's "MinIO now" MVP guidance**: real environments get a real S3/GCS bucket from Terraform, not self-hosted MinIO. MinIO stays exactly where it already was — docker-compose for local dev, and `deploy/components/local-minio` (opt-in, `deploy/overlays/local` only) for local `kind` testing.
- **Environments = Terraform workspaces** (`dev`/`staging`/`production`), one root module per cloud. Each `envs/<env>.tfvars` also sets `environment`, checked against the selected workspace via a `check` block — applying the wrong `.tfvars` against the wrong workspace fails loudly instead of silently.
- **Terraform owns cloud resources; Kustomize (`deploy/`) owns everything in-cluster** — namespaces, NetworkPolicy, HPA, the (AWS-only) cluster-autoscaler controller, Ingress objects. Cloud-specific Ingress annotations are Kustomize *components* (`deploy/components/cloud-{aws,gcp}`), not duplicated overlays.
- Terraform, `tflint`, `checkov`, and `kind` are **host-level** tools for this ticket, same precedent as `kubectl`/standalone `kustomize` in `deploy/README.md`.

## Database & migrations

TICKET-004 is done. The full schema from `nectrix_plan/docs/06-database-schema.md` (31 tables) is implemented via **Liquibase** — chosen over Flyway specifically because Liquibase Community supports real per-changeset rollback for free (Flyway's `undo` is a paid Teams feature). Changesets live in `apps/core-app/db/`, a Gradle subproject deliberately separate from `apps/core-app/bootstrap` (the running app), so `liquibase-core` never ends up on the app's own classpath.

```
make db-migrate       # apply all pending changesets (schema + reference data)
make db-migrate-down  # real rollback of every changeset — proven repeatable: up → down → up
make db-seed-dev      # additionally apply dev-only synthetic users/broker-accounts/master-profile (never staging/production)
make db-status        # list pending changesets
```

Two Postgres roles, required by the audit-log restriction below:
- **`nectrix`** (the existing superuser) — runs all migrations. Never used by the running app.
- **`nectrix_app`** (created/granted by migration) — what `bootstrap`'s Spring datasource actually connects as. Full CRUD everywhere except `audit_log`, which is INSERT+SELECT only — `nectrix_plan/docs/17-security-architecture.md` §17.6, so even a compromised app credential can't rewrite audit history. Verified hands-on: a real integration test attempts `UPDATE`/`DELETE` against `audit_log` as `nectrix_app` and confirms both are rejected with a permission error.

Requires `POSTGRES_APP_PASSWORD` in your `.env` (see `.env.example`) — same no-default pattern as `POSTGRES_PASSWORD`. See `apps/core-app/README.md` for more detail.
