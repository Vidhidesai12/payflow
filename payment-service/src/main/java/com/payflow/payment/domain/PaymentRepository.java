package com.payflow.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Keys are scoped to the user who sent them. */
    Optional<Payment> findByCreatedByAndIdempotencyKey(String createdBy, String idempotencyKey);

    long countByCreatedByAndIdempotencyKey(String createdBy, String idempotencyKey);

    List<Payment> findTop50ByOrderByCreatedAtDesc();

    List<Payment> findTop50ByStatusOrderByCreatedAtDesc(PaymentStatus status);

    /** For reconciliation: payments the ledger confirmed in [from, to). */
    List<Payment> findByStatusAndSettledAtGreaterThanEqualAndSettledAtLessThan(PaymentStatus status,
                                                                               Instant from, Instant to);
}
