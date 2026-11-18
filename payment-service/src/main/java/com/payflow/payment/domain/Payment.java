package com.payflow.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    static final String HOLD_ABOVE_THRESHOLD = "Amount is above the approval threshold";
    static final String HOLD_FRAUD_REVIEW = "Flagged for review by the fraud check";
    static final String HOLD_FRAUD_UNAVAILABLE = "Fraud check unavailable; manual review required";

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    /** SHA-256 of the request body, used to spot a reused key with a different payment. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "debtor_account", nullable = false, updatable = false, length = 34)
    private String debtorAccount;

    @Column(name = "creditor_account", nullable = false, updatable = false, length = 34)
    private String creditorAccount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Why the payment is waiting for a checker, if it is. */
    @Column(name = "hold_reason")
    private String holdReason;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    /** Optimistic lock: two people approving the same payment at once can't both win. */
    @Version
    private Long version;

    protected Payment() {
        // for JPA
    }

    public static Payment create(String idempotencyKey, String requestHash, String debtorAccount,
                                 String creditorAccount, BigDecimal amount, String currency,
                                 String createdBy, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.debtorAccount = debtorAccount;
        p.creditorAccount = creditorAccount;
        p.amount = amount;
        p.currency = currency;
        p.createdBy = createdBy;
        p.status = PaymentStatus.CREATED;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /**
     * Decides where a new payment goes. Fraud first: a denied payment fails, a doubtful one (or one we
     * could not check) waits for a human. Otherwise large payments wait and small ones are approved.
     */
    public void route(BigDecimal approvalThreshold, FraudVerdict fraud, Instant now) {
        switch (fraud) {
            case DENY -> fail("Blocked by the fraud check", now);
            case REVIEW -> hold(HOLD_FRAUD_REVIEW, now);
            case UNAVAILABLE -> hold(HOLD_FRAUD_UNAVAILABLE, now);
            case ALLOW -> {
                if (amount.compareTo(approvalThreshold) > 0) {
                    hold(HOLD_ABOVE_THRESHOLD, now);
                } else {
                    transitionTo(PaymentStatus.APPROVED, now);
                }
            }
        }
    }

    /** A checker approves a held payment. The maker can't approve their own. */
    public void approve(String checker, Instant now) {
        if (status != PaymentStatus.PENDING_APPROVAL) {
            throw new InvalidStatusTransitionException(status, PaymentStatus.APPROVED);
        }
        if (checker.equals(createdBy)) {
            throw new SelfApprovalException(checker);
        }
        transitionTo(PaymentStatus.APPROVED, now);
        this.approvedBy = checker;
    }

    /**
     * A checker turns down a held payment. Only held payments: once APPROVED, the ledger may already be
     * booking it, so cancelling would leave the two systems disagreeing.
     */
    public void reject(String checker, String reason, Instant now) {
        if (status != PaymentStatus.PENDING_APPROVAL) {
            throw new InvalidStatusTransitionException(status, PaymentStatus.FAILED);
        }
        fail("Rejected by " + checker + ": " + reason, now);
    }

    /** Called when the ledger confirms the money has been booked. */
    public void settle(Instant now) {
        transitionTo(PaymentStatus.SETTLED, now);
        this.settledAt = now;
    }

    public void fail(String reason, Instant now) {
        transitionTo(PaymentStatus.FAILED, now);
        this.failureReason = reason;
    }

    private void hold(String reason, Instant now) {
        transitionTo(PaymentStatus.PENDING_APPROVAL, now);
        this.holdReason = reason;
    }

    private void transitionTo(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getDebtorAccount() { return debtorAccount; }
    public String getCreditorAccount() { return creditorAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getHoldReason() { return holdReason; }
    public String getCreatedBy() { return createdBy; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getSettledAt() { return settledAt; }
}
