# core-app

The Core App — a Java 21 + Spring Boot modular monolith covering Auth, Onboarding/Invitations, Social/Marketplace, Billing, Admin, Analytics-read, and Notifications. Each bounded context lives in its own Gradle module under `modules/`, with a hard rule (enforced by ArchUnit in `bootstrap`) that no module may import another module's `repository` or `domain` package directly — only its `api` package, or the event bus.

## Layout

- `bootstrap/` — the Spring Boot entrypoint (`CoreAppApplication`), wiring all 7 modules together. Also owns the ArchUnit module-boundary tests.
- `modules/{auth,invitations,social,billing,admin,analytics,notifications}/` — one bounded context per module, each with `api/` (published surface), `domain/`, and `repository/` packages.
- `archunit-fixtures/` — a deliberately-violating fixture module (outside `com.nectrix.coreapp`) that `ModuleBoundaryRuleSelfTest` checks against, proving the ArchUnit rule actually fires rather than trusting an unverified assertion.

## Design references

- `nectrix_plan/docs/04-architecture-overview.md` — module boundary rules, bounded contexts.
- `nectrix_plan/docs/05-domain-model.md` — the domain model each module implements.
- `nectrix_plan/docs/13-technology-stack.md` §13.1 — why Java/Spring Boot.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-001-repo-scaffolding.md` — the ticket this scaffolding satisfies.

## Commands

Run from the repo root (see root `README.md` for the devcontainer setup):

```
make core-app-build   # ./gradlew build
make core-app-test    # ./gradlew test — includes the ArchUnit boundary checks
```

`bootstrap` exposes `GET /hello` on port 8080 once running (`./gradlew :bootstrap:bootRun`).
