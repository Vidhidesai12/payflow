# AI usage notes

An honest record of how AI coding tools were used on this project. Fill these in as you go. Interviewers may ask, and specifics are more convincing than "I used AI".

## Step 1: payment-service (2026-11-17)

**Generated with Claude Code:** the project skeleton (from Spring Initializr), the Flyway migration, the entity and status rules, the idempotency logic, the controller and error handling, all tests, CI and this README.

**Design decisions to be able to explain:**
- *Why the idempotency check is both in code AND a unique constraint:* the code check alone has a race. Two requests can both see "no payment yet" and both insert. The constraint makes the database refuse the second insert. The service catches that error and returns the winner's payment. The concurrency test reproduces this race. PostgreSQL logs duplicate-key rejections during the run, which shows the code path is really exercised.
- *Why `PaymentService.create` is not `@Transactional`:* if it were, the failed insert would mark the whole transaction as rollback-only, and the follow-up read of the winning row would fail.
- *Why `open-in-view: false`:* otherwise one Hibernate session spans the whole HTTP request, and the session is left unusable after the failed insert above.
- *Why a request hash:* so a reused key with a different amount is rejected (422), not silently treated as a retry.
- *Why `BigDecimal` and `stripTrailingZeros` in the hash:* `100` and `100.00` are the same amount and should count as the same request.

**What I reviewed / changed myself:**
- _TODO: write down what you read, questioned, or rewrote._

**Bugs caught:**
- _TODO_

## Steps 2-5: Kafka, ledger, security, resilience, reconciliation (2026-11-17)

**Generated with Claude Code:** ledger-service and fraud-service in full; the outbox, Kafka listeners and dead-letter setup; maker-checker and Spring Security; the Resilience4j wiring; reconciliation; the Flyway migrations V2-V4; Dockerfile, compose profiles, Prometheus/Grafana config, CI matrix; all new tests; the README.

**Design decisions to be able to explain:**
- *Transactional outbox instead of sending to Kafka from the service:* a DB commit and a Kafka send can't be one atomic step. Writing the event to an `outbox_events` table in the same transaction as the payment means both happen or neither does. A scheduled relay publishes the rows. Delivery is therefore at-least-once.
- *Why consumers must be idempotent:* because of at-least-once delivery. The ledger claims each event ID with `INSERT ... ON CONFLICT DO NOTHING` inside the booking transaction, and `UNIQUE (payment_id)` on postings is a second guard. payment-service uses the payment's own state instead: an already-SETTLED payment ignores a repeated `LedgerPosted`.
- *`FOR UPDATE SKIP LOCKED` in the relay:* two relay instances never send the same row at the same time, and neither waits on the other.
- *Kafka key = payment ID:* all events for one payment land on one partition, so they stay in order.
- *Dead-letter topics:* a message that keeps failing is moved to `<topic>.DLT` after 3 retries, so it can't block the partition. Unparseable messages skip the retries.
- *Ledger is double-entry and append-only, enforced by PostgreSQL:* a deferred constraint trigger checks debits = credits at COMMIT, and other triggers block UPDATE/DELETE/TRUNCATE. The application can't break these rules even with a bug.
- *Idempotency keys scoped per user* (V3 changes the unique constraint to `(created_by, idempotency_key)`): otherwise maker2 reusing maker1's key would receive maker1's payment.
- *Reject only from PENDING_APPROVAL:* once a payment is APPROVED, the ledger may already be booking it. Cancelling then would leave the two services disagreeing.
- *The fraud check runs outside the DB transaction:* a network call inside a transaction holds a DB connection while waiting.
- *Resilience4j core modules, not its Spring Boot starter:* the starter targets Spring Boot 3. The core library is plain Java, and the decoration order (circuit breaker around retry around the HTTP call with timeouts) is explicit in `FraudCheckClient`.
- *Fail safe:* if fraud-service is down, payments are held for a human. They are never approved blindly, and never failed just because a dependency is down.

**What I reviewed / changed myself:**
- _TODO_

**Bugs caught during this step (found by the tests, fixed by Claude Code):**
- A test read a JSON number into a `Map`, which turned `-125.50` into the double `-125.5`. Fixed by comparing as `BigDecimal`.
- The unbalanced-posting test expected the trigger's message on the top-level exception. Spring wraps it, so the check now looks at the root cause.
- payment-service failed to start because in Spring Boot 4 `RestClient.Builder` lives in a separate `spring-boot-starter-restclient` dependency.
- Found in the live Docker run, not by tests: the counter `payflow.payments.created` showed up in Prometheus as `payflow_payments_total`, because `_created` is a reserved suffix in the OpenMetrics format. The Grafana panel was empty until it was renamed to `payflow.payments.routed`.
- Found in the live Docker run: ports 5432/5433 clashed with PostgreSQL installed on the laptop. Compose now maps the databases to 15432/15433 by default (overridable with `PAYFLOW_DB_PORT` / `LEDGER_DB_PORT`).
- **Found by watching the Grafana dashboard (the most interesting bug):** the circuit breaker opened about 2 seconds into a normal traffic run, before any outage was simulated, and held a minute of good payments for manual review. Cause: the retry wrapped the circuit breaker, so the breaker counted every failed *attempt*, including ones the next retry fixed. After the laptop had slept overnight, a few first calls failed (stale connections or a cold container; it couldn't be reproduced on demand), retries recovered them, but 3 failed attempts out of the first 5 were enough to open the circuit. Fix: the breaker now wraps the retry, so it only counts checks that still fail after all retries. Added a regression test (half of all attempts fail, every retry succeeds, the circuit must stay closed), confirmed it fails with the old order and passes with the new one, and added a log line for every retry so the cause is visible next time. A re-run of the same traffic showed the circuit opening only for the real outage.
- A load test right after the chaos demo "lost" 74 of 200 payments. Not a bug: the circuit breaker was still open for its 30 s window, so those payments were correctly held for manual review. Re-run with the circuit closed: 200/200 booked.
