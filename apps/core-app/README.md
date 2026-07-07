# core-app

The Core App — a Java 21 + Spring Boot modular monolith covering Auth, Onboarding/Invitations, Social/Marketplace, Billing, Admin, Analytics-read, and Notifications. Each bounded context lives in its own Gradle module under `modules/`, with a hard rule (enforced by ArchUnit in `bootstrap`) that no module may import another module's `repository` or `domain` package directly — only its `api` package, or the event bus.

## Layout

- `bootstrap/` — the Spring Boot entrypoint (`CoreAppApplication`), wiring all 7 modules together. Also owns the ArchUnit module-boundary tests and the schema/audit-log integration tests (`src/test/java/.../infra/`). Connects to Postgres as the restricted `nectrix_app` role only — never runs migrations itself.
- `modules/{auth,invitations,social,billing,admin,analytics,notifications}/` — one bounded context per module, each with `api/` (published surface), `domain/`, and `repository/` packages.
- `archunit-fixtures/` — a deliberately-violating fixture module (outside `com.nectrix.coreapp`) that `ModuleBoundaryRuleSelfTest` checks against, proving the ArchUnit rule actually fires rather than trusting an unverified assertion.
- `db/` — Liquibase migrations for the full schema (see "Database & migrations" below). Deliberately a separate Gradle subproject from `bootstrap`, so `liquibase-core` never ends up on the running app's classpath.

## Design references

- `nectrix_plan/docs/04-architecture-overview.md` — module boundary rules, bounded contexts.
- `nectrix_plan/docs/05-domain-model.md` — the domain model each module implements.
- `nectrix_plan/docs/06-database-schema.md` — the full schema `db/` implements.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Java/Spring Boot.
- `nectrix_plan/docs/17-security-architecture.md` §17.6 — why `audit_log` has no UPDATE/DELETE grant for the app role.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-001-repo-scaffolding.md` — the ticket this scaffolding satisfies.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-004-database-schema-migrations.md` — the ticket that added `db/` and the datasource wiring.

## Database & migrations

Full schema from `docs/06-database-schema.md` (31 tables), applied via **Liquibase** SQL-formatted changelogs in `db/src/main/resources/db/changelog/changes/` — chosen over Flyway specifically because Liquibase Community supports real per-changeset rollback for free (Flyway's `undo` is a paid Teams feature).

Two Postgres roles:
- **`nectrix`** (the existing superuser) — runs all migrations. Never used by the running app.
- **`nectrix_app`** (created by migration `001`, granted by `013`) — what `bootstrap`'s own `spring.datasource.*` connects as. Full CRUD everywhere except `audit_log`, which is INSERT+SELECT only (no UPDATE/DELETE) — `docs/17-security-architecture.md` §17.6.

```
make db-migrate       # apply all pending changesets (schema + reference data)
make db-migrate-down  # real rollback of every changeset (not clean+reapply)
make db-seed-dev      # additionally apply dev-only synthetic data (context=dev — never staging/production)
make db-status        # list pending changesets
```

Requires `POSTGRES_APP_PASSWORD` set in your `.env` (see root `.env.example`) — same no-default pattern as `POSTGRES_PASSWORD`.

## Container image

`Dockerfile` here is multi-stage (`eclipse-temurin:21-jdk-jammy` build → `eclipse-temurin:21-jre-jammy` runtime, non-root). **Build context must be the repo root**, not this directory, since the build stage needs `packages/event-contracts/java` (pulled in via `includeBuild`) as sibling source:

```
docker build -f apps/core-app/Dockerfile -t core-app .
```

CI builds, Trivy-scans (`CRITICAL,HIGH` gated), and pushes this to `ghcr.io/avison9/nectrix/core-app:<commit-sha>` on every merge to `main` — see the root README's CI/CD section. Deployed via `deploy/base/core-app/` (Kustomize), in its own `core-app` namespace.

## Commands

Run from the repo root (see root `README.md` for the devcontainer setup):

```
make core-app-build   # ./gradlew build
make core-app-test    # ./gradlew test — includes the ArchUnit boundary checks
```

`bootstrap` exposes `GET /hello` on port 8080 once running (`./gradlew :bootstrap:bootRun`).
