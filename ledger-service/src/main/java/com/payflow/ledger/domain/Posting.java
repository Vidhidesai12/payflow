package com.payflow.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One balanced money movement for one payment. Never updated after it is written. */
@Entity
@Table(name = "postings")
@Immutable
public class Posting {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "debtor_account", nullable = false, length = 34)
    private String debtorAccount;

    @Column(name = "creditor_account", nullable = false, length = 34)
    private String creditorAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected Posting() {
        // for JPA
    }

    public static Posting of(UUID paymentId, String debtorAccount, String creditorAccount,
                             BigDecimal amount, String currency, Instant now) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (debtorAccount.equals(creditorAccount)) {
            throw new IllegalArgumentException("Debtor and creditor must differ");
        }
        Posting p = new Posting();
        p.id = UUID.randomUUID();
        p.paymentId = paymentId;
        p.debtorAccount = debtorAccount;
        p.creditorAccount = creditorAccount;
        p.amount = amount;
        p.currency = currency;
        p.postedAt = now;
        return p;
    }

    /** The double entry: money leaves the debtor and arrives at the creditor, same amount. */
    public List<LedgerEntry> entries() {
        return List.of(
                LedgerEntry.of(id, debtorAccount, Direction.DEBIT, amount, currency, postedAt),
                LedgerEntry.of(id, creditorAccount, Direction.CREDIT, amount, currency, postedAt));
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getDebtorAccount() { return debtorAccount; }
    public String getCreditorAccount() { return creditorAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getPostedAt() { return postedAt; }
}
