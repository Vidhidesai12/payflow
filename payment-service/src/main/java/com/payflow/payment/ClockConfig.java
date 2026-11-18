package com.payflow.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /** Injected rather than calling Instant.now() directly, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
