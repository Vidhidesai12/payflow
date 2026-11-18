package com.payflow.fraud;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class FraudController {

    record ChaosRequest(@DecimalMin("0") @DecimalMax("1") double failureRate, @Min(0) long delayMs) {
    }

    private final FraudRules rules;
    private final Chaos chaos;

    FraudController(FraudRules rules, Chaos chaos) {
        this.rules = rules;
        this.chaos = chaos;
    }

    @PostMapping("/fraud-checks")
    FraudCheckResponse check(@Valid @RequestBody FraudCheckRequest request) {
        chaos.apply();
        return rules.assess(request);
    }

    /** An injected failure looks like an overloaded dependency: 503 Service Unavailable. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unavailable(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @GetMapping("/chaos")
    Chaos.Settings chaos() {
        return chaos.get();
    }

    /** Example: {"failureRate": 1.0, "delayMs": 0} makes every check fail. */
    @PutMapping("/chaos")
    Chaos.Settings setChaos(@Valid @RequestBody ChaosRequest request) {
        chaos.set(new Chaos.Settings(request.failureRate(), request.delayMs()));
        return chaos.get();
    }
}
