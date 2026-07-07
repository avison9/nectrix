--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Copy Trading".
--changeset nectrix:006-money-management-profiles
CREATE TABLE money_management_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    method          TEXT NOT NULL CHECK (method IN
                        ('FIXED_LOT','PROPORTIONAL_EQUITY','PROPORTIONAL_BALANCE',
                         'RISK_PERCENT','MULTIPLIER','CUSTOM_FORMULA')),
    fixed_lot_size       NUMERIC(10,4),
    multiplier           NUMERIC(10,4),
    risk_percent         NUMERIC(6,3),
    custom_formula_expr  TEXT,
    rounding_mode        TEXT NOT NULL DEFAULT 'DOWN' CHECK (rounding_mode IN ('DOWN','NEAREST','UP')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS money_management_profiles CASCADE;

--changeset nectrix:006-risk-profiles
CREATE TABLE risk_profiles (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    max_lot_per_trade        NUMERIC(10,4),
    max_open_positions       INTEGER,
    max_exposure_per_symbol_lots NUMERIC(10,4),
    max_total_exposure_lots  NUMERIC(10,4),
    max_slippage_pips        NUMERIC(8,2) NOT NULL DEFAULT 5,
    drawdown_pause_pct       NUMERIC(6,2),
    drawdown_close_all_pct   NUMERIC(6,2),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS risk_profiles CASCADE;

--changeset nectrix:006-copy-relationships
CREATE TABLE copy_relationships (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_profile_id         UUID NOT NULL REFERENCES master_profiles(id),
    master_broker_account_id  UUID NOT NULL REFERENCES broker_accounts(id),
    follower_user_id          UUID NOT NULL REFERENCES users(id),
    follower_broker_account_id UUID NOT NULL REFERENCES broker_accounts(id),
    money_management_profile_id UUID NOT NULL REFERENCES money_management_profiles(id),
    risk_profile_id           UUID NOT NULL REFERENCES risk_profiles(id),
    status                    TEXT NOT NULL DEFAULT 'PENDING_RISK_ACK'
                                CHECK (status IN ('PENDING_RISK_ACK','PENDING_AGREEMENT','ACTIVE','PAUSED','STOPPED')),
    copy_direction             TEXT NOT NULL DEFAULT 'SAME' CHECK (copy_direction IN ('SAME','REVERSE')),
    performance_fee_percent    NUMERIC(5,2) NOT NULL,
    fee_collection_method      TEXT NOT NULL CHECK (fee_collection_method IN ('STRIPE_INVOICE','BROKER_PARTNERSHIP')),
    high_water_mark            NUMERIC(20,4) NOT NULL DEFAULT 0,
    risk_ack_at                TIMESTAMPTZ,
    originating_invitation_id UUID REFERENCES invitations(id),
    originating_follow_request_id UUID REFERENCES follow_requests(id),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    stopped_at                 TIMESTAMPTZ,
    CONSTRAINT chk_no_self_copy CHECK (master_broker_account_id <> follower_broker_account_id),
    CONSTRAINT chk_exactly_one_origin CHECK (
        (originating_invitation_id IS NOT NULL)::int + (originating_follow_request_id IS NOT NULL)::int = 1
    )
);
CREATE INDEX idx_copyrel_master ON copy_relationships(master_broker_account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_copyrel_follower ON copy_relationships(follower_user_id);
--rollback DROP TABLE IF EXISTS copy_relationships CASCADE;

--changeset nectrix:006-trade-signals
CREATE TABLE trade_signals (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_broker_account_id UUID NOT NULL REFERENCES broker_accounts(id),
    broker_position_id    TEXT NOT NULL,
    event_type            TEXT NOT NULL CHECK (event_type IN
                            ('POSITION_OPENED','POSITION_MODIFIED','POSITION_PARTIALLY_CLOSED','POSITION_CLOSED')),
    canonical_symbol       TEXT NOT NULL,
    direction              TEXT NOT NULL CHECK (direction IN ('BUY','SELL')),
    volume_lots            NUMERIC(12,4),
    closed_volume_lots     NUMERIC(12,4),
    fill_price             NUMERIC(20,8),
    sl_price               NUMERIC(20,8),
    tp_price               NUMERIC(20,8),
    server_timestamp       TIMESTAMPTZ NOT NULL,
    received_at_gateway    TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_payload            JSONB NOT NULL,
    UNIQUE (master_broker_account_id, broker_position_id, event_type, server_timestamp)
);
-- High write volume: partition by month once volumes warrant it — §6.3
-- (deliberately deferred, out of scope for this ticket).
CREATE INDEX idx_tradesignals_master_time ON trade_signals(master_broker_account_id, received_at_gateway DESC);
--rollback DROP TABLE IF EXISTS trade_signals CASCADE;

--changeset nectrix:006-copied-trades
CREATE TABLE copied_trades (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    copy_relationship_id   UUID NOT NULL REFERENCES copy_relationships(id),
    trade_signal_id        UUID NOT NULL REFERENCES trade_signals(id),
    idempotency_key        TEXT NOT NULL,
    follower_broker_position_id TEXT,
    status                 TEXT NOT NULL CHECK (status IN
                             ('PENDING','SUBMITTED','FILLED','PARTIALLY_CLOSED','CLOSED','REJECTED','FAILED')),
    computed_volume_lots   NUMERIC(12,4) NOT NULL,
    sizing_method_snapshot JSONB NOT NULL,
    requested_price        NUMERIC(20,8),
    filled_price            NUMERIC(20,8),
    slippage_pips           NUMERIC(8,2),
    reject_reason            TEXT,
    realized_pnl             NUMERIC(20,4),
    opened_at                TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (copy_relationship_id, idempotency_key)
);
CREATE INDEX idx_copiedtrades_relationship ON copied_trades(copy_relationship_id, created_at DESC);
CREATE INDEX idx_copiedtrades_signal ON copied_trades(trade_signal_id);
--rollback DROP TABLE IF EXISTS copied_trades CASCADE;

--changeset nectrix:006-high-water-mark-history
CREATE TABLE high_water_mark_history (
    id                   BIGSERIAL PRIMARY KEY,
    copy_relationship_id UUID NOT NULL REFERENCES copy_relationships(id) ON DELETE CASCADE,
    hwm_value            NUMERIC(20,4) NOT NULL,
    reason               TEXT NOT NULL CHECK (reason IN ('NEW_EQUITY_HIGH','RESET_ON_PAYOUT','ADMIN_ADJUSTMENT')),
    effective_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS high_water_mark_history CASCADE;
