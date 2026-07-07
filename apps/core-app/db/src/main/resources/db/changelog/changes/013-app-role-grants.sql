--liquibase formatted sql

-- Grants for the nectrix_app runtime role (created empty, in
-- 001-extensions-and-roles.sql). Must run last, after every table exists.
-- audit_log is deliberately narrower than everything else — INSERT+SELECT
-- only, no UPDATE/DELETE — per docs/17-security-architecture.md §17.6.
-- ALTER DEFAULT PRIVILEGES so tables added by later tickets' migrations
-- automatically extend the same grants without a repeated statement here.
--changeset nectrix:013-app-role-grants
GRANT USAGE ON SCHEMA public TO nectrix_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nectrix_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nectrix_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nectrix_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO nectrix_app;

REVOKE UPDATE, DELETE ON audit_log FROM nectrix_app;

--rollback ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE USAGE, SELECT ON SEQUENCES FROM nectrix_app;
--rollback ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM nectrix_app;
--rollback REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM nectrix_app;
--rollback REVOKE ALL ON ALL TABLES IN SCHEMA public FROM nectrix_app;
--rollback REVOKE USAGE ON SCHEMA public FROM nectrix_app;
