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

- **Phase 0 — Foundation**: repo scaffolding, CI/CD, K8s/Terraform, DB schema/migrations, auth/RBAC, message bus, Redis caching, a stub broker adapter, observability, secrets/envelope encryption, and a working Admin Portal shell. Nothing user-facing yet — that starts in Phase 1.
- **Phase 1 — MVP** *(current)*: real cTrader/MT5 adapters, the full copy engine (sizing, risk guard, partial-close/SL-TP sync, drawdown protection, reconciliation), invitation-based onboarding, broker IB links, both fee-collection methods, a basic leaderboard, the web dashboard, and Admin Portal MVP features.
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

The monorepo is polyglot on purpose — one JS-only tool doesn't fit Java/Go/TypeScript, so each language ecosystem uses its own idiomatic tooling:

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
└── api-client/        shared HTTP client (login/refresh/admin endpoints) — first built out for real by TICKET-012

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

Every PR runs lint/build/test (path-filtered per stack — a PR touching only `apps/web` doesn't trigger a Gradle build); merging to `main` runs the full pipeline unattended through a real GitHub Environment approval gate:

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

`infra/terraform/{aws,gcp}/` writes the real, persistent infrastructure a real environment needs — managed Kubernetes (EKS/GKE), managed Postgres (RDS Multi-AZ / Cloud SQL regional HA), managed Redis (ElastiCache / Memorystore), VPC/networking, object storage (S3/GCS), a container registry (ECR/Artifact Registry, with federated-identity CI push access — see "Container registry" in `infra/terraform/README.md`), and WAF (WAFv2 / Cloud Armor) — for **both** AWS and GCP, in separate directories, so either can be picked later.

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

The full schema from `nectrix_plan/docs/06-database-schema.md` (31 tables) is implemented via **Liquibase** — chosen over Flyway specifically because Liquibase Community supports real per-changeset rollback for free (Flyway's `undo` is a paid Teams feature). Changesets live in `apps/core-app/db/`, a Gradle subproject deliberately separate from `apps/core-app/bootstrap` (the running app), so `liquibase-core` never ends up on the app's own classpath.

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

## Auth & Identity

`apps/core-app/modules/auth` implements login, session/refresh-token rotation with reuse detection, TOTP 2FA, and Google/Apple OAuth login — the shared primitives later admin-provisioning/accept-invite tickets call into. There is no public self-registration route anywhere (`POST /api/v1/auth/register` 404s by design, not a stub). Access tokens are HS256 JWTs (~15 min); refresh tokens are opaque and rotate on every use, with reuse of an already-rotated token revoking every session for that user. Rate limiting (Redis, 5 attempts/15 min default) guards `/login` and `/2fa/verify`.

Google's OAuth code path is real; Apple's is implemented but not yet tested against Apple's real servers (two documented gaps — see `apps/core-app/README.md`'s Auth section). 2FA secrets are encrypted via TICKET-011's real KMS-backed envelope encryption (`modules/crypto`), not a local stub — see the Secrets Management section below.

Requires `JWT_SIGNING_SECRET` in your `.env` (see `.env.example`) — same no-default pattern as `POSTGRES_APP_PASSWORD`. See `apps/core-app/README.md` for the full endpoint list and setup instructions.

## RBAC

Two independent enforcement layers per `nectrix_plan/docs/17-security-architecture.md` §17.3: coarse role checks (`@PreAuthorize("hasRole(...)")`) at the route level, and fine-grained object-ownership checks (`@PostAuthorize` + a shared `SecurityPermissions` bean referenced by SpEL bean name, not a compile-time import) at the service level — the IDOR-prevention layer, demonstrated against `BrokerAccount` and reusable for any future per-user-owned resource. Demo admin endpoints (impersonation — issues a JWT tagged with the acting admin's ID; a ledger-adjustment stub distinguishing `ADMIN` from `SUPPORT`) each write one audited `audit_log` row. Role management for existing accounts is still a `make role-grant`/`role-revoke`/`role-list` CLI; TICKET-012's Admin Portal adds a real UI for provisioning brand-new Admin/Support accounts with a role assigned at creation — see the Admin Portal section below.

Fixed one real, previously-undetected bug along the way: Spring MVC's bare `@PathVariable UUID id` (no explicit name) silently fails at request time unless the class file retains real parameter names — `apps/core-app/build.gradle.kts` now sets javac's `-parameters` flag for every subproject, not just the ones this ticket touched (TICKET-005's own `oauthCallback` route had the same latent bug, just never exercised by a test until now).

See `apps/core-app/README.md` for the full endpoint list and the ownership-check pattern to reuse.

## Message Bus

Kafka's topic catalog (`nectrix_plan/docs/15-event-driven-architecture.md` §15.3, plus `invitations`/`follow-requests` from the invitation-only onboarding model) is created explicitly — `infra/kafka/create-topics.sh` (`make kafka-topics`), 3 partitions per topic + a `.dlq` variant each, idempotent — rather than relying on Kafka's own `auto.create.topics.enable` default, which would silently give every topic a single, ordering-defeating partition. `packages/event-contracts/{java,go}` now carries, alongside the existing Protobuf schemas (one new message type per topic, sharing a common `EventEnvelope`), a reusable typed producer + idempotent-consumer-with-DLQ helper library in both languages: consume → dedup-check → handle-with-retry → on exhaustion, publish to `<topic>.dlq` with failure-context headers and only then commit (manual commit only, never auto-commit) — verified hands-on with a real producer/consumer round trip proving per-key ordering survives a consumer restart, and a real always-failing handler proving a message lands on the DLQ after exactly the configured retry count, not an infinite loop or a silent drop.

Go's idempotency-dedup default reuses `packages/go-domain`'s already-forward-declared `Deduper` interface; Java originally got an equivalent self-contained Redis-backed default, since consolidated onto `packages/redis-client` by TICKET-008. Real (never-applied, offline-validated) Terraform modules exist for both clouds — AWS MSK and GCP's Managed Service for Apache Kafka — following the same `elasticache-redis`/`memorystore-redis` pattern as everything else in `infra/terraform/`.

See `packages/event-contracts/README.md` for the full topic catalog and the consumer-helper usage pattern to reuse.

## Redis / Caching

`packages/redis-client/{java,go}` is the platform's shared, cluster-aware Redis client library — the one place standalone-vs-cluster topology branches (`REDIS_MODE=standalone|cluster`, an explicit switch since neither Jedis's `JedisCluster` nor go-redis's `ClusterClient` works transparently against a plain non-cluster-enabled node), and the canonical home for the idempotency-dedup (`SET key val NX EX ttl`) and rate-limiting (single-key Lua token-bucket, lazy refill against Redis's own `TIME` command) helpers every other Redis consumer builds on. Consolidates two helpers built ahead of this ticket by tickets that needed Redis before it existed: TICKET-005's `RateLimiterService` (now a thin adapter over the shared `TokenBucketRateLimiter`) and TICKET-007's dedup defaults (`event-contracts/java`'s `RedisDeduplicator` and `event-contracts/go`'s now-deleted `redisdeduper` package).

The hard rule this package is built around, per `nectrix_plan/docs/15-event-driven-architecture.md` §15.5: every Redis-cached fact must have a Postgres-durable source of truth and a re-derivation path — Redis is a fast-path optimization, never storage. Verified hands-on, not just asserted: a real `FLUSHALL` against the live local Redis mid-test, confirming both the dedup and rate-limit helpers keep working correctly afterward with no crash or corruption (a dedup key looks "new" again, a rate-limit bucket resets to full — expected degradation, not silent misbehavior). Concurrency correctness is also verified for real — 50 goroutines/threads racing the same dedup key (exactly one must win), and 50 concurrent requests racing a 10-capacity, zero-refill bucket (exactly 10 must succeed) — rather than trusting the design on paper.

Real (never-applied, offline-validated) Terraform now provisions genuine Redis **Cluster** (sharded) mode on both clouds — AWS ElastiCache's `num_node_groups`/`replicas_per_node_group` and a new GCP `memorystore-redis-cluster` module (`google_redis_cluster`, a different resource from the non-cluster-capable `google_redis_instance` used pre-TICKET-008, and a different networking model too: Private Service Connect, not the VPC-peering `PRIVATE_SERVICE_ACCESS` style everything else uses).

See `packages/redis-client/README.md` for the full design (including why plain `EVAL` is used every call instead of cached-`EVALSHA`) and `infra/terraform/README.md` for the Redis Cluster infrastructure.

## Copy Trading Engine

`packages/go-domain`'s `BrokerAdapter` interface (`docs/04-architecture-overview.md` §4.3, method-for-method — connect/disconnect/healthCheck/getAccountSnapshot/getOpenPositions/streamTradeEvents/placeOrder/modifyPosition/closePosition/resolveSymbol/getSymbolSpecification) is the abstraction boundary the whole multi-broker bet depends on: the Copy Engine never speaks cTrader/MT5 protocol directly. `apps/copy-engine/internal/stubadapter` is the first (and, until Phase 1's real cTrader/MT5 adapters, only) implementation — two of them, in fact (`StubBrokerAdapter`/`StubBrokerAdapterVariant`, CTRADER vs MT5, genuinely different fill-slippage behavior), proving the abstraction is real by swapping one for the other with zero pipeline code changes.

`apps/copy-engine/internal/pipeline` implements the real (if simplified) pipeline shape from `docs/08-copy-trading-engine.md` §8.2/Appendix A: Normalizer → Dedup Filter (Redis fast-path + `trade_signals`' unique-constraint durable guard) → Relationship Matcher (`copy_relationships` query) → Order Dispatcher (1:1 volume copy — real money-management/risk-guard formulas are Phase 1) → publish `CopiedTradeEvent` to Kafka's `copied-trades` topic. A new `POST /test/inject-trade-event` endpoint drives it end-to-end — verified hands-on: an injected synthetic event produces a real `trade_signals` row and a real published event; the identical event submitted concurrently 20 times produces exactly one `trade_signals` row (dedup wiring proven under a genuine race, not just sequentially).

This is also the first Go service to talk to Postgres directly (`jackc/pgx/v5`, connecting as the same restricted `nectrix_app` role `core-app` uses).

See `apps/copy-engine/README.md` for the full design.

## Observability

Every service inherits tracing, metrics, structured logging, and alerting by default rather than bolting it on later, per `docs/18-scaling-observability-dr.md` §18.2's diagram (all services → Grafana as the single pane of glass over traces/metrics/logs; Prometheus → Alertmanager → on-call). The full LGTM stack (Loki, Grafana, Tempo, Prometheus, Alertmanager, plus Promtail for log shipping and a small self-written webhook-catcher standing in for a real Slack/email channel — no real notification credentials exist in this project) runs in `docker-compose.yml`, config in `infra/observability/`.

`core-app` gets the OpenTelemetry Java auto-instrumentation agent (zero code changes for Spring MVC/JDBC tracing) plus Micrometer/Actuator (`/actuator/prometheus`) plus `logstash-logback-encoder` JSON logging with field-masking for allow-listed sensitive keys (`password`, `secret`, `token`, `credential`, `ciphertext`, ...) — trace/log correlation is automatic via the agent's Logback MDC injection. `copy-engine` gets the equivalent by hand (`internal/observability`): OTel Go SDK + `otelhttp` middleware, `prometheus/client_golang` metrics, and a redacting `log/slog` JSON logger — TICKET-009's pipeline already threaded `context.Context` through every stage, so each stage (normalize/dedup/relationship-match/dispatch/publish) got a real child span for free, all under one trace per request.

All 4 acceptance criteria are verified hands-on, repeatably, via `make observability-verify` (`infra/observability/verify.sh`) — not just asserted: a real request produces a real trace in Tempo and real metrics in Prometheus; an injected synthetic event is traceable as one single trace across every pipeline stage; a deliberately-logged fake `secret` field is confirmed redacted in Loki's aggregated log view; and a deliberately-triggered error-rate alert is confirmed `firing` in Prometheus and confirmed received by the webhook-catcher. Kustomize manifests for a real cluster (`deploy/base/platform-observability/`) are written and offline-`kubectl kustomize`-validated, matching Terraform's own "written, never applied" precedent — there's no persistent cluster to deploy into yet.

See `apps/copy-engine/README.md` and `apps/core-app/README.md` for the per-service details.

## Secrets Management & Envelope Encryption

`apps/core-app/modules/crypto` is a shared-kernel Gradle module providing `EnvelopeEncryptionService` — real KMS-backed envelope encryption (`docs/17-security-architecture.md` §17.2), replacing TICKET-005's temporary local AES-GCM 2FA-secret stub outright (`StubAesGcmTwoFactorSecretCipher` is deleted, not left as a fallback). Per encrypted field: a fresh Data Encryption Key (DEK) is generated via KMS `GenerateDataKey`, used to AES-256-GCM-encrypt the plaintext locally (no per-byte KMS round trip), and the KMS-wrapped DEK is packed alongside the field ciphertext into one opaque stored string. A `kms_key_versions` table is the explicit, application-level version registry (distinct from a cloud KMS's own opaque internal key rotation) — new encryptions always use whichever version `is_current`, so a KEK rotation never requires a synchronous full-table re-encryption; old ciphertext stays decryptable indefinitely via the KMS key ID its tagged version points to.

`AwsEnvelopeKmsClient` (AWS SDK v2) is the only real implementation today — endpoint-overridable so the identical code path talks to **LocalStack** (`docker-compose.yml`, a real AWS KMS API surface, emulated) locally/in CI and real AWS KMS in production via the SDK's normal IRSA credential chain. `infra/localstack/init-kms.sh` (`make localstack-init`) creates the real LocalStack key and seeds `kms_key_versions`' version-1 row.

Real (never-applied, offline-validated) Terraform KMS modules exist for both clouds, wired into each cloud's IRSA/Workload-Identity role alongside existing S3/GCS access. The other 11 checkov-skipped engines (RDS, ElastiCache, S3, EKS, MSK, GCS, CloudSQL, Artifact Registry, ...) migrating onto customer-managed keys is explicit, honestly-labeled future work, deferred until a real cloud provider is chosen — this ticket only builds the reusable KMS utility both that future work and Phase 1's broker-credential encryption depend on.

Verified hands-on against real LocalStack KMS + Postgres: a real encrypt→decrypt round trip; a real KEK rotation (a second, genuinely separate LocalStack KMS key created, `kms_key_versions.rotate()` called, confirming version-1-encrypted data still decrypts correctly *and* a fresh encryption is tagged with the new version); and a redaction proof — a real `/2fa/enable` call's plaintext TOTP secret is confirmed absent from the app's actual captured stdout, with only the masked `"secret":"****"` form present (TICKET-010's `MaskingJsonGeneratorDecorator`, proven against this specific code path, not just the demo `HelloController` one).

See `apps/core-app/modules/crypto` and `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-011-secrets-envelope-encryption.md` for full design.

## Admin Portal

`apps/admin-portal` is a real, separately-deployed Next.js (React) + TypeScript application — not a route inside the Follower-facing `web` app (`docs/13-technology-stack.md` §13.3, a firm architecture decision) — with its own auth session, own Dockerfile/CI stage, and own Kustomize namespace/Ingress (`portal.nectrix.example.com`).

Auth is Next.js Server Actions setting `httpOnly` cookies, and `proxy.ts` (Next 16's renamed `middleware.ts` convention) verifies the access token's HS256 signature and `roles` claim **server-side**, using the same `JWT_SIGNING_SECRET` core-app signs with — genuine enforcement, not a client-side check a modified cookie could bypass. Verified hands-on with a real, valid `FOLLOWER`-role JWT: rejected with a real redirect to `/login`, not just hidden by the UI. The login page is a real two-step flow (email/password, then a separate 2FA-code screen only if the account actually has 2FA enabled — never both fields at once) with a password show/hide toggle.

Two new ADMIN/SUPPORT-gated backend routes back the real (non-stub) parts of the shell:

```
POST /api/v1/admin/users        (ADMIN only) {email, password, display_name, role: ADMIN|SUPPORT} -> 201 {id}
GET  /api/v1/admin/audit-log    (ADMIN/SUPPORT) ?actorUserId=&targetType=&targetId=&from=&to=&page=&pageSize= -> paginated real audit_log rows
```

`POST /api/v1/admin/users` is the platform's account-creation entry point — there is still no self-registration anywhere — and is the pattern Phase 1's `POST /api/v1/admin/masters` (deferred, needs `master_profiles`) will extend. Every other section (System Health, the full Users directory, Disputes) is a placeholder page at this phase, per the ticket's own scope; the shell's visual chrome (sidebar/topbar, colors, typography) is recreated from the `frontend-design` Claude Design handoff bundle's master-workspace mockup, not that mockup's Phase-1 feature pages.

Verified hands-on end-to-end, including through a real Docker image and a real local `kind` cluster deploy (probes, Service DNS, namespaces/Secrets all real): a `SUPPORT` session's provisioning attempt gets the real backend 403 surfaced in the UI, with zero row written to `users`; a freshly-provisioned `SUPPORT` account can immediately log in and reach the shell; the Audit Log viewer displays real rows, including ones generated by TICKET-006's own RBAC tests. A dev-only, real-login-capable seed account (`superadmin@nectrix.dev`, `make db-seed-dev`) exists for local QA — distinct from the pre-existing `admin@nectrix.dev` seed row, whose password hash has always been a non-functional placeholder.

See `apps/admin-portal/README.md` and `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-012-admin-portal-shell.md` for full design.
