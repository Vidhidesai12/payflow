package com.payflow.payment.messaging;

import com.payflow.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Replaces the old manual /settle endpoint: a payment is SETTLED only once the ledger says so. */
@Component
class LedgerEventListener {

    private final JsonMapper json;
    private final PaymentService payments;

    LedgerEventListener(JsonMapper json, PaymentService payments) {
        this.json = json;
        this.payments = payments;
    }

    @KafkaListener(topics = "${payflow.topics.ledger-events}")
    void onLedgerEvent(ConsumerRecord<String, String> record) {
        LedgerPostedEvent event = parse(record.value());
        if (!LedgerPostedEvent.TYPE.equals(event.eventType())) {
            return;
        }
        event.validate();
        payments.settleFromLedger(event.paymentId(), event.amount(), event.currency());
    }

    private LedgerPostedEvent parse(String value) {
        if (value == null) {
            throw new InvalidEventException("Empty message");
        }
        try {
            return json.readValue(value, LedgerPostedEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Not a valid ledger event", e);
        }
    }
}
