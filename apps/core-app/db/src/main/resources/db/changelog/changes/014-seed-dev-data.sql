--liquibase formatted sql

-- Dev-only synthetic data for manual QA (TICKET-004 AC4) — never applied to
-- staging/production (context:dev on every changeset below, activated via
-- `make db-seed-dev` / `-PliquibaseContexts=dev`). Fixed UUIDs (not
-- gen_random_uuid()) so they're stable/known across every seed run, and so
-- the rollback can delete precisely these rows, nothing else.
--changeset nectrix:014-seed-users context:dev
INSERT INTO users (id, email, password_hash, display_name, status, created_by_user_id) VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@nectrix.dev',    '$2a$10$devSeedPlaceholderHashOnly', 'Dev Admin',    'ACTIVE', NULL),
    ('00000000-0000-0000-0000-000000000002', 'master@nectrix.dev',   '$2a$10$devSeedPlaceholderHashOnly', 'Dev Master',   'ACTIVE', '00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-000000000003', 'follower@nectrix.dev', '$2a$10$devSeedPlaceholderHashOnly', 'Dev Follower', 'ACTIVE', '00000000-0000-0000-0000-000000000001');
--rollback DELETE FROM users WHERE id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003');

--changeset nectrix:014-seed-user-roles context:dev
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000001', id FROM roles WHERE name = 'ADMIN';
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000002', id FROM roles WHERE name = 'MASTER';
INSERT INTO user_roles (user_id, role_id)
    SELECT '00000000-0000-0000-0000-000000000003', id FROM roles WHERE name = 'FOLLOWER';
--rollback DELETE FROM user_roles WHERE user_id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003');

--changeset nectrix:014-seed-broker-accounts context:dev
INSERT INTO broker_accounts (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency, server_name, connection_role, credentials_ciphertext, credentials_key_version, connection_status) VALUES
    ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000002', 'CTRADER', 'demo-master-001',   'Master demo account',   TRUE, 'USD', 'Demo-Server-1', 'MASTER_ONLY',   '\x00', 1, 'CONNECTED'),
    ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000003', 'CTRADER', 'demo-follower-001', 'Follower demo account', TRUE, 'USD', 'Demo-Server-1', 'FOLLOWER_ONLY', '\x00', 1, 'CONNECTED');
--rollback DELETE FROM broker_accounts WHERE id IN ('00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000011');

--changeset nectrix:014-seed-master-profile context:dev
INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name, bio, is_public) VALUES
    ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000010', 'Dev Master', 'Seeded master profile for local QA', TRUE);
--rollback DELETE FROM master_profiles WHERE id = '00000000-0000-0000-0000-000000000020';

--changeset nectrix:014-seed-mm-and-risk-profiles context:dev
INSERT INTO money_management_profiles (id, method, multiplier, rounding_mode) VALUES
    ('00000000-0000-0000-0000-000000000030', 'MULTIPLIER', 1.0, 'DOWN');
INSERT INTO risk_profiles (id, max_lot_per_trade, max_open_positions, max_slippage_pips) VALUES
    ('00000000-0000-0000-0000-000000000031', 5.0, 20, 5);
--rollback DELETE FROM risk_profiles WHERE id = '00000000-0000-0000-0000-000000000031';
--rollback DELETE FROM money_management_profiles WHERE id = '00000000-0000-0000-0000-000000000030';

--changeset nectrix:014-seed-follow-request context:dev
INSERT INTO follow_requests (id, follower_user_id, master_profile_id, follower_broker_account_id, proposed_money_management_profile_id, proposed_risk_profile_id, status, decided_by_user_id, decided_at) VALUES
    ('00000000-0000-0000-0000-000000000040', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031', 'APPROVED', '00000000-0000-0000-0000-000000000002', now());
--rollback DELETE FROM follow_requests WHERE id = '00000000-0000-0000-0000-000000000040';

--changeset nectrix:014-seed-copy-relationship context:dev
INSERT INTO copy_relationships (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id, money_management_profile_id, risk_profile_id, status, performance_fee_percent, fee_collection_method, originating_follow_request_id) VALUES
    ('00000000-0000-0000-0000-000000000050', '00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031', 'ACTIVE', 20.00, 'BROKER_PARTNERSHIP', '00000000-0000-0000-0000-000000000040');
--rollback DELETE FROM copy_relationships WHERE id = '00000000-0000-0000-0000-000000000050';
