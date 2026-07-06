# web

The Follower-facing client — a Next.js (React) + TypeScript + Tailwind app. Serves the (public) leaderboard/master-profile marketing pages via SSR and the authenticated follower dashboard via CSR, responsive across desktop/tablet/mobile-web. No public registration route exists here — the only entry points are an invite-acceptance page and login, once auth lands (TICKET-005).

## Design references

- `nectrix_plan/docs/13-technology-stack.md` §13.3 — why Next.js, and the split from `admin-portal`.
- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` — invite-acceptance/login flows this app will implement.
- `nectrix_plan/docs/10-portfolio-social-trading.md` — leaderboard/master-profile pages.

## Dependencies

- `@nectrix/domain-model` — shared TypeScript types (mirrors the Go/Java normalized domain types).
- `@nectrix/api-client` — shared API client (stub only, until real endpoints exist).

Module boundaries are enforced via `eslint-plugin-boundaries` (see root `eslint.config.mjs`) — this app may not import from `admin-portal` directly.

## Commands

Run from the repo root:

```
make ts-install   # npm install across the workspace
make ts-build     # turbo run build
make ts-lint      # turbo run lint
```

Or from this directory: `npm run dev` (port 3000).
