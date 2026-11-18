CREATE TABLE payments (
    id               UUID          PRIMARY KEY,
    idempotency_key  VARCHAR(100)  NOT NULL,
    request_hash     VARCHAR(64)   NOT NULL,
    debtor_account   VARCHAR(34)   NOT NULL,
    creditor_account VARCHAR(34)   NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    failure_reason   VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL,

    -- The database is the final guard against double-charging: even if two
    -- identical requests race past the application check, only one row can win.
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_accounts_differ CHECK (debtor_account <> creditor_account),
    CONSTRAINT chk_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payments_status CHECK (
        status IN ('CREATED', 'PENDING_APPROVAL', 'APPROVED', 'SETTLED', 'FAILED')
    )
);

CREATE INDEX idx_payments_status_created_at ON payments (status, created_at DESC);
