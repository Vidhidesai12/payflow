package com.payflow.payment;

import com.payflow.payment.domain.PaymentRepository;
import com.payflow.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The REST API end to end: real HTTP, real security, real PostgreSQL. */
class PaymentApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    PaymentRepository repository;

    // ---- creating payments --------------------------------------------------

    @Test
    void smallPaymentIsCreatedAndApproved() {
        ResponseEntity<Payment> res = create("maker1", newKey(), body("250.00"), Payment.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getHeaders().getLocation()).hasToString(
                "http://localhost:" + port + "/api/payments/" + res.getBody().id());
        assertThat(res.getBody().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(res.getBody().amount()).isEqualByComparingTo("250.00");
        assertThat(res.getBody().createdBy()).isEqualTo("maker1");
    }

    @Test
    void largePaymentWaitsForApproval() {
        Payment p = createOk("maker1", "25000.00");

        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        assertThat(p.holdReason()).isEqualTo("Amount is above the approval threshold");
    }

    // ---- idempotency ----------------------------------------------------------

    @Test
    void retryWithSameKeyReturnsOriginalPaymentInsteadOfChargingTwice() {
        String key = newKey();
        Map<String, Object> body = body("99.99");

        ResponseEntity<Payment> first = create("maker1", key, body, Payment.class);
        ResponseEntity<Payment> retry = create("maker1", key, body, Payment.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(repository.countByCreatedByAndIdempotencyKey("maker1", key)).isEqualTo(1);
    }

    @Test
    void sameAmountWrittenDifferentlyCountsAsSameRequest() {
        String key = newKey();
        Map<String, Object> body = body("100.00");
        create("maker1", key, body, Payment.class);

        Map<String, Object> sameButDifferentScale = new HashMap<>(body);
        sameButDifferentScale.put("amount", new BigDecimal("100"));

        assertThat(create("maker1", key, sameButDifferentScale, Payment.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void reusingKeyForDifferentPaymentIsRejected() {
        String key = newKey();
        create("maker1", key, body("10.00"), Payment.class);

        ResponseEntity<String> res = create("maker1", key, body("20.00"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(repository.countByCreatedByAndIdempotencyKey("maker1", key)).isEqualTo(1);
    }

    @Test
    void sameKeyFromDifferentUsersCreatesSeparatePayments() {
        String key = newKey();
        Map<String, Object> body = body("10.00");

        Payment fromMaker1 = create("maker1", key, body, Payment.class).getBody();
        ResponseEntity<Payment> fromMaker2 = create("maker2", key, body, Payment.class);

        // Keys are per client: maker2 must not receive maker1's payment.
        assertThat(fromMaker2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fromMaker2.getBody().id()).isNotEqualTo(fromMaker1.id());
    }

    @Test
    void concurrentRetriesWithSameKeyCreateExactlyOnePayment() throws Exception {
        String key = newKey();
        Map<String, Object> body = body("500.00");
        int threads = 20;
        CountDownLatch startTogether = new CountDownLatch(1);

        Callable<ResponseEntity<Payment>> request = () -> {
            startTogether.await();
            return create("maker1", key, body, Payment.class);
        };

        List<ResponseEntity<Payment>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<ResponseEntity<Payment>>> futures =
                    IntStream.range(0, threads).mapToObj(i -> pool.submit(request)).toList();
            startTogether.countDown();
            for (Future<ResponseEntity<Payment>> f : futures) {
                responses.add(f.get());
            }
        }

        Set<UUID> ids = responses.stream().map(r -> r.getBody().id()).collect(Collectors.toSet());
        long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful()).isTrue());
        assertThat(ids).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(repository.countByCreatedByAndIdempotencyKey("maker1", key)).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        ResponseEntity<String> res = as("maker1").post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON).body(body("10.00"))
                .retrieve().toEntity(String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- validation -----------------------------------------------------------

    @Test
    void invalidRequestReturnsFieldErrors() {
        Map<String, Object> bad = Map.of(
                "debtorAccount", "ACC123456",
                "creditorAccount", "ACC123456",
                "amount", new BigDecimal("-5"),
                "currency", "usd");

        ResponseEntity<Map> res = create("maker1", newKey(), bad, Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) res.getBody().get("errors");
        assertThat(errors).containsKeys("amount", "currency", "differentAccounts");
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsRejected() {
        ResponseEntity<String> res = create("maker1", newKey(), body("10.001"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- security and maker-checker -------------------------------------------

    @Test
    void requestWithoutCredentialsIsUnauthorized() {
        ResponseEntity<String> res = RestClient.builder().baseUrl("http://localhost:" + port + "/api").build()
                .get().uri("/payments").retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        ResponseEntity<String> res = RestClient.builder().baseUrl("http://localhost:" + port + "/api")
                .defaultHeaders(h -> h.setBasicAuth("maker1", "wrong")).build()
                .get().uri("/payments").retrieve()
                .onStatus(s -> true, (req, r) -> { })
                .toEntity(String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void checkerCannotCreatePayments() {
        ResponseEntity<String> res = create("checker1", newKey(), body("10.00"), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void makerCannotApprove() {
        Payment p = createOk("maker1", "75000.00");

        assertThat(action("maker2", p.id(), "approve", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void checkerApprovesHeldPaymentAndIsRecorded() {
        Payment p = createOk("maker1", "75000.00");

        Payment approved = action("checker1", p.id(), "approve", Payment.class).getBody();

        assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("checker1");
        assertThat(action("checker1", p.id(), "approve", String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void userWithBothRolesCannotApproveTheirOwnPayment() {
        // lead1 is both MAKER and CHECKER: the role check passes, so the four-eyes rule must catch it.
        Payment p = createOk("lead1", "75000.00");

        ResponseEntity<Map> res = action("lead1", p.id(), "approve", Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().get("detail").toString()).contains("cannot also approve");
        assertThat(action("checker1", p.id(), "approve", Payment.class).getBody().status())
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void checkerRejectsHeldPaymentWithReason() {
        Payment p = createOk("maker1", "75000.00");

        Payment rejected = as("checker1").post().uri("/payments/{id}/reject", p.id())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "Beneficiary not verified"))
                .retrieve().toEntity(Payment.class).getBody();

        assertThat(rejected.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejected.failureReason()).isEqualTo("Rejected by checker1: Beneficiary not verified");
        assertThat(action("checker1", p.id(), "approve", String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void approvedPaymentCanNoLongerBeRejected() {
        Payment p = createOk("maker1", "50.00"); // auto-approved, may already be on its way to the ledger

        ResponseEntity<String> res = as("checker1").post().uri("/payments/{id}/reject", p.id())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "changed my mind"))
                .retrieve().toEntity(String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void manualSettleEndpointNoLongerExists() {
        Payment p = createOk("maker1", "50.00");

        // Not open to anyone: settlement only comes from the ledger over Kafka.
        assertThat(action("checker1", p.id(), "settle", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- reading -----------------------------------------------------------------

    @Test
    void unknownPaymentReturns404() {
        ResponseEntity<String> res = as("auditor1").get().uri("/payments/{id}", UUID.randomUUID())
                .retrieve().toEntity(String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listCanFilterByStatus() {
        UUID pendingId = createOk("maker1", "99999.00").id();

        Payment[] pending = as("checker1").get().uri("/payments?status=PENDING_APPROVAL")
                .retrieve().body(Payment[].class);

        assertThat(pending).extracting(Payment::id).contains(pendingId);
        assertThat(pending).allSatisfy(p -> assertThat(p.status()).isEqualTo(PaymentStatus.PENDING_APPROVAL));
    }

    private <T> ResponseEntity<T> action(String user, UUID id, String action, Class<T> type) {
        return as(user).post().uri("/payments/{id}/{action}", id, action).retrieve().toEntity(type);
    }
}
