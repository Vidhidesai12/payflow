-- Transactional outbox: an event row is written in the SAME transaction as the payment change,
-- so the database and Kafka can never disagree. OutboxRelay publishes the rows afterwards.
CREATE TABLE outbox_events (
    id            UUID         PRIMARY KEY,
    seq           BIGINT       GENERATED ALWAYS AS IDENTITY,  -- publish order
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100) NOT NULL,                      -- payment ID: keeps one payment's events in order
    event_type    VARCHAR(50)  NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);

-- Partial index: the relay only ever looks for unpublished rows, which stay few.
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
