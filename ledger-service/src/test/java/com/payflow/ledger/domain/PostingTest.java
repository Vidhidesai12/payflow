package com.payflow.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Test
    void entriesDebitTheDebtorAndCreditTheCreditorForTheSameAmount() {
        Posting p = Posting.of(UUID.randomUUID(), "ACC1001", "ACC2002", new BigDecimal("150.00"), "USD", NOW);

        List<LedgerEntry> entries = p.entries();

        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(e -> e.getDirection() == Direction.DEBIT)
                .singleElement().satisfies(e -> {
                    assertThat(e.getAccount()).isEqualTo("ACC1001");
                    assertThat(e.getAmount()).isEqualByComparingTo("150.00");
                });
        assertThat(entries).filteredOn(e -> e.getDirection() == Direction.CREDIT)
                .singleElement().satisfies(e -> assertThat(e.getAccount()).isEqualTo("ACC2002"));
        assertThat(entries).allSatisfy(e -> assertThat(e.getPostingId()).isEqualTo(p.getId()));
    }

    @Test
    void amountMustBePositive() {
        assertThatThrownBy(() -> Posting.of(UUID.randomUUID(), "ACC1001", "ACC2002", BigDecimal.ZERO, "USD", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountsMustDiffer() {
        assertThatThrownBy(() -> Posting.of(UUID.randomUUID(), "ACC1001", "ACC1001", BigDecimal.TEN, "USD", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
