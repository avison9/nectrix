# admin-portal

The Admin Portal — a genuinely separate Next.js (React) + TypeScript deployable from `web`, not a route inside it. Used by platform Admins/Support and by Masters (invitations, broker IB links, follower book, fee reports — once those land). Kept separate so its auth domain, session cookies, CORS policy, and network exposure can be independently controlled, and so a bug in one app's frontend build can never affect the other's availability.

This ticket (TICKET-001) only stands up the hello-world shell. The real shell — auth-gated routing, placeholder sections, the account-provisioning form, audit log viewer — is TICKET-012.

## Design references

- `nectrix_plan/docs/13-technology-stack.md` §13.3 — why a separate deployable, not a route in `web`.
- `nectrix_plan/docs/12-analytics-notifications-admin.md` §12.3 — Admin Portal features and RBAC-within-admin.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-012-admin-portal-shell.md` — the ticket that builds the real shell on top of this scaffold.

## Dependencies

- `@nectrix/domain-model` — shared TypeScript types (mirrors the Go/Java normalized domain types).
- `@nectrix/api-client` — shared API client (stub only, until real endpoints exist).

Module boundaries are enforced via `eslint-plugin-boundaries` (see root `eslint.config.mjs`) — this app may not import from `web` directly.

## Commands

Run from the repo root:

```
make ts-install   # npm install across the workspace
make ts-build     # turbo run build
make ts-lint      # turbo run lint
```

Or from this directory: `npm run dev` (port 3001).
