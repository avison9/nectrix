--liquibase formatted sql

-- docs/06-database-schema.md §6.2 "Partner / IB".
--changeset nectrix:008-partners
CREATE TABLE partners (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL UNIQUE REFERENCES users(id),
    referral_code     TEXT UNIQUE NOT NULL,
    commission_pct    NUMERIC(5,2) NOT NULL DEFAULT 10.00,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS partners CASCADE;

--changeset nectrix:008-referral-attributions
CREATE TABLE referral_attributions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id   UUID NOT NULL REFERENCES partners(id),
    referred_user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    attributed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS referral_attributions CASCADE;

--changeset nectrix:008-commission-ledger
CREATE TABLE commission_ledger (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id   UUID NOT NULL REFERENCES partners(id),
    source_invoice_id UUID NOT NULL REFERENCES invoices(id),
    commission_amount NUMERIC(20,4) NOT NULL,
    status       TEXT NOT NULL DEFAULT 'ACCRUED' CHECK (status IN ('ACCRUED','PAID','VOID')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS commission_ledger CASCADE;
