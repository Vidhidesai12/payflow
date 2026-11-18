package com.payflow.ledger.api;

import com.payflow.ledger.domain.LedgerEntry;
import com.payflow.ledger.domain.LedgerEntryRepository;
import com.payflow.ledger.domain.Posting;
import com.payflow.ledger.domain.PostingRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only views of the ledger. Writes only ever come from Kafka events.
 * Internal API: in production it would sit behind service-to-service authentication (mTLS or tokens).
 */
@RestController
@RequestMapping("/api/ledger")
@Transactional(readOnly = true)
class LedgerController {

    private static final Duration MAX_WINDOW = Duration.ofDays(31);

    record EntryView(String account, String direction, BigDecimal amount, String currency) {
    }

    record PostingView(UUID postingId, UUID paymentId, BigDecimal amount, String currency, Instant postedAt,
                       BigDecimal debitTotal, BigDecimal creditTotal, List<EntryView> entries) {
    }

    record BalanceView(String currency, BigDecimal balance) {
    }

    record TrialBalanceView(String currency, BigDecimal debits, BigDecimal credits, boolean balanced) {
    }

    private final PostingRepository postings;
    private final LedgerEntryRepository entries;
    private final JdbcClient jdbc;

    LedgerController(PostingRepository postings, LedgerEntryRepository entries, JdbcClient jdbc) {
        this.postings = postings;
        this.entries = entries;
        this.jdbc = jdbc;
    }

    @GetMapping("/postings/{paymentId}")
    PostingView posting(@PathVariable UUID paymentId) {
        Posting p = postings.findByPaymentId(paymentId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No posting for payment " + paymentId));
        return view(p, entries.findByPostingIdIn(List.of(p.getId())));
    }

    /** Postings booked in [from, to). Used by payment-service's nightly reconciliation. */
    @GetMapping("/postings")
    List<PostingView> postings(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be before 'to', at most 31 days apart");
        }
        List<Posting> found = postings.findByPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAt(from, to);
        Map<UUID, List<LedgerEntry>> byPosting = entries.findByPostingIdIn(found.stream().map(Posting::getId).toList())
                .stream().collect(Collectors.groupingBy(LedgerEntry::getPostingId));
        return found.stream().map(p -> view(p, byPosting.getOrDefault(p.getId(), List.of()))).toList();
    }

    /** Balance = credits - debits. Accounts can go negative here: overdraft rules are out of scope. */
    @GetMapping("/accounts/{account}/balances")
    List<BalanceView> balances(@PathVariable String account) {
        return jdbc.sql("""
                        SELECT currency, SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS balance
                          FROM ledger_entries
                         WHERE account = :account
                         GROUP BY currency
                         ORDER BY currency""")
                .param("account", account)
                .query((rs, n) -> new BalanceView(rs.getString("currency"), rs.getBigDecimal("balance")))
                .list();
    }

    /** Accounting's basic health check: across the whole ledger, debits must equal credits. */
    @GetMapping("/trial-balance")
    List<TrialBalanceView> trialBalance() {
        return jdbc.sql("""
                        SELECT currency,
                               SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END) AS debits,
                               SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS credits
                          FROM ledger_entries
                         GROUP BY currency
                         ORDER BY currency""")
                .query((rs, n) -> {
                    BigDecimal d = rs.getBigDecimal("debits");
                    BigDecimal c = rs.getBigDecimal("credits");
                    return new TrialBalanceView(rs.getString("currency"), d, c, d.compareTo(c) == 0);
                })
                .list();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail error(ResponseStatusException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason());
    }

    private static PostingView view(Posting p, List<LedgerEntry> list) {
        List<EntryView> views = list.stream()
                .sorted(Comparator.comparing(LedgerEntry::getDirection)) // DEBIT first
                .map(e -> new EntryView(e.getAccount(), e.getDirection().name(), e.getAmount(), e.getCurrency()))
                .toList();
        return new PostingView(p.getId(), p.getPaymentId(), p.getAmount(), p.getCurrency(), p.getPostedAt(),
                total(list, "DEBIT"), total(list, "CREDIT"), views);
    }

    private static BigDecimal total(List<LedgerEntry> list, String direction) {
        return list.stream().filter(e -> e.getDirection().name().equals(direction))
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
