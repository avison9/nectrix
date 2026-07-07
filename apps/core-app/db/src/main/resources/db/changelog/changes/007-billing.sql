--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Billing".
--changeset nectrix:007-management-agreements
CREATE TABLE management_agreements (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    copy_relationship_id  UUID NOT NULL UNIQUE REFERENCES copy_relationships(id) ON DELETE CASCADE,
    agreement_version     TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'PENDING_SIGNATURE'
                            CHECK (status IN ('PENDING_SIGNATURE','SIGNED','VOID')),
    document_object_key   TEXT NOT NULL,
    signature_reference   TEXT,
    signed_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS management_agreements CASCADE;

--changeset nectrix:007-subscriptions
CREATE TABLE subscriptions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id),
    plan_code     TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('TRIALING','ACTIVE','PAST_DUE','CANCELED')),
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end   TIMESTAMPTZ NOT NULL,
    payment_provider_customer_id TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS subscriptions CASCADE;

--changeset nectrix:007-performance-fee-ledger
CREATE TABLE performance_fee_ledger (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    copy_relationship_id UUID NOT NULL REFERENCES copy_relationships(id),
    period_start         TIMESTAMPTZ NOT NULL,
    period_end           TIMESTAMPTZ NOT NULL,
    starting_hwm         NUMERIC(20,4) NOT NULL,
    ending_equity        NUMERIC(20,4) NOT NULL,
    new_profit_above_hwm NUMERIC(20,4) NOT NULL,
    master_fee_amount    NUMERIC(20,4) NOT NULL,
    platform_take_amount NUMERIC(20,4) NOT NULL,
    net_to_master_amount NUMERIC(20,4) NOT NULL,
    computation_detail   JSONB NOT NULL,
    status               TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN
                            ('PENDING','INVOICED','PAID','REPORTED_TO_BROKER',
                             'BROKER_CONFIRMED_DEDUCTED','BROKER_CONFIRMED_PAID','DISPUTED','VOID')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (copy_relationship_id, period_start, period_end)
);
--rollback DROP TABLE IF EXISTS performance_fee_ledger CASCADE;

--changeset nectrix:007-broker-fee-reports
CREATE TABLE broker_fee_reports (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    master_profile_id     UUID NOT NULL REFERENCES master_profiles(id),
    broker_type           TEXT NOT NULL CHECK (broker_type IN ('CTRADER','MT5')),
    period_start          TIMESTAMPTZ NOT NULL,
    period_end            TIMESTAMPTZ NOT NULL,
    status                TEXT NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','SENT','BROKER_CONFIRMED_DEDUCTED','BROKER_CONFIRMED_PAID','FAILED')),
    report_object_key     TEXT NOT NULL,
    sent_at               TIMESTAMPTZ,
    confirmed_deducted_at TIMESTAMPTZ,
    confirmed_paid_at     TIMESTAMPTZ,
    generated_by_user_id  UUID NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (master_profile_id, broker_type, period_start, period_end)
);
CREATE INDEX idx_broker_fee_reports_master ON broker_fee_reports(master_profile_id, period_start DESC);
--rollback DROP TABLE IF EXISTS broker_fee_reports CASCADE;

--changeset nectrix:007-broker-fee-report-lines
CREATE TABLE broker_fee_report_lines (
    id                          BIGSERIAL PRIMARY KEY,
    broker_fee_report_id        UUID NOT NULL REFERENCES broker_fee_reports(id) ON DELETE CASCADE,
    performance_fee_ledger_id   UUID NOT NULL REFERENCES performance_fee_ledger(id),
    follower_broker_account_login TEXT NOT NULL,
    fee_amount                  NUMERIC(20,4) NOT NULL,
    currency                    TEXT NOT NULL,
    UNIQUE (broker_fee_report_id, performance_fee_ledger_id)
);
--rollback DROP TABLE IF EXISTS broker_fee_report_lines CASCADE;

--changeset nectrix:007-invoices
CREATE TABLE invoices (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    source_type  TEXT NOT NULL CHECK (source_type IN ('PERFORMANCE_FEE','SUBSCRIPTION')),
    source_id    UUID NOT NULL,
    amount       NUMERIC(20,4) NOT NULL,
    currency     TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PAID','FAILED','VOID')),
    payment_provider_ref TEXT,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at      TIMESTAMPTZ
);
--rollback DROP TABLE IF EXISTS invoices CASCADE;
