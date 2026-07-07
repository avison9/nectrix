# db

Liquibase migrations for the full schema in `nectrix_plan/docs/06-database-schema.md` (TICKET-004). A **separate Gradle subproject** from `bootstrap` — not bolted onto the running app — so `liquibase-core`/the Gradle plugin never appear on `bootstrap`'s own classpath. That matters for one specific reason: `bootstrap`'s Spring datasource connects as the restricted `nectrix_app` role, which has no DDL privileges at all; migrations are always run separately, by this subproject, as the `nectrix` superuser.

## Why Liquibase, not Flyway

Flyway is the more common Spring Boot default, but Flyway Community has no free per-changeset rollback (`flyway undo` is a paid Teams feature). Liquibase Community supports real rollback for free, which is what TICKET-004's AC1 ("migrate up / migrate down work cleanly and repeatably") actually requires — proven hands-on: a full `update` → `rollbackCount` (all changesets) → `update` cycle, verified against a real local Postgres, not a "drop everything and start over" shortcut.

## Layout

```
src/main/resources/db/changelog/
├── db.changelog-master.yaml   # includeAll over changes/, alphabetical (numeric prefixes control order)
└── changes/
    ├── 001-extensions-and-roles.sql   # pgcrypto, citext, CREATE ROLE nectrix_app (empty, granted later)
    ├── 002-identity-access.sql        # users, roles, user_roles, sessions
    ├── 003-invitations-onboarding.sql # invitations, follow_requests (forward-ref columns, FKs deferred)
    ├── 004-broker-connectivity.sql    # broker_ib_links, broker_accounts, account_snapshots, symbol_mappings
    ├── 005-social-marketplace.sql     # master_profiles, reviews, leaderboard_snapshots
    ├── 006-copy-trading.sql           # money_management_profiles, risk_profiles, copy_relationships, trade_signals, copied_trades, high_water_mark_history
    ├── 007-billing.sql                # management_agreements, subscriptions, performance_fee_ledger, broker_fee_reports, broker_fee_report_lines, invoices
    ├── 008-partner-ib.sql             # partners, referral_attributions, commission_ledger
    ├── 009-notifications.sql          # notification_preferences, notification_log
    ├── 010-audit.sql                  # audit_log
    ├── 011-deferred-foreign-keys.sql  # the forward references from 003/004, now addable
    ├── 012-seed-roles.sql             # FOLLOWER/MASTER/PARTNER/ADMIN/SUPPORT — reference data, every environment
    ├── 013-app-role-grants.sql        # broad GRANT to nectrix_app + REVOKE UPDATE/DELETE on audit_log — runs last
    └── 014-seed-dev-data.sql          # context=dev only — synthetic users/broker-accounts/master-profile for manual QA
```

Each SQL file uses Liquibase's SQL-formatted-changelog style (`--liquibase formatted sql`, `--changeset author:id`, `--rollback ...`) — one changeset per schema object (one `CREATE TABLE` = one changeset), not one changeset per file, so `rollbackCount` can undo precisely. Rollback always runs in strict reverse-chronological order across the *entire* changelog, independent of which file a changeset lives in — so `nectrix_app` (created in 001) rolling back last, after 013's grants are already reverted, is automatic, not something each file needs to reason about.

**Watch out for one real gotcha if you edit these files**: a prose comment line that happens to start with `-- changeset` or `-- rollback` (note the space) after a line-wrap gets misparsed by Liquibase as a malformed directive. Keep multi-line prose comments from ever starting a line with those two words.

## Commands

All run via the root `Makefile` (which execs into the devcontainer, since these need the same Java/Gradle toolchain as `bootstrap`):

```
make db-migrate       # ./gradlew :db:update
make db-migrate-down  # ./gradlew :db:rollbackCount -PliquibaseCount=<total>
make db-seed-dev      # ./gradlew :db:update -PliquibaseContexts=dev
make db-status        # ./gradlew :db:status
```

Requires `POSTGRES_APP_PASSWORD` in your `.env` (root `.env.example`) — substituted into the `CREATE ROLE nectrix_app WITH LOGIN PASSWORD '${appRolePassword}'` changeset via Liquibase's `changelogParameters`.

## Design references

- `nectrix_plan/docs/06-database-schema.md` — the full DDL this implements, verbatim.
- `nectrix_plan/docs/17-security-architecture.md` §17.6 — why `audit_log` gets no UPDATE/DELETE grant.
- `nectrix_plan/phases/phase-0-foundation/tickets/TICKET-004-database-schema-migrations.md` — the ticket this satisfies.
- `apps/core-app/bootstrap/src/test/java/com/nectrix/coreapp/infra/SchemaConstraintsIntegrationTest.java` — real, hands-on proof of every constraint class (CHECK, FK, UNIQUE) and the audit_log write restriction.
