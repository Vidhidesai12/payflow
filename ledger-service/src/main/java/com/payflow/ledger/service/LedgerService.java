package com.payflow.ledger.service;

import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.domain.Posting;
import com.payflow.ledger.domain.PostingRepository;
import com.payflow.ledger.messaging.LedgerPostedEvent;
import com.payflow.ledger.messaging.OutboxRepository;
import com.payflow.ledger.messaging.PaymentEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final EntityManager em;
    private final PostingRepository postings;
    private final OutboxRepository outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final String ledgerEventsTopic;
    private final Counter booked;
    private final Counter duplicates;

    LedgerService(EntityManager em, PostingRepository postings, OutboxRepository outbox, JdbcClient jdbc,
                  JsonMapper json, Clock clock, MeterRegistry meters,
                  @Value("${payflow.topics.ledger-events}") String ledgerEventsTopic) {
        this.em = em;
        this.postings = postings;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.ledgerEventsTopic = ledgerEventsTopic;
        this.booked = Counter.builder("payflow.ledger.postings").description("Payments booked").register(meters);
        this.duplicates = Counter.builder("payflow.ledger.duplicate_events")
                .description("Already-processed events that were skipped").register(meters);
    }

    /**
     * Books an approved payment exactly once. Everything here commits or rolls back together:
     * the "already processed" marker, the posting, both ledger entries and the outbox event.
     */
    @Transactional
    public void book(PaymentEvent event) {
        if (!claim(event.eventId())) {
            duplicates.increment();
            log.info("Event {} was already processed; skipping", event.eventId());
            return;
        }
        if (postings.existsByPaymentId(event.paymentId())) {
            // A different event for a payment we already booked. Never book money twice.
            duplicates.increment();
            log.warn("Payment {} is already booked; ignoring event {}", event.paymentId(), event.eventId());
            return;
        }

        Instant now = clock.instant();
        Posting posting = Posting.of(event.paymentId(), event.debtorAccount(), event.creditorAccount(),
                event.amount(), event.currency(), now);
        // persist(), not save(): these IDs are assigned by us, and save() would first SELECT to see if they exist.
        em.persist(posting);
        for (LedgerEntry entry : posting.entries()) {
            em.persist(entry);
        }

        var posted = new LedgerPostedEvent(UUID.randomUUID(), LedgerPostedEvent.TYPE, posting.getPaymentId(),
                posting.getId(), posting.getAmount(), posting.getCurrency(), now);
        outbox.append(posted.eventId(), ledgerEventsTopic, posting.getPaymentId().toString(),
                LedgerPostedEvent.TYPE, json.writeValueAsString(posted), now);

        booked.increment();
        log.info("Booked payment {} as posting {}", event.paymentId(), posting.getId());
    }

    /**
     * Records the event ID. Returns false if it was already there. ON CONFLICT DO NOTHING makes the
     * check and the claim one atomic statement, so two consumers can't both pass the check.
     */
    private boolean claim(UUID eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_events (event_id, processed_at) VALUES (:id, :now)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("id", eventId).param("now", Timestamp.from(clock.instant()))
                .update();
        return inserted == 1;
    }
}
