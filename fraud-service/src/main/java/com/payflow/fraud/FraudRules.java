package com.payflow.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Stand-in for a real fraud engine. Real engines score behaviour (velocity, device, geography);
 * this one uses fixed lists so tests and demos are predictable.
 */
@ConfigurationProperties("fraud")
public record FraudRules(Set<String> blockedAccounts, Set<String> watchlistAccounts) {

    public FraudRules {
        blockedAccounts = blockedAccounts == null ? Set.of() : Set.copyOf(blockedAccounts);
        watchlistAccounts = watchlistAccounts == null ? Set.of() : Set.copyOf(watchlistAccounts);
    }

    public FraudCheckResponse assess(FraudCheckRequest request) {
        if (blockedAccounts.contains(request.debtorAccount()) || blockedAccounts.contains(request.creditorAccount())) {
            return new FraudCheckResponse(FraudCheckResponse.Decision.DENY, "Account is on the block list");
        }
        if (watchlistAccounts.contains(request.debtorAccount()) || watchlistAccounts.contains(request.creditorAccount())) {
            return new FraudCheckResponse(FraudCheckResponse.Decision.REVIEW, "Account is on the watch list");
        }
        return new FraudCheckResponse(FraudCheckResponse.Decision.ALLOW, null);
    }
}
