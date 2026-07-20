--liquibase formatted sql

-- TICKET-101 follow-up — a queryable record of every broker-account archive-then-cascade-delete,
-- so "was this account's history actually archived, and where" is answerable without downloading
-- and decompressing the blob itself. Written by BrokerAccountArchivalOrchestrator (bootstrap)
-- immediately after a successful blob upload, before any row is deleted.
--changeset nectrix:032-archival-audit-log
CREATE TABLE archival_log (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    broker_account_id     UUID NOT NULL,
    blob_key              TEXT NOT NULL,
    archived_row_counts   JSONB NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_archivallog_broker_account ON archival_log(broker_account_id);
--rollback DROP TABLE IF EXISTS archival_log CASCADE;
