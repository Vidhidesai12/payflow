package com.payflow.ledger.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Moves events from the outbox table to Kafka.
 *
 * <p>Delivery is at-least-once: if the app dies after Kafka accepted a message but before the row was
 * marked published, the message is sent again on restart. Consumers must therefore ignore duplicates.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration keepPublishedFor;

    OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx, Clock clock,
                MeterRegistry meters,
                @Value("${payflow.outbox.batch-size}") int batchSize,
                @Value("${payflow.outbox.keep-published-for}") Duration keepPublishedFor) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
        this.keepPublishedFor = keepPublishedFor;
        Gauge.builder("payflow.outbox.pending", outbox, OutboxRepository::countUnpublished)
                .description("Events written but not yet published to Kafka")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval}")
    void publishPending() {
        Integer sent;
        do {
            sent = tx.execute(status -> publishBatch());
        } while (sent != null && sent == batchSize);
    }

    private int publishBatch() {
        List<OutboxRepository.OutboxMessage> batch = outbox.lockNextBatch(batchSize);
        List<UUID> published = new ArrayList<>();
        for (OutboxRepository.OutboxMessage m : batch) {
            try {
                var record = new ProducerRecord<>(m.topic(), m.key(), m.payload());
                record.headers().add("eventType", m.eventType().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                published.add(m.id());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Stop here so later events for the same key are not sent before this one. Retried next poll.
                log.warn("Kafka send failed for outbox event {}; will retry", m.id(), e);
                break;
            }
        }
        outbox.markPublished(published, clock.instant());
        return published.size() == batch.size() ? published.size() : -1;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    void deleteOldPublished() {
        int deleted = outbox.deletePublishedBefore(clock.instant().minus(keepPublishedFor));
        log.info("Deleted {} published outbox events older than {}", deleted, keepPublishedFor);
    }
}
