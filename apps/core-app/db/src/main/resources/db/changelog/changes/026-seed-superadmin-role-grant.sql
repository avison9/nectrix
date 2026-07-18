--liquibase formatted sql

-- 017-seed-superadmin.sql predates migration 022's SUPER_ADMIN role (it only granted the
-- pre-existing ADMIN role) -- never updated, so the one real-login-capable dev account
-- ("Dev Superadmin", superadmin@nectrix.dev) was never actually SUPER_ADMIN, just ADMIN. This is
-- a new, separate entry rather than editing 017 -- already-applied Liquibase entries are never
-- modified. context:dev, same as 017/014 -- never applied to staging/production.
--changeset nectrix:026-seed-superadmin-role-grant context:dev
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000099', id FROM roles WHERE name = 'SUPER_ADMIN';
--rollback DELETE FROM user_roles WHERE user_id = '00000000-0000-0000-0000-000000000099' AND role_id = (SELECT id FROM roles WHERE name = 'SUPER_ADMIN');
