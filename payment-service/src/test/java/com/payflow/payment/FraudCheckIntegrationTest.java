package com.payflow.payment;

import com.payflow.payment.domain.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What happens to a payment when the fraud service says no, says maybe, or doesn't answer. */
class FraudCheckIntegrationTest extends IntegrationTestSupport {

    @Test
    void deniedPaymentFails() {
        FRAUD.on(FRAUD_PATH, b -> StubHttpServer.Response.json("{\"decision\":\"DENY\",\"reason\":\"blocked\"}"));

        Payment p = createOk("maker1", "100.00");

        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.failureReason()).isEqualTo("Blocked by the fraud check");
    }

    @Test
    void doubtfulPaymentIsHeldEvenWhenSmall() {
        FRAUD.on(FRAUD_PATH, b -> StubHttpServer.Response.json("{\"decision\":\"REVIEW\"}"));

        Payment p = createOk("maker1", "100.00");

        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        assertThat(p.holdReason()).isEqualTo("Flagged for review by the fraud check");
    }

    @Test
    void serverErrorsAreRetriedThenPaymentIsHeldNotApproved() {
        FRAUD.on(FRAUD_PATH, b -> new StubHttpServer.Response(503, "{}", 0));

        Payment p = createOk("maker1", "100.00");

        assertThat(FRAUD.calls(FRAUD_PATH)).isEqualTo(3); // first try + 2 retries
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        assertThat(p.holdReason()).isEqualTo("Fraud check unavailable; manual review required");
    }

    @Test
    void briefBlipIsSmoothedOverByRetry() {
        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        FRAUD.on(FRAUD_PATH, b -> attempt.incrementAndGet() == 1
                ? new StubHttpServer.Response(503, "{}", 0)
                : StubHttpServer.Response.json("{\"decision\":\"ALLOW\"}"));

        Payment p = createOk("maker1", "100.00");

        assertThat(FRAUD.calls(FRAUD_PATH)).isEqualTo(2);
        assertThat(p.status()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void slowFraudServiceTimesOutInsteadOfHangingTheRequest() {
        FRAUD.on(FRAUD_PATH, b -> new StubHttpServer.Response(200, "{\"decision\":\"ALLOW\"}", 2_000));

        long start = System.nanoTime();
        Payment p = createOk("maker1", "100.00");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        // 3 attempts x 300ms read timeout + small backoffs, well under 3 x 2s of waiting
        assertThat(millis).isLessThan(2_500);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingTheFraudService() {
        FRAUD.on(FRAUD_PATH, b -> new StubHttpServer.Response(500, "{}", 0));

        for (int i = 0; i < 5; i++) {
            createOk("maker1", "100.00"); // 5 checks that still fail after 3 attempts each
        }
        assertThat(FRAUD.calls(FRAUD_PATH)).isEqualTo(15);
        assertThat(fraudCheckClient.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = FRAUD.calls(FRAUD_PATH);
        Payment p = createOk("maker1", "100.00");

        assertThat(FRAUD.calls(FRAUD_PATH)).isEqualTo(callsBefore); // failed fast, no network call
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
    }

    /**
     * Regression test for a problem seen in a live run: half of all attempts failing, but every retry
     * succeeding, must not open the circuit. When the breaker sat inside the retry it counted each failed
     * attempt, opened, and held good payments for manual review.
     */
    @Test
    void blipsThatRetriesFixDoNotOpenTheCircuit() {
        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        FRAUD.on(FRAUD_PATH, b -> attempt.incrementAndGet() % 2 == 1
                ? new StubHttpServer.Response(503, "{}", 0)
                : StubHttpServer.Response.json("{\"decision\":\"ALLOW\"}"));

        for (int i = 0; i < 6; i++) {
            assertThat(createOk("maker1", "100.00").status()).isEqualTo(PaymentStatus.APPROVED);
        }

        assertThat(FRAUD.calls(FRAUD_PATH)).isEqualTo(12); // every check needed one retry
        assertThat(fraudCheckClient.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
