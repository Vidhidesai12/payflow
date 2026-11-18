package com.payflow.payment.messaging;

import com.payflow.payment.domain.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Records a payment's new status as an event in the outbox. Does NOT talk to Kafka: that would be a
 * second write outside the database transaction (the "dual-write" problem).
 */
@Component
public class PaymentEventPublisher {

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final String topic;

    PaymentEventPublisher(OutboxRepository outbox, JsonMapper json, Clock clock,
                          @Value("${payflow.topics.payment-events}") String topic) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.topic = topic;
    }

    /** MANDATORY: fails fast if someone calls this outside the transaction that changed the payment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(Payment payment) {
        PaymentEvent event = PaymentEvent.of(payment, clock.instant());
        outbox.append(event.eventId(), topic, payment.getId().toString(), event.eventType(),
                json.writeValueAsString(event), event.occurredAt());
    }
}
