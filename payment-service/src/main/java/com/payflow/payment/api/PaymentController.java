package com.payflow.payment.api;

import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.service.CreatePaymentResult;
import com.payflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.security.Principal;

import java.util.List;
import java.util.UUID;

/** Who may call what is decided in SecurityConfig; this class only uses the caller's name. */
@RestController
@RequestMapping("/api/payments")
class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 100;

    private final PaymentService service;

    PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * Returns 201 the first time a key is seen. A retry with the same key and body returns 200
     * with the original payment, so a client that times out can safely send the request again.
     */
    @PostMapping
    ResponseEntity<PaymentResponse> create(Principal user,
                                           @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                           @Valid @RequestBody CreatePaymentRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    IDEMPOTENCY_KEY + " must be 1-" + MAX_KEY_LENGTH + " characters");
        }

        CreatePaymentResult result = service.create(user.getName(), idempotencyKey, request.toCommand());
        PaymentResponse body = PaymentResponse.from(result.payment());

        if (!result.created()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(body);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(service.get(id));
    }

    @GetMapping
    List<PaymentResponse> list(@RequestParam(required = false) PaymentStatus status) {
        return service.listRecent(status).stream().map(PaymentResponse::from).toList();
    }

    /** CHECKER role only, and never the payment's own creator (maker-checker). */
    @PostMapping("/{id}/approve")
    PaymentResponse approve(Principal checker, @PathVariable UUID id) {
        return PaymentResponse.from(service.approve(id, checker.getName()));
    }

    @PostMapping("/{id}/reject")
    PaymentResponse reject(Principal checker, @PathVariable UUID id,
                           @Valid @RequestBody RejectPaymentRequest request) {
        return PaymentResponse.from(service.reject(id, checker.getName(), request.reason()));
    }

    // There is no /settle endpoint any more: a payment becomes SETTLED only when ledger-service
    // confirms the booking over Kafka (see LedgerEventListener).
}
