# admin-portal

The Admin Portal — a genuinely separate Next.js (React) + TypeScript deployable from `web`, not a route inside it. Used by platform Admins/Support and, once Phase 1's Master-scoped views land, by Masters too (invitations, broker IB links, follower book, fee reports). Kept separate so its auth domain, session cookies, CORS policy, and network exposure can be independently controlled, and so a bug in one app's frontend build can never affect the other's availability.

TICKET-012 is done — this is the real shell (auth-gated routing, nav, the account-provisioning form, the Audit Log viewer), not the TICKET-001 hello-world scaffold it started as.

## Auth

`proxy.ts` (Next 16's renamed `middleware.ts` convention) is the real gate: it verifies the `access_token` cookie's HS256 signature and `roles` claim **server-side**, using the same `JWT_SIGNING_SECRET` core-app signs with (`lib/session.ts`, via `jose`) — the secret never reaches client JS, and a modified/forged cookie is rejected before any protected page renders. Only `ADMIN`/`SUPPORT`/`MASTER` roles reach past `/login`.

Login (`app/login/`) is a real two-step flow, not one form with every field shown up front: email + password first; if the account has 2FA enabled, a second screen asks for just the code (the email/password fields aren't shown again). A password show/hide toggle sits inside the password field. `app/login/actions.ts`'s Server Actions call core-app's `/api/v1/auth/login` server-side and set `httpOnly` cookies — the browser only ever talks to this app's own server, never core-app directly.

## Pages

- `/audit-log` — real, working viewer: paginated/filterable real rows from `GET /api/v1/admin/audit-log`.
- `/users` — real account-provisioning form (`POST /api/v1/admin/users`, ADMIN-only, creates an Admin or Support account with a role assigned at creation — the platform's only account-creation entry point, since there is no self-registration anywhere) plus a placeholder for the full user directory (Phase 1).
- `/system-health`, `/disputes` — placeholder pages per the ticket's own scope; Phase 1 fills these in.

Shell chrome (sidebar/topbar, color/typography tokens) is recreated from the `frontend-design` Claude Design handoff bundle's master-workspace mockup — its Phase-1 feature pages (KPI dashboards, invitations, IB links, ...) are deliberately not implemented here yet.

## Design references

- `nectrix_plan/docs/13-technology-stack.md` §13.3 — why a separate deployable, not a route in `web`.
- `nectrix_plan/docs/12-analytics-notifications-admin.md` §12.3 — Admin Portal features and RBAC-within-admin.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-012-admin-portal-shell.md` — this ticket.

## Dependencies

- `@nectrix/domain-model` — shared TypeScript types (mirrors the Go/Java normalized domain types), including `AdminPortalRole`/`AuditLogEntry`/`AuditLogPage`.
- `@nectrix/api-client` — shared HTTP client (`login`, `refreshSession`, `createAdminUser`, `listAuditLog`) — server-side only, since it carries the bearer token. First built out for real by this ticket.

Module boundaries are enforced via `eslint-plugin-boundaries` (see root `eslint.config.mjs`) — this app may not import from `web` directly.

## Environment

`CORE_APP_BASE_URL` and `JWT_SIGNING_SECRET` are required server-side env vars (server-only — never `NEXT_PUBLIC_*`). Local dev already has both set via `.devcontainer/docker-compose.yml`. `core-app` itself has to be running (`./gradlew :bootstrap:bootRun` from `apps/core-app`, inside the devcontainer) for anything here to work — it is not a docker-compose service, so it doesn't survive a devcontainer restart on its own.

A dev-only, real-login-capable superadmin (`superadmin@nectrix.dev`, `make db-seed-dev`) exists for local QA — distinct from `apps/core-app`'s pre-existing `admin@nectrix.dev` dev-seed row, whose password hash is a non-functional placeholder that can never log in.

## Container image

`Dockerfile` here is multi-stage (`node:22-alpine` build → `node:22-alpine` runtime, non-root, Next's `output: "standalone"` build). **Build context must be the repo root**, not this directory — npm workspaces resolve `@nectrix/domain-model`/`@nectrix/api-client` via the root `package-lock.json`:

```
docker build -f apps/admin-portal/Dockerfile -t admin-portal .
```

CI builds, Trivy-scans, and pushes this to `ghcr.io/avison9/nectrix/admin-portal:<commit-sha>` on every merge to `main` — see the root README's CI/CD section. Deployed via `deploy/base/admin-portal/` (Kustomize), in its own `admin-portal` namespace, reachable at `portal.nectrix.example.com`.

## Commands

Run from the repo root:

```
make ts-install   # npm install across the workspace
make ts-build     # turbo run build
make ts-lint      # turbo run lint
```

Or from this directory: `npm run dev` (port 3001).
