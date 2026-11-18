package com.payflow.payment.messaging;

import com.payflow.payment.domain.InvalidStatusTransitionException;
import com.payflow.payment.service.LedgerMismatchException;
import com.payflow.payment.service.PaymentNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    static final int PARTITIONS = 3;
    static final String DLT_SUFFIX = ".DLT";

    @Bean
    NewTopic paymentEventsTopic(@Value("${payflow.topics.payment-events}") String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic ledgerEventsTopic(@Value("${payflow.topics.ledger-events}") String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic ledgerEventsDeadLetterTopic(@Value("${payflow.topics.ledger-events}") String name) {
        return TopicBuilder.name(name + DLT_SUFFIX).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * Retry transient failures (e.g. an optimistic-lock clash with a checker) 3 times, then dead-letter.
     * Errors that retrying can't fix go to the dead-letter topic at once.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        Counter deadLettered = Counter.builder("payflow.events.dead_lettered")
                .description("Events moved to a dead-letter topic").register(meters);

        var recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            deadLettered.increment();
            log.error("Sending {}-{}@{} to the dead-letter topic", record.topic(), record.partition(),
                    record.offset(), ex);
            return new TopicPartition(record.topic() + DLT_SUFFIX, record.partition());
        });

        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class, PaymentNotFoundException.class,
                InvalidStatusTransitionException.class, LedgerMismatchException.class);
        return handler;
    }
}
