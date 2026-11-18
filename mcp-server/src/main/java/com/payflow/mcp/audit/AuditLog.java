package com.payflow.mcp.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Writes one row per tool call to the append-only tool_calls table. */
@Component
public class AuditLog {

    public enum Outcome { OK, DENIED, REFUSED, ERROR }

    private final JdbcClient jdbc;

    AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Instant at, String client, String tool, String argumentsJson, Outcome outcome,
                       String detail, long durationMs) {
        jdbc.sql("""
                        INSERT INTO tool_calls (called_at, client, tool, arguments, outcome, detail, duration_ms)
                        VALUES (:at, :client, :tool, :args, :outcome, :detail, :ms)""")
                .param("at", Timestamp.from(at))
                .param("client", client)
                .param("tool", tool)
                .param("args", argumentsJson)
                .param("outcome", outcome.name())
                .param("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)))
                .param("ms", (int) Math.min(durationMs, Integer.MAX_VALUE))
                .update();
    }
}
