package com.payflow.payment.service;

import com.payflow.payment.domain.FraudVerdict;
import com.payflow.payment.domain.Payment;
import com.payflow.payment.domain.PaymentRepository;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.fraud.FraudCheckClient;
import com.payflow.payment.messaging.PaymentEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final FraudCheckClient fraudCheck;
    private final PaymentEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BigDecimal approvalThreshold;
    private final MeterRegistry meters;

    public PaymentService(PaymentRepository repository, FraudCheckClient fraudCheck, PaymentEventPublisher events,
                          TransactionTemplate tx, Clock clock, MeterRegistry meters,
                          @Value("${payflow.approval-threshold}") BigDecimal approvalThreshold) {
        this.repository = repository;
        this.fraudCheck = fraudCheck;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        this.approvalThreshold = approvalThreshold;
    }

    /**
     * Creates a payment exactly once per (user, idempotency key).
     *
     * <p>Deliberately NOT @Transactional as a whole. The fraud check is a network call, so it happens
     * outside any database transaction. The insert and its outbox event then commit together in one
     * short transaction. If a concurrent request with the same key wins the race, that transaction
     * fails on the unique constraint and we read the winner's row in a fresh one.
     */
    public CreatePaymentResult create(String user, String idempotencyKey, PaymentCommand command) {
        String requestHash = RequestHasher.hash(command);

        Optional<Payment> existing = repository.findByCreatedByAndIdempotencyKey(user, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        Payment payment = Payment.create(idempotencyKey, requestHash, command.debtorAccount(),
                command.creditorAccount(), command.amount(), command.currency(), user, clock.instant());
        FraudVerdict verdict = fraudCheck.check(payment);
        payment.route(approvalThreshold, verdict, clock.instant());

        try {
            tx.executeWithoutResult(status -> {
                repository.saveAndFlush(payment);
                events.statusChanged(payment);
            });
        } catch (DataIntegrityViolationException e) {
            // Another request with the same key was inserted between our check and our insert.
            Payment winner = repository.findByCreatedByAndIdempotencyKey(user, idempotencyKey).orElseThrow(() -> e);
            return replay(winner, requestHash);
        }
        // Not "...created": Prometheus reserves the _created suffix and would rename it.
        meters.counter("payflow.payments.routed", "status", payment.getStatus().name(),
                "fraud", verdict.name()).increment();
        return new CreatePaymentResult(payment, true);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Payment> listRecent(PaymentStatus status) {
        return status == null
                ? repository.findTop50ByOrderByCreatedAtDesc()
                : repository.findTop50ByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public Payment approve(UUID id, String checker) {
        return update(id, p -> p.approve(checker, clock.instant()));
    }

    @Transactional
    public Payment reject(UUID id, String checker, String reason) {
        return update(id, p -> p.reject(checker, reason, clock.instant()));
    }

    /**
     * Called for each LedgerPosted event. Kafka may deliver the same event twice, so a payment that is
     * already SETTLED is simply left alone (the state itself makes this idempotent).
     */
    @Transactional
    public void settleFromLedger(UUID paymentId, BigDecimal bookedAmount, String bookedCurrency) {
        Payment payment = repository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.SETTLED) {
            log.info("Payment {} is already settled; ignoring duplicate ledger event", paymentId);
            return;
        }
        if (payment.getAmount().compareTo(bookedAmount) != 0 || !payment.getCurrency().equals(bookedCurrency)) {
            throw new LedgerMismatchException(paymentId, "payment " + payment.getAmount() + " "
                    + payment.getCurrency() + ", ledger " + bookedAmount + " " + bookedCurrency);
        }
        update(paymentId, p -> p.settle(clock.instant()));
    }

    private Payment update(UUID id, Consumer<Payment> change) {
        Payment payment = get(id);
        change.accept(payment);
        // flush now so an optimistic-lock conflict surfaces here, not after the response is built
        Payment saved = repository.saveAndFlush(payment);
        events.statusChanged(saved);
        return saved;
    }

    private static CreatePaymentResult replay(Payment payment, String requestHash) {
        if (!payment.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(payment.getIdempotencyKey());
        }
        return new CreatePaymentResult(payment, false);
    }
}
