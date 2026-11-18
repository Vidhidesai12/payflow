package com.payflow.payment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("10000.00");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Payment payment(String amount) {
        return Payment.create("key", "hash", "ACC111111", "ACC222222", new BigDecimal(amount), "USD", "maker1", NOW);
    }

    private static Payment routed(String amount, FraudVerdict verdict) {
        Payment p = payment(amount);
        p.route(THRESHOLD, verdict, NOW);
        return p;
    }

    // ---- routing ----------------------------------------------------------------

    @Test
    void paymentAtThresholdIsApprovedAutomatically() {
        assertThat(routed("10000.00", FraudVerdict.ALLOW).getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void paymentAboveThresholdWaitsForApproval() {
        Payment p = routed("10000.01", FraudVerdict.ALLOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        assertThat(p.getHoldReason()).isEqualTo(Payment.HOLD_ABOVE_THRESHOLD);
    }

    @Test
    void fraudDenialFailsThePayment() {
        Payment p = routed("10", FraudVerdict.DENY);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("Blocked by the fraud check");
    }

    @Test
    void fraudReviewHoldsEvenASmallPayment() {
        Payment p = routed("10", FraudVerdict.REVIEW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        assertThat(p.getHoldReason()).isEqualTo(Payment.HOLD_FRAUD_REVIEW);
    }

    @Test
    void unavailableFraudCheckHoldsThePaymentInsteadOfApprovingIt() {
        Payment p = routed("10", FraudVerdict.UNAVAILABLE);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
        assertThat(p.getHoldReason()).isEqualTo(Payment.HOLD_FRAUD_UNAVAILABLE);
    }

    // ---- approval (maker-checker) -------------------------------------------------

    @Test
    void anotherUserCanApproveAndIsRecorded() {
        Payment p = routed("50000", FraudVerdict.ALLOW);
        p.approve("checker1", NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(p.getApprovedBy()).isEqualTo("checker1");
    }

    @Test
    void creatorCannotApproveTheirOwnPayment() {
        Payment p = routed("50000", FraudVerdict.ALLOW);
        assertThatThrownBy(() -> p.approve("maker1", NOW)).isInstanceOf(SelfApprovalException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING_APPROVAL);
    }

    @Test
    void cannotApprovePaymentThatNeverNeededApproval() {
        Payment p = routed("10", FraudVerdict.ALLOW);
        assertThatThrownBy(() -> p.approve("checker1", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void rejectionRecordsWhoAndWhy() {
        Payment p = routed("50000", FraudVerdict.ALLOW);
        p.reject("checker1", "suspicious", NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("Rejected by checker1: suspicious");
    }

    @Test
    void approvedPaymentCannotBeRejected() {
        Payment p = routed("10", FraudVerdict.ALLOW);
        assertThatThrownBy(() -> p.reject("checker1", "too late", NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // ---- settlement ---------------------------------------------------------------

    @Test
    void approvedPaymentCanSettle() {
        Payment p = routed("50000", FraudVerdict.ALLOW);
        p.approve("checker1", NOW);
        p.settle(NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(p.getSettledAt()).isEqualTo(NOW);
    }

    @Test
    void cannotSettleBeforeApproval() {
        Payment p = routed("50000", FraudVerdict.ALLOW);
        assertThatThrownBy(() -> p.settle(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SETTLED", "FAILED"})
    void finalStatusesCannotChange(PaymentStatus finalStatus) {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(finalStatus.canTransitionTo(next)).as("%s -> %s", finalStatus, next).isFalse();
        }
    }
}
