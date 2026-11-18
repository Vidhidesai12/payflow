package com.payflow.payment;

import com.payflow.payment.domain.PaymentRepository;
import com.payflow.payment.domain.PaymentStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** payment-service's side of the Kafka conversation with ledger-service. */
class EventFlowIntegrationTest extends IntegrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Value("${payflow.topics.payment-events}")
    String paymentEvents;

    @Value("${payflow.topics.ledger-events}")
    String ledgerEvents;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    PaymentRepository repository;

    @Autowired
    JsonMapper json;

    @Test
    void approvedPaymentIsPublishedThroughTheOutbox() {
        Payment p = createOk("maker1", "120.00");

        ConsumerRecord<String, String> record = awaitRecord(paymentEvents, r -> r.key().equals(p.id().toString()));

        Map<?, ?> event = json.readValue(record.value(), Map.class);
        assertThat(event.get("eventType")).isEqualTo("PaymentApproved");
        assertThat(event.get("debtorAccount")).isEqualTo(p.debtorAccount());
        assertThat(new java.math.BigDecimal(event.get("amount").toString())).isEqualByComparingTo("120.00");
    }

    @Test
    void heldPaymentIsPublishedOnlyAsHeldUntilACheckerApproves() {
        Payment p = createOk("maker1", "20000.00");

        List<ConsumerRecord<String, String>> held = awaitRecords(paymentEvents, r -> r.key().equals(p.id().toString()), 1);
        assertThat(held.getFirst().value()).contains("\"PaymentHeld\"");

        as("checker1").post().uri("/payments/{id}/approve", p.id()).retrieve().toBodilessEntity();

        List<ConsumerRecord<String, String>> both = awaitRecords(paymentEvents, r -> r.key().equals(p.id().toString()), 2);
        assertThat(both.get(1).value()).contains("\"PaymentApproved\"");
    }

    @Test
    void ledgerPostedSettlesThePaymentAndPublishesPaymentSettled() {
        Payment p = createOk("maker1", "75.25");

        sendLedgerPosted(p.id(), "75.25", "USD");

        await().atMost(WAIT).until(() -> repository.findById(p.id()).orElseThrow().getStatus() == PaymentStatus.SETTLED);
        assertThat(repository.findById(p.id()).orElseThrow().getSettledAt()).isNotNull();
        awaitRecord(paymentEvents, r -> r.key().equals(p.id().toString()) && r.value().contains("\"PaymentSettled\""));
    }

    @Test
    void duplicateLedgerPostedIsHarmless() {
        Payment p = createOk("maker1", "10.00");

        sendLedgerPosted(p.id(), "10.00", "USD");
        sendLedgerPosted(p.id(), "10.00", "USD");
        UUID marker = createOk("maker1", "11.00").id();
        sendLedgerPosted(marker, "11.00", "USD");

        await().atMost(WAIT).until(() -> repository.findById(marker).orElseThrow().getStatus() == PaymentStatus.SETTLED);
        assertThat(repository.findById(p.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(awaitRecords(paymentEvents, r -> r.key().equals(p.id().toString())
                && r.value().contains("\"PaymentSettled\""), 1)).hasSize(1);
    }

    @Test
    void ledgerBookingTheWrongAmountIsDeadLetteredAndPaymentIsNotSettled() {
        Payment p = createOk("maker1", "10.00");

        sendLedgerPosted(p.id(), "99.00", "USD");

        awaitRecord(ledgerEvents + ".DLT", r -> r.key().equals(p.id().toString()));
        assertThat(repository.findById(p.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void unreadableLedgerMessageIsDeadLettered() throws Exception {
        String key = UUID.randomUUID().toString();

        kafka.send(ledgerEvents, key, "{not json").get();

        ConsumerRecord<String, String> dead = awaitRecord(ledgerEvents + ".DLT", r -> key.equals(r.key()));
        assertThat(dead.value()).isEqualTo("{not json");
    }

    // ---- helpers ---------------------------------------------------------------

    private void sendLedgerPosted(UUID paymentId, String amount, String currency) {
        Map<String, Object> event = Map.of(
                "eventId", UUID.randomUUID(), "eventType", "LedgerPosted", "paymentId", paymentId,
                "postingId", UUID.randomUUID(), "amount", new java.math.BigDecimal(amount),
                "currency", currency, "postedAt", Instant.now().toString());
        try {
            kafka.send(ledgerEvents, paymentId.toString(), json.writeValueAsString(event)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> match) {
        return awaitRecords(topic, match, 1).getFirst();
    }

    /** Reads the topic from the start with a fresh consumer group until `count` records match. */
    private List<ConsumerRecord<String, String>> awaitRecords(String topic,
                                                              Predicate<ConsumerRecord<String, String>> match,
                                                              int count) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> matched = new ArrayList<>();
            await().atMost(WAIT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (match.test(r)) {
                        matched.add(r);
                    }
                });
                return matched.size() >= count;
            });
            // a short extra poll, so "exactly N" assertions would see an unexpected extra record
            consumer.poll(Duration.ofMillis(1000)).forEach(r -> {
                if (match.test(r)) {
                    matched.add(r);
                }
            });
            return matched;
        }
    }
}
