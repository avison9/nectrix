--liquibase formatted sql

-- TICKET-103: symbol_mappings (004-broker-connectivity.sql) was created with
-- no confirmation-state column -- every row was implicitly "trusted" since
-- nothing populated the table yet. This ticket's auto-suggestion flow is the
-- first real writer, and per the design (nectrix_plan/docs/08-copy-trading-
-- engine.md §8.4), an auto-suggested mapping must never be used for live
-- copying until a user/admin explicitly confirms it -- so new rows default
-- unconfirmed. BOOLEAN (not TEXT+CHECK) matches this schema's own convention
-- for genuinely binary flags (is_demo, is_active) vs. real multi-state
-- fields (connection_status). confirmed_at/confirmed_by_user_id mirror the
-- audit-trail style already used elsewhere (follow_requests.decided_by_user_id).
--changeset nectrix:019-symbol-mappings-confirmation
ALTER TABLE symbol_mappings ADD COLUMN is_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE symbol_mappings ADD COLUMN confirmed_at TIMESTAMPTZ;
ALTER TABLE symbol_mappings ADD COLUMN confirmed_by_user_id UUID REFERENCES users(id);
-- Copy Engine's dispatcher (apps/copy-engine/internal/pipeline/dispatch.go)
-- checks this on every dispatched order -- a hot lookup path.
CREATE INDEX idx_symbol_mappings_confirmed ON symbol_mappings(broker_account_id, canonical_symbol) WHERE is_confirmed = TRUE;
--rollback DROP INDEX IF EXISTS idx_symbol_mappings_confirmed;
--rollback ALTER TABLE symbol_mappings DROP COLUMN IF EXISTS confirmed_by_user_id;
--rollback ALTER TABLE symbol_mappings DROP COLUMN IF EXISTS confirmed_at;
--rollback ALTER TABLE symbol_mappings DROP COLUMN IF EXISTS is_confirmed;
