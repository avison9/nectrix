--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Social / Marketplace".
--changeset nectrix:005-master-profiles
CREATE TABLE master_profiles (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    primary_broker_account_id UUID NOT NULL REFERENCES broker_accounts(id),
    display_name          TEXT NOT NULL,
    bio                   TEXT,
    strategy_tags         TEXT[],
    performance_fee_percent NUMERIC(5,2) NOT NULL DEFAULT 20.00,
    fee_collection_method TEXT NOT NULL DEFAULT 'BROKER_PARTNERSHIP'
                            CHECK (fee_collection_method IN ('STRIPE_INVOICE','BROKER_PARTNERSHIP')),
    is_public             BOOLEAN NOT NULL DEFAULT TRUE,
    verified_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS master_profiles CASCADE;

--changeset nectrix:005-reviews
CREATE TABLE reviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_profile_id UUID NOT NULL REFERENCES master_profiles(id) ON DELETE CASCADE,
    reviewer_user_id  UUID NOT NULL REFERENCES users(id),
    rating            SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body              TEXT,
    moderation_status TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (moderation_status IN ('PENDING','APPROVED','REJECTED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS reviews CASCADE;

--changeset nectrix:005-leaderboard-snapshots
CREATE TABLE leaderboard_snapshots (
    id                BIGSERIAL PRIMARY KEY,
    master_profile_id UUID NOT NULL REFERENCES master_profiles(id) ON DELETE CASCADE,
    period            TEXT NOT NULL,
    return_pct        NUMERIC(10,4) NOT NULL,
    max_drawdown_pct  NUMERIC(10,4) NOT NULL,
    win_rate_pct      NUMERIC(6,2),
    sharpe_like_ratio NUMERIC(10,4),
    follower_count    INTEGER NOT NULL DEFAULT 0,
    aum_proxy         NUMERIC(20,2),
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (master_profile_id, period, computed_at)
);
CREATE INDEX idx_leaderboard_period_return ON leaderboard_snapshots(period, return_pct DESC);
--rollback DROP TABLE IF EXISTS leaderboard_snapshots CASCADE;
