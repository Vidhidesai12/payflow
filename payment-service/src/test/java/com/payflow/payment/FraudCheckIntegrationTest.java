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

        for (int i = 0; i < 3; i++) {
            createOk("maker1", "100.00"); // 3 payments x 3 attempts = 9 failed calls
        }
        assertThat(fraudCheckClient.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = FRAUD.calls(FRAUD_PATH);
        Payment p = createOk("maker1", "100.00");

        assertThat(FRAUD.calls(FRAUD_PATH)).isEqualTo(callsBefore); // failed fast, no network call
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
    }
}
