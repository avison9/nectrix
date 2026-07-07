--liquibase formatted sql

-- Reference data, not dev-only test data — no context restriction. TICKET-006
-- (RBAC framework) depends on these 5 rows already existing.
--changeset nectrix:012-seed-roles
INSERT INTO roles (name) VALUES ('FOLLOWER'), ('MASTER'), ('PARTNER'), ('ADMIN'), ('SUPPORT');
--rollback DELETE FROM roles WHERE name IN ('FOLLOWER','MASTER','PARTNER','ADMIN','SUPPORT');
