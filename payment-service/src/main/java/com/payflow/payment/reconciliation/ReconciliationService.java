package com.payflow.payment.reconciliation;

import com.payflow.payment.domain.Payment;
import com.payflow.payment.domain.PaymentRepository;
import com.payflow.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks that payment-service and ledger-service agree, the way banks reconcile daily.
 *
 * <p>For a time window it compares:
 * <ul>
 *   <li>every payment SETTLED in the window has a ledger posting with the same amount and currency;</li>
 *   <li>every ledger posting in the window is balanced and belongs to a SETTLED payment.</li>
 * </ul>
 * Postings are fetched with a margin either side, because a payment is settled a moment after it is
 * posted and the two timestamps can fall on opposite sides of midnight.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration MARGIN = Duration.ofHours(1);

    public enum Kind {
        /** Payment says SETTLED, but the ledger has no posting for it. */
        MISSING_IN_LEDGER,
        /** Both exist but disagree on amount or currency. */
        AMOUNT_MISMATCH,
        /** The ledger booked money for a payment that is not SETTLED (yet, or ever). */
        NOT_SETTLED,
        /** The ledger has a posting for a payment we don't know at all. */
        UNKNOWN_PAYMENT,
        /** A posting whose debits and credits differ. */
        UNBALANCED_POSTING
    }

    public record Mismatch(Kind kind, UUID paymentId, String detail) {
    }

    private final PaymentRepository payments;
    private final LedgerClient ledger;
    private final ReconciliationRunRepository runs;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration settlementGrace;
    private final AtomicInteger lastMismatches;

    ReconciliationService(PaymentRepository payments, LedgerClient ledger, ReconciliationRunRepository runs,
                          JsonMapper json, Clock clock, MeterRegistry meters,
                          @Value("${payflow.reconciliation.settlement-grace}") Duration settlementGrace) {
        this.payments = payments;
        this.ledger = ledger;
        this.runs = runs;
        this.json = json;
        this.clock = clock;
        this.settlementGrace = settlementGrace;
        this.lastMismatches = meters.gauge("payflow.reconciliation.mismatches", new AtomicInteger());
    }

    /** Every night at 01:30 UTC, reconcile the previous UTC day. */
    @Scheduled(cron = "${payflow.reconciliation.cron}", zone = "UTC")
    void nightly() {
        LocalDate yesterday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        run(yesterday.atStartOfDay().toInstant(ZoneOffset.UTC),
                yesterday.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    public ReconciliationRun run(Instant from, Instant to) {
        Instant startedAt = clock.instant();
        ReconciliationRun run;
        try {
            List<Payment> settled = payments.findByStatusAndSettledAtGreaterThanEqualAndSettledAtLessThan(
                    PaymentStatus.SETTLED, from, to);
            List<LedgerClient.LedgerPosting> postings = ledger.postings(from.minus(MARGIN), to.plus(MARGIN));
            List<LedgerClient.LedgerPosting> inWindow = postings.stream()
                    .filter(p -> !p.postedAt().isBefore(from) && p.postedAt().isBefore(to)).toList();

            List<Mismatch> mismatches = compare(settled, postings, inWindow, startedAt);
            run = new ReconciliationRun(from, to, startedAt,
                    mismatches.isEmpty() ? ReconciliationRun.Status.MATCHED : ReconciliationRun.Status.MISMATCHED,
                    settled.size(), inWindow.size(), mismatches.size(), json.writeValueAsString(mismatches));
            lastMismatches.set(mismatches.size());
        } catch (RuntimeException e) {
            log.error("Reconciliation of {} to {} failed", from, to, e);
            run = new ReconciliationRun(from, to, startedAt, ReconciliationRun.Status.FAILED, 0, 0, 0,
                    String.valueOf(e.getMessage()));
        }
        runs.save(run);
        log.info("Reconciliation {} to {}: {} ({} mismatches)", from, to, run.getStatus(), run.getMismatchCount());
        return run;
    }

    public List<ReconciliationRun> recentRuns() {
        return runs.findTop20ByOrderByStartedAtDesc();
    }

    private List<Mismatch> compare(List<Payment> settled, List<LedgerClient.LedgerPosting> allPostings,
                                   List<LedgerClient.LedgerPosting> inWindow, Instant now) {
        List<Mismatch> found = new ArrayList<>();
        Map<UUID, LedgerClient.LedgerPosting> postingByPayment = allPostings.stream()
                .collect(Collectors.toMap(LedgerClient.LedgerPosting::paymentId, Function.identity(), (a, b) -> a));

        for (Payment p : settled) {
            LedgerClient.LedgerPosting posting = postingByPayment.get(p.getId());
            if (posting == null) {
                found.add(new Mismatch(Kind.MISSING_IN_LEDGER, p.getId(), "Settled " + p.getSettledAt()));
            } else if (p.getAmount().compareTo(posting.amount()) != 0 || !p.getCurrency().equals(posting.currency())) {
                found.add(new Mismatch(Kind.AMOUNT_MISMATCH, p.getId(), "Payment " + p.getAmount() + " "
                        + p.getCurrency() + ", ledger " + posting.amount() + " " + posting.currency()));
            }
        }

        Map<UUID, Payment> paymentById = payments.findAllById(inWindow.stream()
                        .map(LedgerClient.LedgerPosting::paymentId).toList())
                .stream().collect(Collectors.toMap(Payment::getId, Function.identity()));

        for (LedgerClient.LedgerPosting posting : inWindow) {
            if (posting.debitTotal().compareTo(posting.creditTotal()) != 0) {
                found.add(new Mismatch(Kind.UNBALANCED_POSTING, posting.paymentId(),
                        "Debits " + posting.debitTotal() + ", credits " + posting.creditTotal()));
            }
            Payment p = paymentById.get(posting.paymentId());
            if (p == null) {
                found.add(new Mismatch(Kind.UNKNOWN_PAYMENT, posting.paymentId(), "Posted " + posting.postedAt()));
            } else if (p.getStatus() != PaymentStatus.SETTLED && posting.postedAt().isBefore(now.minus(settlementGrace))) {
                // Within the grace period the LedgerPosted event may simply still be on its way.
                found.add(new Mismatch(Kind.NOT_SETTLED, p.getId(), "Payment is " + p.getStatus()));
            }
        }
        return found;
    }
}
