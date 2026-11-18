-- One posting = one money movement for one payment. It owns a balanced set of ledger entries.
CREATE TABLE postings (
    id               UUID          PRIMARY KEY,
    payment_id       UUID          NOT NULL,
    debtor_account   VARCHAR(34)   NOT NULL,
    creditor_account VARCHAR(34)   NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    posted_at        TIMESTAMPTZ   NOT NULL,

    -- A payment can be booked once, no matter how many times its event is delivered.
    CONSTRAINT uq_postings_payment_id UNIQUE (payment_id),
    CONSTRAINT chk_postings_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_postings_posted_at ON postings (posted_at);

-- Double-entry: every posting has debits and credits that add up to the same total.
CREATE TABLE ledger_entries (
    id          UUID          PRIMARY KEY,
    posting_id  UUID          NOT NULL REFERENCES postings (id),
    account     VARCHAR(34)   NOT NULL,
    direction   VARCHAR(6)    NOT NULL,
    amount      NUMERIC(19,2) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,

    CONSTRAINT chk_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_entries_posting_id ON ledger_entries (posting_id);
CREATE INDEX idx_entries_account ON ledger_entries (account, currency);

-- Ledgers are append-only: a mistake is fixed with a new reversing posting, never by editing history.
CREATE FUNCTION forbid_ledger_changes() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'The ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_changes();

CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_changes();

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_changes();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_ledger_changes();

-- The database refuses to COMMIT a posting whose debits and credits differ.
-- DEFERRABLE INITIALLY DEFERRED: checked at commit, after both entries have been inserted.
CREATE FUNCTION check_posting_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    net NUMERIC;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO net
      FROM ledger_entries
     WHERE posting_id = NEW.posting_id;

    IF net <> 0 THEN
        RAISE EXCEPTION 'Posting % is unbalanced: debits minus credits = %', NEW.posting_id, net;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_posting_balanced();

-- Idempotent consumer: the ID of every Kafka event already handled.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Transactional outbox: events written in the same transaction as the ledger rows,
-- then published to Kafka by OutboxRelay.
CREATE TABLE outbox_events (
    id            UUID         PRIMARY KEY,
    seq           BIGINT       GENERATED ALWAYS AS IDENTITY,
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100) NOT NULL,
    event_type    VARCHAR(50)  NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
