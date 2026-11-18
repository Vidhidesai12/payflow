package com.payflow.fraud;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Fault injection for demos: makes this service slow or failing on purpose, so you can watch
 * payment-service's timeout, retry and circuit breaker react. Off by default.
 */
@Component
class Chaos {

    record Settings(double failureRate, long delayMs) {
    }

    private volatile Settings settings = new Settings(0, 0);

    Settings get() {
        return settings;
    }

    void set(Settings settings) {
        this.settings = settings;
    }

    /** Sleeps and/or throws according to the current settings. */
    void apply() {
        Settings s = settings;
        if (s.delayMs() > 0) {
            try {
                Thread.sleep(s.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (s.failureRate() > 0 && ThreadLocalRandom.current().nextDouble() < s.failureRate()) {
            throw new IllegalStateException("Injected failure (chaos mode)");
        }
    }
}
