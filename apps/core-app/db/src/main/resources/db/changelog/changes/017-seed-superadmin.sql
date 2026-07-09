--liquibase formatted sql

-- Dev-only, real-login-capable superadmin (context:dev, same as
-- 014-seed-dev-data.sql — never applied to staging/production). Distinct
-- from that file's admin@nectrix.dev, whose password_hash is a placeholder
-- that can never actually verify (`$2a$10$devSeedPlaceholderHashOnly` isn't
-- a real bcrypt/argon2 hash of anything) — this one's password_hash is a
-- genuine Argon2id hash (generated for real via PasswordService, the same
-- Argon2PasswordEncoder core-app verifies logins with), so
-- superadmin@nectrix.dev / Test123 actually logs in after `make
-- db-seed-dev`. A plain dev-only password, not a real credential — this
-- hash is committed to the repo, so it must never be reused anywhere real.
-- Fixed UUID (not gen_random_uuid()) so it's stable across every reseed,
-- and so rollback deletes precisely this row.
--changeset nectrix:017-seed-superadmin-user context:dev
INSERT INTO users (id, email, password_hash, display_name, status, created_by_user_id) VALUES
    ('00000000-0000-0000-0000-000000000099', 'superadmin@nectrix.dev', '$argon2id$v=19$m=19456,t=2,p=1$yFGJNd2W6qmteS4PB6dpBw$tXNCEHkyFt0XCV9PeusITppdqdjADgZFzHaSwLMIHko', 'Dev Superadmin', 'ACTIVE', NULL);
--rollback DELETE FROM users WHERE id = '00000000-0000-0000-0000-000000000099';

--changeset nectrix:017-seed-superadmin-role context:dev
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000099', id FROM roles WHERE name = 'ADMIN';
--rollback DELETE FROM user_roles WHERE user_id = '00000000-0000-0000-0000-000000000099';
