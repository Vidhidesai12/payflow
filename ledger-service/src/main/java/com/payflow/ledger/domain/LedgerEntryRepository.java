package com.payflow.ledger.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByPostingIdIn(Collection<UUID> postingIds);

    long countByPostingId(UUID postingId);
}
