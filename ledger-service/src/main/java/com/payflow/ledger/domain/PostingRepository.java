package com.payflow.ledger.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Optional<Posting> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    List<Posting> findByPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAt(Instant from, Instant to);
}
