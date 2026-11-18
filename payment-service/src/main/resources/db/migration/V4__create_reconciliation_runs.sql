-- One row per reconciliation run: did payments and the ledger agree for a time window?
CREATE TABLE reconciliation_runs (
    id                UUID        PRIMARY KEY,
    window_start      TIMESTAMPTZ NOT NULL,
    window_end        TIMESTAMPTZ NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    status            VARCHAR(20) NOT NULL,
    payments_checked  INT         NOT NULL,
    postings_checked  INT         NOT NULL,
    mismatch_count    INT         NOT NULL,
    details           TEXT        NOT NULL,   -- JSON list of mismatches, or the error message

    CONSTRAINT chk_recon_status CHECK (status IN ('MATCHED', 'MISMATCHED', 'FAILED')),
    CONSTRAINT chk_recon_window CHECK (window_start < window_end)
);

CREATE INDEX idx_recon_started_at ON reconciliation_runs (started_at DESC);
