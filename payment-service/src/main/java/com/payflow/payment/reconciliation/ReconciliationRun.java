package com.payflow.payment.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public enum Status { MATCHED, MISMATCHED, FAILED }

    @Id
    private UUID id;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "payments_checked", nullable = false)
    private int paymentsChecked;

    @Column(name = "postings_checked", nullable = false)
    private int postingsChecked;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    /** JSON array of mismatches, or the error message when the run FAILED. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String details;

    protected ReconciliationRun() {
        // for JPA
    }

    ReconciliationRun(Instant windowStart, Instant windowEnd, Instant startedAt, Status status,
                      int paymentsChecked, int postingsChecked, int mismatchCount, String details) {
        this.id = UUID.randomUUID();
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.startedAt = startedAt;
        this.status = status;
        this.paymentsChecked = paymentsChecked;
        this.postingsChecked = postingsChecked;
        this.mismatchCount = mismatchCount;
        this.details = details;
    }

    public UUID getId() { return id; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public Instant getStartedAt() { return startedAt; }
    public Status getStatus() { return status; }
    public int getPaymentsChecked() { return paymentsChecked; }
    public int getPostingsChecked() { return postingsChecked; }
    public int getMismatchCount() { return mismatchCount; }
    public String getDetails() { return details; }
}
