package com.payflow.mcp.payflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Talks to PayFlow only through its public REST APIs, as any other client would. The MCP server never
 * touches PayFlow's databases, so every PayFlow rule (roles, idempotency, state machine) still applies.
 */
@Component
public class PayFlowClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(UUID id, String debtorAccount, String creditorAccount, BigDecimal amount, String currency,
                          String status, String failureReason, String holdReason, String createdBy, String approvedBy,
                          Instant createdAt, Instant settledAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String account, String direction, BigDecimal amount, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Posting(UUID postingId, UUID paymentId, BigDecimal amount, String currency, Instant postedAt,
                          BigDecimal debitTotal, BigDecimal creditTotal, List<Entry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Balance(String currency, BigDecimal balance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrialBalance(String currency, BigDecimal debits, BigDecimal credits, boolean balanced) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReconciliationRun(UUID id, Instant windowStart, Instant windowEnd, Instant startedAt, String status,
                                    int paymentsChecked, int postingsChecked, int mismatchCount, Object details) {
    }

    public record NewPayment(String debtorAccount, String creditorAccount, BigDecimal amount, String currency) {
    }

    public record Created(Payment payment, boolean replayed) {
    }

    private final RestClient payments;
    private final RestClient ledger;

    PayFlowClient(RestClient.Builder builder, PayFlowProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.payments = builder.clone().baseUrl(props.paymentsBaseUrl()).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(props.serviceUser(), props.servicePassword()))
                .build();
        this.ledger = builder.clone().baseUrl(props.ledgerBaseUrl()).requestFactory(factory).build();
    }

    public Payment payment(UUID id) {
        return call(() -> payments.get().uri("/api/payments/{id}", id).retrieve().body(Payment.class));
    }

    public List<Payment> payments(String status) {
        return call(() -> payments.get().uri(status == null ? "/api/payments" : "/api/payments?status=" + status)
                .retrieve().body(new ParameterizedTypeReference<List<Payment>>() {
                }));
    }

    public Created create(String idempotencyKey, NewPayment body) {
        return call(() -> {
            var response = payments.post().uri("/api/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Payment.class);
            return new Created(response.getBody(), "true".equals(response.getHeaders().getFirst("Idempotent-Replayed")));
        });
    }

    public Posting posting(UUID paymentId) {
        return call(() -> ledger.get().uri("/api/ledger/postings/{id}", paymentId).retrieve().body(Posting.class));
    }

    public List<Balance> balances(String account) {
        return call(() -> ledger.get().uri("/api/ledger/accounts/{a}/balances", account)
                .retrieve().body(new ParameterizedTypeReference<List<Balance>>() {
                }));
    }

    public List<TrialBalance> trialBalance() {
        return call(() -> ledger.get().uri("/api/ledger/trial-balance")
                .retrieve().body(new ParameterizedTypeReference<List<TrialBalance>>() {
                }));
    }

    public List<ReconciliationRun> reconciliationRuns() {
        return call(() -> payments.get().uri("/api/reconciliation/runs")
                .retrieve().body(new ParameterizedTypeReference<List<ReconciliationRun>>() {
                }));
    }

    /** Maps HTTP failures to safe messages: no stack traces, URLs or internal details reach the AI client. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404) {
                throw new PayFlowException(PayFlowException.Kind.NOT_FOUND, "Not found in PayFlow");
            }
            if (status.is4xxClientError()) {
                throw new PayFlowException(PayFlowException.Kind.REJECTED, "PayFlow rejected the request: " + detail(e));
            }
            throw new PayFlowException(PayFlowException.Kind.UNAVAILABLE, "PayFlow is unavailable; try again later");
        } catch (ResourceAccessException e) {
            throw new PayFlowException(PayFlowException.Kind.UNAVAILABLE, "PayFlow is unavailable; try again later");
        }
    }

    private static String detail(RestClientResponseException e) {
        try {
            Map<?, ?> problem = e.getResponseBodyAs(Map.class);
            Object detail = problem == null ? null : problem.get("detail");
            return detail == null ? "HTTP " + e.getStatusCode().value() : Sanitizer.clean(detail.toString());
        } catch (RuntimeException ignored) {
            return "HTTP " + e.getStatusCode().value();
        }
    }
}
