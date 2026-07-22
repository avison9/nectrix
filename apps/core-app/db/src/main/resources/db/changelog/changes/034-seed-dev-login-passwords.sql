--liquibase formatted sql

-- TICKET-122 follow-up — every one of this repo's dev-seed accounts now has a real,
-- working password (the same shared dev-only password, "Testing123") baked into the
-- migration itself, so a fresh docker volume wipe + `./gradlew :db:update
-- -PliquibaseContexts=dev` never again requires a manual live-DB fixup to log in as any of
-- them. 014-seed-dev-data.sql's admin@nectrix.dev/master@nectrix.dev/follower@nectrix.dev
-- and 017-seed-superadmin.sql's superadmin@nectrix.dev were seeded with either a
-- placeholder hash that can never verify, or (superadmin only) a real hash for a
-- DIFFERENT password ("Test123") -- both are UPDATEd here to the one shared password
-- instead, and two new accounts are added: support@nectrix.dev (SUPPORT role) and
-- individual@nectrix.dev (base USER role only, no MASTER/FOLLOWER -- an Individual-mode
-- account for exercising TICKET-122's tier-change-request flow end to end). context:dev on
-- every changeset below, same as every other seed file -- never applied to
-- staging/production. The hash itself is committed to the repo, same established
-- precedent 017-seed-superadmin.sql's own comment already set: a plain dev-only password,
-- not a real credential.
--changeset nectrix:034-seed-dev-login-passwords context:dev
UPDATE users SET password_hash = '$argon2id$v=19$m=19456,t=2,p=1$coI1iAJphllhjhIuP+7dVg$MITPLQGJaHYGTeXcxmIqhVi2ib9qKcMAlpdrJumV55k'
    WHERE id IN (
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000003',
        '00000000-0000-0000-0000-000000000099'
    );
--rollback UPDATE users SET password_hash = '$2a$10$devSeedPlaceholderHashOnly' WHERE id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003');

--changeset nectrix:034-seed-support-and-individual-users context:dev
INSERT INTO users (id, email, password_hash, display_name, status, created_by_user_id) VALUES
    ('00000000-0000-0000-0000-000000000004', 'support@nectrix.dev', '$argon2id$v=19$m=19456,t=2,p=1$coI1iAJphllhjhIuP+7dVg$MITPLQGJaHYGTeXcxmIqhVi2ib9qKcMAlpdrJumV55k', 'Dev Support', 'ACTIVE', '00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-000000000005', 'individual@nectrix.dev', '$argon2id$v=19$m=19456,t=2,p=1$coI1iAJphllhjhIuP+7dVg$MITPLQGJaHYGTeXcxmIqhVi2ib9qKcMAlpdrJumV55k', 'Dev Individual', 'ACTIVE', NULL);
--rollback DELETE FROM users WHERE id IN ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000005');

-- individual@nectrix.dev deliberately gets only USER (TICKET-114's own Individual-mode
-- self-registration grants nothing else -- see RegistrationService's Javadoc), so it can
-- exercise TICKET-122's tier-change-request flow end to end without any other seed already
-- granting it MASTER/FOLLOWER.
--changeset nectrix:034-seed-support-and-individual-roles context:dev
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000004', id FROM roles WHERE name = 'SUPPORT';
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000005', id FROM roles WHERE name = 'USER';
--rollback DELETE FROM user_roles WHERE user_id IN ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000005');
