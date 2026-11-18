-- Who did what: needed for the maker-checker rule and for audit.
ALTER TABLE payments ADD COLUMN created_by  VARCHAR(100) NOT NULL DEFAULT 'unknown';
ALTER TABLE payments ALTER COLUMN created_by DROP DEFAULT;
ALTER TABLE payments ADD COLUMN approved_by VARCHAR(100);

-- Why a payment is waiting for a human (above threshold, flagged by fraud check, fraud check down).
ALTER TABLE payments ADD COLUMN hold_reason VARCHAR(255);

-- When the ledger confirmed the booking. Used by reconciliation.
ALTER TABLE payments ADD COLUMN settled_at TIMESTAMPTZ;
CREATE INDEX idx_payments_settled_at ON payments (settled_at) WHERE settled_at IS NOT NULL;

-- Four-eyes rule, enforced by the database as well as the code.
ALTER TABLE payments ADD CONSTRAINT chk_payments_four_eyes
    CHECK (approved_by IS NULL OR approved_by <> created_by);

-- Idempotency keys belong to the client that sent them. Two different users who happen to pick the
-- same key must get two separate payments, not each other's.
ALTER TABLE payments DROP CONSTRAINT uq_payments_idempotency_key;
ALTER TABLE payments ADD CONSTRAINT uq_payments_creator_idempotency_key UNIQUE (created_by, idempotency_key);
