package com.payflow.ledger.messaging;

import com.payflow.ledger.service.LedgerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class PaymentEventListener {

    private final JsonMapper json;
    private final LedgerService ledger;

    PaymentEventListener(JsonMapper json, LedgerService ledger) {
        this.json = json;
        this.ledger = ledger;
    }

    @KafkaListener(topics = "${payflow.topics.payment-events}")
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        PaymentEvent event = parse(record.value());
        event.validate();
        if (PaymentEvent.APPROVED.equals(event.eventType())) {
            ledger.book(event);
        }
        // Other event types (PaymentFailed, PaymentSettled, ...) are not the ledger's business.
    }

    private PaymentEvent parse(String value) {
        if (value == null) {
            throw new InvalidEventException("Empty message");
        }
        try {
            return json.readValue(value, PaymentEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Not a valid payment event", e);
        }
    }
}
