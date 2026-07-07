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

## Auth & Identity (`modules/auth`, TICKET-005)

Login, session/refresh-token management, TOTP 2FA, and Google/Apple OAuth login. There is **no
public self-registration anywhere** — `POST /api/v1/auth/register` is not a mapped route at all (it
404s), by design (`docs/05-domain-model.md` §5.0). Every account is created internally via
`auth.api.UserProvisioningApi#createUser`, called by admin-provisioning/accept-invite tickets, never
by this module's own HTTP layer.

```
POST /api/v1/auth/login                      {email, password, totp_code?} -> {access_token, refresh_token, expires_in}
POST /api/v1/auth/oauth/{provider}/callback  {code} -> {access_token, refresh_token, expires_in}   (provider ∈ {google, apple})
POST /api/v1/auth/refresh                    {refresh_token} -> {access_token, refresh_token, expires_in}
POST /api/v1/auth/logout                     (authenticated; revokes the current session only)
POST /api/v1/auth/2fa/enable                 (authenticated) -> {secret, qr_code_uri}
POST /api/v1/auth/2fa/verify                 (authenticated) {totp_code} -> 204, flips two_factor_enabled=true
```

Access tokens are HS256 JWTs (~15 min TTL, `JWT_SIGNING_SECRET`). Refresh tokens are opaque, rotate
on every `/refresh` call, and use claim-and-rotate reuse detection: presenting an already-rotated
refresh token revokes **every** session for that user, not just the one presented (see
`AuthService#refresh`'s Javadoc for the exact race-safety mechanics). Rate limiting (Redis
INCR+EXPIRE, default 5 attempts / 15 min) applies to `/login` and `/2fa/verify`.

**OAuth status**: Google's code path is real (not a stub) and intended to get a live end-to-end test
once a real client exists, but that manual round-trip hasn't been run yet — set
`GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` in `.env` from a Google Cloud OAuth 2.0
web-app client (redirect URI `http://localhost:8080/api/v1/auth/oauth/google/callback`) to exercise
it. Apple's code path is implemented (same
generic `OidcIdTokenVerifier` machinery) but **not tested against Apple's real servers** — see
`AppleOAuthProvider`'s Javadoc for two known gaps that need resolving before it's activated for
real: Apple's `client_secret` must be a periodically-regenerated ES256 JWT (not a static string),
and Apple only includes the user's email on their *first* authorization, never on repeat logins.

2FA secret encryption uses a temporary local AES-GCM stub (`TWO_FACTOR_SECRET_ENCRYPTION_KEY`,
`StubAesGcmTwoFactorSecretCipher`) — must be replaced by TICKET-011's real KMS-backed envelope
encryption before any production credential flows through it (i.e. before Phase 1 broker linking).

## RBAC (TICKET-006)

Roles are additive, not exclusive (`user_roles` join table): `FOLLOWER`, `MASTER`, `PARTNER`,
`ADMIN`, `SUPPORT`. Two independent enforcement layers, per `docs/17-security-architecture.md`
§17.3:

- **Coarse, route-level** — plain `@PreAuthorize("hasRole(...)")`/`hasAnyRole(...)` on controller
  methods, using the `ROLE_*` authorities `auth.config.SecurityConfig`'s `JwtAuthenticationConverter`
  already derives from the access token's `roles` claim. No custom annotation.
- **Fine, object-level (IDOR prevention)** — `@PostAuthorize` on a service method, referencing
  `auth.security.SecurityPermissions` (bean name `perms`) by SpEL:
  `@PostAuthorize("@perms.isOwnerOrStaff(authentication, returnObject.userId())")`. This is a
  *runtime* bean-name lookup, not a compile-time import, so it works across module boundaries
  without violating `ModuleBoundaryArchTest`. See `invitations.service.BrokerAccountService` for the
  reference implementation — reuse this exact pattern for any future per-user-owned resource
  (`CopyRelationship`, `Invitation`, `BrokerIBLink`, `BrokerFeeReport`, ...). Reads only — a future
  write endpoint needs an explicit imperative guard instead (see that class's Javadoc for why).

Demo endpoints (real role/ownership enforcement, but not real features — `BrokerAccount` linking and
`performance_fee_ledger` logic are Phase 1):

```
GET  /api/v1/broker-accounts/{id}          (authenticated; owner or ADMIN/SUPPORT only — 403/404 otherwise)
POST /api/v1/admin/impersonate/{userId}    (ADMIN/SUPPORT) -> {access_token, expires_in}, JWT tagged impersonated_by=<acting admin/support id>
POST /api/v1/admin/ledger-adjustments      (ADMIN only — SUPPORT gets 403) {target_type, target_id, amount, reason} -> 204
GET  /api/v1/admin/broker-accounts/{id}    (ADMIN/SUPPORT — bypasses ownership) -> same shape as the Follower-facing route
```

Every admin/support action above writes one `audit_log` row (`docs/17-security-architecture.md`
§17.6 — write-only at the app-DB-role level).

Role management is a CLI at this phase (a full admin-portal UI is TICKET-012):

```
make role-grant EMAIL=foo@example.com ROLE=ADMIN
make role-revoke EMAIL=foo@example.com ROLE=ADMIN
make role-list EMAIL=foo@example.com
```

## Commands

Run from the repo root (see root `README.md` for the devcontainer setup):

```
make core-app-build   # ./gradlew build
make core-app-test    # ./gradlew test — includes the ArchUnit boundary checks
```

`bootstrap` exposes `GET /hello` on port 8080 once running (`./gradlew :bootstrap:bootRun`). Auth
integration tests (`./gradlew :bootstrap:integrationTest`) need Postgres + Redis running (see root
`docker-compose.yml`) and `JWT_SIGNING_SECRET`/`TWO_FACTOR_SECRET_ENCRYPTION_KEY` set — same
no-default pattern as `POSTGRES_APP_PASSWORD` (see `.env.example`).
