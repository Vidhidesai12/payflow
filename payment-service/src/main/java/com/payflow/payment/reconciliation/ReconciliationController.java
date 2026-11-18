package com.payflow.payment.reconciliation;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** AUDITOR role only (see SecurityConfig). */
@RestController
@RequestMapping("/api/reconciliation/runs")
class ReconciliationController {

    record RunView(UUID id, Instant windowStart, Instant windowEnd, Instant startedAt, String status,
                   int paymentsChecked, int postingsChecked, int mismatchCount, Object details) {
    }

    private final ReconciliationService service;
    private final JsonMapper json;

    ReconciliationController(ReconciliationService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    /** Run now for [from, to). Defaults to the last 24 hours. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RunView run(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        if (!start.isBefore(end) || Duration.between(start, end).toDays() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be before 'to', at most 30 days apart");
        }
        return view(service.run(start, end));
    }

    @GetMapping
    List<RunView> recent() {
        return service.recentRuns().stream().map(this::view).toList();
    }

    private RunView view(ReconciliationRun r) {
        Object details = r.getStatus() == ReconciliationRun.Status.FAILED
                ? r.getDetails()
                : json.readValue(r.getDetails(), List.class);
        return new RunView(r.getId(), r.getWindowStart(), r.getWindowEnd(), r.getStartedAt(), r.getStatus().name(),
                r.getPaymentsChecked(), r.getPostingsChecked(), r.getMismatchCount(), details);
    }
}
