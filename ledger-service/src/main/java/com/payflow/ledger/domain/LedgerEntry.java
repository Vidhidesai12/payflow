package com.payflow.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Immutable
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "posting_id", nullable = false)
    private UUID postingId;

    @Column(nullable = false, length = 34)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    static LedgerEntry of(UUID postingId, String account, Direction direction, BigDecimal amount,
                          String currency, Instant now) {
        LedgerEntry e = new LedgerEntry();
        e.id = UUID.randomUUID();
        e.postingId = postingId;
        e.account = account;
        e.direction = direction;
        e.amount = amount;
        e.currency = currency;
        e.createdAt = now;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getPostingId() { return postingId; }
    public String getAccount() { return account; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
