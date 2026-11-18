package com.payflow.payment;

import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.fraud.FraudCheckClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared setup for every integration test: the real app on a random port, real PostgreSQL and Kafka
 * in Docker (Testcontainers), and stub HTTP servers standing in for fraud-service and ledger-service.
 * All subclasses share one Spring context, so the containers start once per test run.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Short timings so resilience tests run in seconds, not minutes.
        "payflow.fraud.read-timeout=300ms",
        "payflow.fraud.initial-backoff=20ms",
        "payflow.fraud.wait-in-open-state=5s",
        "payflow.outbox.poll-interval=100ms"
})
abstract class IntegrationTestSupport {

    record Payment(UUID id, String debtorAccount, String creditorAccount, BigDecimal amount, String currency,
                   PaymentStatus status, String failureReason, String holdReason, String createdBy,
                   String approvedBy, Instant createdAt, Instant updatedAt, Instant settledAt) {
    }

    static final String FRAUD_PATH = "/api/fraud-checks";
    static final StubHttpServer FRAUD = new StubHttpServer();
    static final StubHttpServer LEDGER = new StubHttpServer();

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("payflow.fraud.base-url", FRAUD::baseUrl);
        registry.add("payflow.ledger.base-url", LEDGER::baseUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    FraudCheckClient fraudCheckClient;

    @BeforeEach
    void resetStubs() {
        FRAUD.reset();
        FRAUD.on(FRAUD_PATH, body -> StubHttpServer.Response.json("{\"decision\":\"ALLOW\"}"));
        LEDGER.reset();
        fraudCheckClient.resetCircuit();
    }

    /** A client logged in as one of the demo users from application.yml (password = name + "-pass"). */
    RestClient as(String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api")
                .defaultHeaders(h -> h.setBasicAuth(user, user + "-pass"))
                .defaultStatusHandler(status -> true, (req, res) -> { /* let tests assert on errors */ })
                .build();
    }

    <T> ResponseEntity<T> create(String user, String key, Map<String, Object> body, Class<T> type) {
        return as(user).post().uri("/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(type);
    }

    Payment createOk(String user, String amount) {
        return create(user, newKey(), body(amount), Payment.class).getBody();
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Fresh account numbers per test so tests don't depend on each other's data. */
    static Map<String, Object> body(String amount) {
        long n = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        return Map.of(
                "debtorAccount", "DEB" + n,
                "creditorAccount", "CRE" + n,
                "amount", new BigDecimal(amount),
                "currency", "USD");
    }
}
