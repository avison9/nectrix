--liquibase formatted sql

-- Extensions used throughout the schema (docs/06-database-schema.md §6.2).
--changeset nectrix:001-extension-pgcrypto
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
--rollback DROP EXTENSION IF EXISTS "pgcrypto";

--changeset nectrix:001-extension-citext
CREATE EXTENSION IF NOT EXISTS "citext";
--rollback DROP EXTENSION IF EXISTS "citext";

-- The restricted runtime role core-app's Spring datasource actually connects
-- as (docs/17-security-architecture.md §17.6 — no UPDATE/DELETE on audit_log
-- for this role). Created here, early, deliberately with zero privileges yet
-- — 013-app-role-grants.sql grants everything once every table exists. This
-- ordering matters when undoing everything: DROP ROLE (below) only succeeds
-- once a full undo has already reached and reverted 013's grants first,
-- since undo always runs in strict reverse-chronological order across the
-- whole changelog (013 was applied after 001, so it gets undone before 001).
--changeset nectrix:001-create-app-role
CREATE ROLE nectrix_app WITH LOGIN PASSWORD '${appRolePassword}';
--rollback DROP ROLE nectrix_app;
