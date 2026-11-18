package com.payflow.payment.fraud;

import com.payflow.payment.domain.FraudVerdict;
import com.payflow.payment.domain.Payment;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls fraud-service, protected by three layers, innermost first:
 * <ol>
 *   <li><b>Timeouts</b> on connect and read, so a hung service can't hold our request threads.</li>
 *   <li><b>Circuit breaker</b>: once most recent calls fail, stop calling for a while and fail fast.</li>
 *   <li><b>Retry</b> with exponential backoff, for brief blips (only on errors worth retrying).</li>
 * </ol>
 * If all of that fails, the answer is {@link FraudVerdict#UNAVAILABLE}: the payment is held for a human,
 * never approved blindly ("fail safe", not "fail open").
 */
@Component
public class FraudCheckClient {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckClient.class);

    record FraudCheckRequest(UUID paymentId, String debtorAccount, String creditorAccount,
                             BigDecimal amount, String currency) {
    }

    record FraudCheckResponse(String decision, String reason) {
    }

    private final RestClient http;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Timer timer;

    FraudCheckClient(RestClient.Builder builder, FraudProperties props, MeterRegistry meters) {
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(props.readTimeout());
        this.http = builder.baseUrl(props.baseUrl()).requestFactory(requestFactory).build();

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.slidingWindowSize())
                .minimumNumberOfCalls(props.minimumCalls())
                .failureRateThreshold(props.failureRateThreshold())
                .waitDurationInOpenState(props.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        this.circuitBreaker = breakers.circuitBreaker("fraud-service");
        circuitBreaker.getEventPublisher().onStateTransition(e ->
                log.warn("Fraud-service circuit breaker: {}", e.getStateTransition()));

        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(props.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(props.initialBackoff(), 2.0))
                // Retry network trouble and 5xx. Never retry 4xx (our bug) or an open circuit (pointless).
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build());
        this.retry = retries.retry("fraud-service");

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
        this.timer = Timer.builder("payflow.fraud.check").description("Fraud check incl. retries")
                .register(meters);
    }

    public FraudVerdict check(Payment payment) {
        Supplier<FraudVerdict> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> callFraudService(payment)));
        return timer.record(() -> {
            try {
                return guarded.get();
            } catch (RuntimeException e) {
                log.warn("Fraud check unavailable for payment {} ({}); holding it for manual review",
                        payment.getId(), e.toString());
                return FraudVerdict.UNAVAILABLE;
            }
        });
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    /** For tests and demos: forget past failures. */
    public void resetCircuit() {
        circuitBreaker.reset();
    }

    private FraudVerdict callFraudService(Payment p) {
        FraudCheckResponse response = http.post().uri("/api/fraud-checks")
                .body(new FraudCheckRequest(p.getId(), p.getDebtorAccount(), p.getCreditorAccount(),
                        p.getAmount(), p.getCurrency()))
                .retrieve()
                .body(FraudCheckResponse.class);
        if (response == null || response.decision() == null) {
            return FraudVerdict.REVIEW;
        }
        return switch (response.decision()) {
            case "ALLOW" -> FraudVerdict.ALLOW;
            case "DENY" -> FraudVerdict.DENY;
            default -> FraudVerdict.REVIEW; // REVIEW, or anything we don't understand: let a human decide
        };
    }
}
