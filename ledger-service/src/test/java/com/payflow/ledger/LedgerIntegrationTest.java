package com.payflow.ledger;

import com.payflow.ledger.domain.LedgerEntryRepository;
import com.payflow.ledger.domain.Posting;
import com.payflow.ledger.domain.PostingRepository;
import com.payflow.ledger.messaging.PaymentEvent;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Drives the ledger only through Kafka, the way payment-service does, against real containers. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Value("${payflow.topics.payment-events}")
    String paymentEvents;

    @Value("${payflow.topics.ledger-events}")
    String ledgerEvents;

    @LocalServerPort
    int port;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    PostingRepository postings;

    @Autowired
    LedgerEntryRepository entries;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JsonMapper json;

    RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port + "/api/ledger")
                .defaultStatusHandler(s -> true, (req, res) -> { })
                .build();
    }

    @Test
    void approvedPaymentIsBookedAsBalancedDebitAndCredit() {
        PaymentEvent event = approved(UUID.randomUUID(), "125.50");

        send(event);

        Posting posting = awaitPosting(event.paymentId());
        assertThat(posting.getAmount()).isEqualByComparingTo("125.50");
        assertThat(entries.countByPostingId(posting.getId())).isEqualTo(2);

        Map<?, ?> view = api.get().uri("/postings/{id}", event.paymentId()).retrieve().body(Map.class);
        assertThat(new BigDecimal(view.get("debitTotal").toString())).isEqualByComparingTo("125.50");
        assertThat(new BigDecimal(view.get("creditTotal").toString())).isEqualByComparingTo("125.50");

        List<?> debtor = api.get().uri("/accounts/{a}/balances", event.debtorAccount()).retrieve().body(List.class);
        List<?> creditor = api.get().uri("/accounts/{a}/balances", event.creditorAccount()).retrieve().body(List.class);
        assertThat(new BigDecimal(((Map<?, ?>) debtor.getFirst()).get("balance").toString()))
                .isEqualByComparingTo("-125.50");
        assertThat(new BigDecimal(((Map<?, ?>) creditor.getFirst()).get("balance").toString()))
                .isEqualByComparingTo("125.50");
    }

    @Test
    void bookingPublishesLedgerPostedThroughTheOutbox() {
        PaymentEvent event = approved(UUID.randomUUID(), "42.00");

        send(event);

        ConsumerRecord<String, String> posted = awaitRecord(ledgerEvents,
                r -> r.key().equals(event.paymentId().toString()));
        Map<?, ?> body = json.readValue(posted.value(), Map.class);
        assertThat(body.get("eventType")).isEqualTo("LedgerPosted");
        assertThat(body.get("paymentId")).isEqualTo(event.paymentId().toString());
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE message_key = :k AND published_at IS NOT NULL")
                .param("k", event.paymentId().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void duplicateDeliveriesAreBookedOnlyOnce() {
        UUID paymentId = UUID.randomUUID();
        PaymentEvent event = approved(paymentId, "300.00");
        PaymentEvent sameEventAgain = event;
        PaymentEvent differentEventSamePayment = approved(paymentId, "300.00");

        // Same key, so all three land on one partition and are processed in this order.
        send(event);
        send(sameEventAgain);
        send(differentEventSamePayment);

        await().atMost(WAIT).until(() -> processed(differentEventSamePayment.eventId()));
        Posting posting = postings.findByPaymentId(paymentId).orElseThrow();
        assertThat(entries.countByPostingId(posting.getId())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM postings WHERE payment_id = :id")
                .param("id", paymentId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void eventsOtherThanApprovedAreIgnored() {
        PaymentEvent approved = approved(UUID.randomUUID(), "10.00");
        PaymentEvent failed = new PaymentEvent(UUID.randomUUID(), "PaymentFailed", UUID.randomUUID(),
                null, null, null, null, "FAILED", Instant.now());

        send(failed);
        send(approved);

        awaitPosting(approved.paymentId());
        assertThat(postings.findByPaymentId(failed.paymentId())).isEmpty();
    }

    @Test
    void unreadableMessageGoesToDeadLetterTopicWithoutBlockingOthers() throws Exception {
        String key = UUID.randomUUID().toString();
        kafka.send(paymentEvents, key, "this is not JSON").get();
        PaymentEvent next = approved(UUID.fromString(key), "5.00");
        send(next);

        ConsumerRecord<String, String> dead = awaitRecord(paymentEvents + ".DLT", r -> key.equals(r.key()));
        assertThat(dead.value()).isEqualTo("this is not JSON");
        awaitPosting(next.paymentId());
    }

    @Test
    void eventMissingMoneyDetailsGoesToDeadLetterTopic() {
        PaymentEvent broken = new PaymentEvent(UUID.randomUUID(), PaymentEvent.APPROVED, UUID.randomUUID(),
                "ACC1", null, new BigDecimal("1.00"), "USD", "APPROVED", Instant.now());

        send(broken);

        awaitRecord(paymentEvents + ".DLT", r -> broken.paymentId().toString().equals(r.key()));
        assertThat(postings.findByPaymentId(broken.paymentId())).isEmpty();
    }

    @Test
    void databaseRefusesToCommitAnUnbalancedPosting() {
        UUID postingId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            INSERT INTO postings (id, payment_id, debtor_account, creditor_account, amount, currency, posted_at)
                            VALUES (:id, :pid, 'ACC1', 'ACC2', 10.00, 'USD', :now)""")
                    .param("id", postingId).param("pid", UUID.randomUUID()).param("now", Timestamp.from(Instant.now()))
                    .update();
            jdbc.sql("""
                            INSERT INTO ledger_entries (id, posting_id, account, direction, amount, currency, created_at)
                            VALUES (:id, :pid, 'ACC1', 'DEBIT', 10.00, 'USD', :now)""")
                    .param("id", UUID.randomUUID()).param("pid", postingId).param("now", Timestamp.from(Instant.now()))
                    .update();
            // no matching CREDIT entry
        })).rootCause().hasMessageContaining("unbalanced"); // raised by the deferred trigger at COMMIT

        assertThat(postings.findById(postingId)).isEmpty();
    }

    @Test
    void ledgerRowsCannotBeChangedOrDeleted() {
        PaymentEvent event = approved(UUID.randomUUID(), "77.00");
        send(event);
        Posting posting = awaitPosting(event.paymentId());

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE posting_id = :id")
                .param("id", posting.getId()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings WHERE id = :id")
                .param("id", posting.getId()).update()).hasMessageContaining("append-only");
    }

    @Test
    void trialBalanceShowsDebitsEqualCredits() {
        PaymentEvent event = approved(UUID.randomUUID(), "64.00");
        send(event);
        awaitPosting(event.paymentId());

        List<?> rows = api.get().uri("/trial-balance").retrieve().body(List.class);

        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(((Map<?, ?>) r).get("balanced")).isEqualTo(true));
    }

    @Test
    void postingsCanBeListedByTimeWindowForReconciliation() {
        PaymentEvent event = approved(UUID.randomUUID(), "18.25");
        send(event);
        awaitPosting(event.paymentId());
        Instant now = Instant.now();

        List<?> rows = api.get().uri("/postings?from={f}&to={t}", now.minusSeconds(3600), now.plusSeconds(60))
                .retrieve().body(List.class);

        assertThat(rows).anySatisfy(r -> assertThat(((Map<?, ?>) r).get("paymentId"))
                .isEqualTo(event.paymentId().toString()));
        assertThat(api.get().uri("/postings?from={f}&to={t}", now, now.minusSeconds(1))
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(400);
    }

    // ---- helpers ---------------------------------------------------------------

    private static PaymentEvent approved(UUID paymentId, String amount) {
        long n = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        return new PaymentEvent(UUID.randomUUID(), PaymentEvent.APPROVED, paymentId, "DEB" + n, "CRE" + n,
                new BigDecimal(amount), "USD", "APPROVED", Instant.now());
    }

    private void send(PaymentEvent event) {
        try {
            kafka.send(paymentEvents, event.paymentId().toString(), json.writeValueAsString(event)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Posting awaitPosting(UUID paymentId) {
        return await().atMost(WAIT).until(() -> postings.findByPaymentId(paymentId), java.util.Optional::isPresent)
                .orElseThrow();
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single() == 1;
    }

    /** Reads a topic from the beginning with a fresh consumer group until a matching record shows up. */
    private ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> seen = new ArrayList<>();
            await().atMost(WAIT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(seen::add);
                return seen.stream().anyMatch(match);
            });
            return seen.stream().filter(match).findFirst().orElseThrow();
        }
    }
}
