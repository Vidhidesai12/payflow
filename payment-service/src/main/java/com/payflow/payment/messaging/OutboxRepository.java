package com.payflow.payment.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plain SQL rather than JPA: the outbox is a queue, and "lock the next batch, skipping rows another
 * instance already holds" is a SQL feature (FOR UPDATE SKIP LOCKED) that JPA does not express well.
 */
@Repository
public class OutboxRepository {

    public record OutboxMessage(UUID id, String topic, String key, String eventType, String payload) {
    }

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the same transaction as the business change it describes. */
    public void append(UUID id, String topic, String key, String eventType, String payload, Instant now) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, topic, message_key, event_type, payload, created_at)
                        VALUES (:id, :topic, :key, :type, :payload, :now)""")
                .param("id", id).param("topic", topic).param("key", key).param("type", eventType)
                .param("payload", payload).param("now", Timestamp.from(now))
                .update();
    }

    /** Oldest unpublished messages first; rows locked by another publisher are skipped, not waited on. */
    List<OutboxMessage> lockNextBatch(int limit) {
        return jdbc.sql("""
                        SELECT id, topic, message_key, event_type, payload
                          FROM outbox_events
                         WHERE published_at IS NULL
                         ORDER BY seq
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, n) -> new OutboxMessage(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("message_key"), rs.getString("event_type"), rs.getString("payload")))
                .list();
    }

    void markPublished(List<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox_events SET published_at = :now WHERE id IN (:ids)")
                .param("now", Timestamp.from(now)).param("ids", ids)
                .update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class).single();
    }

    int deletePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox_events WHERE published_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
