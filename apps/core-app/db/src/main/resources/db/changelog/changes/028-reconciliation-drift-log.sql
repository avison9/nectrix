--liquibase formatted sql

-- TICKET-117: System Health's reconciliation-drift-rate metric. TICKET-109 already publishes a
-- real ReconciliationDriftDetected protobuf event to the "reconciliation" Kafka topic
-- (copy-engine/internal/pipeline/reconcile.go) -- nothing has ever consumed it until now. This
-- table is that consumer's landing spot; System Health queries a count over a recent window
-- against it for a real rate, not a mock number.
--changeset nectrix:028-reconciliation-drift-log
CREATE TABLE reconciliation_drift_log (
    id                 BIGSERIAL PRIMARY KEY,
    broker_account_id  UUID NOT NULL,
    drift_type         TEXT NOT NULL,
    detected_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconciliation_drift_log_detected_at ON reconciliation_drift_log(detected_at);
--rollback DROP TABLE IF EXISTS reconciliation_drift_log CASCADE;
