-- Every tool call an AI client makes: who, which tool, with what, and what happened.
CREATE TABLE tool_calls (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    called_at    TIMESTAMPTZ  NOT NULL,
    client       VARCHAR(100) NOT NULL,
    tool         VARCHAR(100) NOT NULL,
    arguments    TEXT         NOT NULL,   -- JSON
    outcome      VARCHAR(20)  NOT NULL,
    detail       VARCHAR(500),
    duration_ms  INT          NOT NULL,

    CONSTRAINT chk_tool_calls_outcome CHECK (outcome IN ('OK', 'DENIED', 'REFUSED', 'ERROR'))
);

CREATE INDEX idx_tool_calls_called_at ON tool_calls (called_at DESC);
CREATE INDEX idx_tool_calls_client ON tool_calls (client, called_at DESC);

-- An audit trail nobody can quietly edit: rows can be added, never changed or removed.
CREATE FUNCTION forbid_audit_changes() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'The audit log is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER tool_calls_append_only
    BEFORE UPDATE OR DELETE ON tool_calls
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_changes();

CREATE TRIGGER tool_calls_no_truncate
    BEFORE TRUNCATE ON tool_calls
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_changes();
