package com.payflow.payment.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("payflow.fraud")
public record FraudProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration initialBackoff,
        float failureRateThreshold,
        int slidingWindowSize,
        int minimumCalls,
        Duration waitInOpenState) {
}
