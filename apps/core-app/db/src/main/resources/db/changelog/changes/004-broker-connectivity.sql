--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Broker Connectivity". broker_ib_links'
-- master_profile_id is a forward reference (master_profiles is defined in
-- 005-social-marketplace.sql) — FK added in 011-deferred-foreign-keys.sql.
--changeset nectrix:004-broker-ib-links
CREATE TABLE broker_ib_links (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_profile_id     UUID NOT NULL,
    broker_type           TEXT NOT NULL CHECK (broker_type IN ('CTRADER','MT5')),
    broker_display_name   TEXT NOT NULL,
    ib_referral_url_or_code TEXT NOT NULL,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_broker_ib_links_master ON broker_ib_links(master_profile_id) WHERE is_active = TRUE;
--rollback DROP TABLE IF EXISTS broker_ib_links CASCADE;

--changeset nectrix:004-broker-accounts
CREATE TABLE broker_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    broker_type         TEXT NOT NULL CHECK (broker_type IN ('CTRADER','MT5')),
    broker_account_login TEXT NOT NULL,
    display_label       TEXT,
    is_demo             BOOLEAN NOT NULL DEFAULT FALSE,
    currency            TEXT NOT NULL,
    server_name         TEXT,
    connection_role      TEXT NOT NULL DEFAULT 'BOTH'
                            CHECK (connection_role IN ('MASTER_ONLY','FOLLOWER_ONLY','BOTH')),
    opened_via_ib_link_id UUID REFERENCES broker_ib_links(id),
    credentials_ciphertext BYTEA NOT NULL,
    credentials_key_version SMALLINT NOT NULL,
    connection_status    TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (connection_status IN ('PENDING','CONNECTED','DEGRADED','DISCONNECTED','REAUTH_REQUIRED')),
    last_health_check_at TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (broker_type, broker_account_login, server_name)
);
CREATE INDEX idx_broker_accounts_user ON broker_accounts(user_id);
CREATE INDEX idx_broker_accounts_status ON broker_accounts(connection_status);
--rollback DROP TABLE IF EXISTS broker_accounts CASCADE;

--changeset nectrix:004-account-snapshots
CREATE TABLE account_snapshots (
    id               BIGSERIAL PRIMARY KEY,
    broker_account_id UUID NOT NULL REFERENCES broker_accounts(id) ON DELETE CASCADE,
    balance          NUMERIC(20,4) NOT NULL,
    equity           NUMERIC(20,4) NOT NULL,
    used_margin      NUMERIC(20,4) NOT NULL,
    free_margin      NUMERIC(20,4) NOT NULL,
    margin_level_pct NUMERIC(10,4),
    captured_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- High write volume: partition by month, or move to ClickHouse, once volumes
-- warrant it — docs/06-database-schema.md §6.3 (deliberately deferred, out of
-- scope for this ticket).
CREATE INDEX idx_account_snapshots_account_time ON account_snapshots(broker_account_id, captured_at DESC);
--rollback DROP TABLE IF EXISTS account_snapshots CASCADE;

--changeset nectrix:004-symbol-mappings
CREATE TABLE symbol_mappings (
    id                 BIGSERIAL PRIMARY KEY,
    broker_account_id  UUID NOT NULL REFERENCES broker_accounts(id) ON DELETE CASCADE,
    canonical_symbol   TEXT NOT NULL,
    broker_symbol_name TEXT NOT NULL,
    contract_size      NUMERIC(20,4) NOT NULL,
    lot_step           NUMERIC(10,4) NOT NULL,
    min_lot            NUMERIC(10,4) NOT NULL,
    max_lot            NUMERIC(10,4) NOT NULL,
    pip_size           NUMERIC(12,8) NOT NULL,
    digits             SMALLINT NOT NULL,
    margin_currency    TEXT NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (broker_account_id, canonical_symbol)
);
--rollback DROP TABLE IF EXISTS symbol_mappings CASCADE;
