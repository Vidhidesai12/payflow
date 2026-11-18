package com.payflow.payment.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reads postings from ledger-service's REST API. Used only by the nightly reconciliation job. */
@Component
class LedgerClient {

    record LedgerPosting(UUID paymentId, BigDecimal amount, String currency,
                         BigDecimal debitTotal, BigDecimal creditTotal, Instant postedAt) {
    }

    private final RestClient http;

    LedgerClient(RestClient.Builder builder, @Value("${payflow.ledger.base-url}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    List<LedgerPosting> postings(Instant from, Instant to) {
        return http.get().uri("/api/ledger/postings?from={from}&to={to}", from, to)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }
}
