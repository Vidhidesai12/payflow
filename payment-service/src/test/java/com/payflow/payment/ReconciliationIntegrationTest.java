package com.payflow.payment;

import com.payflow.payment.domain.PaymentRepository;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Nightly reconciliation against a stubbed ledger API, so every kind of disagreement can be staged. */
class ReconciliationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    PaymentService payments;

    @Autowired
    PaymentRepository repository;

    @Test
    void settledPaymentsThatMatchTheLedgerReconcileCleanly() {
        Payment p = settledPayment("40.00");
        ledgerReturns(posting(p.id(), "40.00", "40.00", "40.00", Instant.now()));

        Map<?, ?> run = runNow();

        assertThat(run.get("status")).isEqualTo("MATCHED");
        assertThat(run.get("mismatchCount")).isEqualTo(0);
    }

    @Test
    void everyKindOfDisagreementIsReported() {
        Payment missing = settledPayment("10.00");
        Payment wrongAmount = settledPayment("20.00");
        Payment notSettled = createOk("maker1", "30.00"); // APPROVED, never settled
        UUID unknown = UUID.randomUUID();
        Instant longAgo = Instant.now().minusSeconds(3600);

        ledgerReturns(
                posting(wrongAmount.id(), "25.00", "25.00", "25.00", Instant.now()),
                posting(notSettled.id(), "30.00", "30.00", "30.00", longAgo),
                posting(unknown, "5.00", "5.00", "4.00", Instant.now()));

        // Wider window so the hour-old posting is inside it (other tests' payments may show up too).
        Map<?, ?> run = runNow(Instant.now().minusSeconds(2 * 3600));

        assertThat(run.get("status")).isEqualTo("MISMATCHED");
        List<?> details = (List<?>) run.get("details");
        assertThat(details).extracting(d -> ((Map<?, ?>) d).get("kind") + ":" + ((Map<?, ?>) d).get("paymentId"))
                .contains(
                        "MISSING_IN_LEDGER:" + missing.id(),
                        "AMOUNT_MISMATCH:" + wrongAmount.id(),
                        "NOT_SETTLED:" + notSettled.id(),
                        "UNKNOWN_PAYMENT:" + unknown,
                        "UNBALANCED_POSTING:" + unknown);
    }

    @Test
    void ledgerOutageIsRecordedAsAFailedRun() {
        LEDGER.on("/api/ledger/postings", b -> new StubHttpServer.Response(503, "{}", 0));

        Map<?, ?> run = runNow();

        assertThat(run.get("status")).isEqualTo("FAILED");
        List<?> recent = as("auditor1").get().uri("/reconciliation/runs").retrieve().body(List.class);
        assertThat(recent).extracting(r -> String.valueOf(((Map<?, ?>) r).get("id"))).contains(String.valueOf(run.get("id")));
    }

    @Test
    void onlyAuditorsCanRunReconciliation() {
        ResponseEntity<String> res = as("checker1").post().uri("/reconciliation/runs").retrieve().toEntity(String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ---------------------------------------------------------------

    private Payment settledPayment(String amount) {
        Payment p = createOk("maker1", amount);
        payments.settleFromLedger(p.id(), new BigDecimal(amount), "USD");
        assertThat(repository.findById(p.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        return p;
    }

    /** By default the window covers only this test's payments: other tests' settled payments are older. */
    private Map<?, ?> runNow() {
        return runNow(testStart);
    }

    private Map<?, ?> runNow(Instant from) {
        Instant to = Instant.now().plusSeconds(1);
        return as("auditor1").post().uri("/reconciliation/runs?from={f}&to={t}", from, to)
                .retrieve().body(Map.class);
    }

    private final Instant testStart = Instant.now();

    private void ledgerReturns(String... postings) {
        String body = "[" + String.join(",", postings) + "]";
        LEDGER.on("/api/ledger/postings", b -> StubHttpServer.Response.json(body));
    }

    private static String posting(UUID paymentId, String amount, String debits, String credits, Instant postedAt) {
        return """
                {"paymentId":"%s","amount":%s,"currency":"USD","debitTotal":%s,"creditTotal":%s,"postedAt":"%s"}"""
                .formatted(paymentId, amount, debits, credits, postedAt);
    }
}
