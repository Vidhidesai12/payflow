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

## MCP server (2026-11-18)

**Generated with Claude Code:** the `mcp-server` module (tools, security, audit log, tests), the payment-service service account, Docker/CI wiring, and the README section.

**Design decisions to be able to explain:**
- *No approve tool at all:* the strongest defence against prompt injection is capability. Even an AI that is fooled cannot approve a payment, because no tool exists for it and the service account has no CHECKER role.
- *Human confirmation through elicitation:* `create_payment` asks the person to confirm amount and accounts inside their AI client. If the client cannot ask, the payment is refused (fail safe, as with the fraud check).
- *Per-client tokens with scopes, hashed at rest:* least privilege per client; only SHA-256 hashes live in config; constant-time comparison.
- *No token passthrough:* the server uses its own PayFlow service account and never forwards the client's token.
- *Untrusted text labelling:* free text typed by people is cleaned (control/invisible characters removed, length capped) and returned as `untrustedText`.
- *Own transport bean:* Spring AI's MCP starter adds no authentication. The caller's identity is captured on the HTTP request thread and passed to each tool call through the MCP transport context.
- *Spec version:* the Java SDK in Spring AI 2.0.2 speaks MCP up to 2025-11-25, not yet the 2026-07-28 spec (stateless core, multi-round-trip elicitation). Noted as a known limit.

**Bugs caught during this step:**
- Rejecting a foreign Origin with `sendError(403)` came back as 401, because the error page itself is behind security. Fixed by writing the 403 response directly.
- The agent limit `1000.00` in YAML was read as the float `1000.0`. Fixed by quoting it, so money stays an exact decimal.
- Found in the live screenshot: every error message reached the client twice. Throwing from a tool made Spring AI send two identical text blocks. Tools now return the MCP result object directly (one block, `isError: true`), and a test checks there is exactly one.
- A first live check failed in the test script, not the server: the server answers some calls as an event stream, which the script read incorrectly.

## MCP gateway (TypeScript) and analytics server (Python) (2026-11-18)

**Generated with Claude Code:** `analytics-mcp` (tools, auth, tests), `mcp-gateway` (policy, pinning, redaction, audit chain, elicitation relay, metrics, tests, demo and audit scripts), the `analytics-agent` account, Dockerfiles, Compose and CI wiring, and the README section.

**What I reviewed / changed myself:**
- _TODO_

**Design decisions to be able to explain:**
- *Why a gateway at all:* MCP servers each enforce their own rules, but nobody sees all tool traffic in one place. The gateway is a single checkpoint for policy, rate limits, secret masking and audit. It is the same idea as an API gateway, applied to AI tool calls.
- *Tool pinning against tool poisoning and rug pulls:* a malicious or compromised server can change a tool's description into hidden instructions for the model after it was approved. The gateway fingerprints each tool definition (SHA-256 over canonical JSON) and quarantines anything new or changed until a person re-approves it with `npm run pin`. `pins.json` is committed, so every change is a reviewable diff. The phrase scan is only a heuristic; pinning is the control.
- *No confused deputy:* clients never get upstream tokens. Each client policy names the upstream identity it uses, so a read-only client reaches PayFlow with PayFlow's read-only token. The gateway and PayFlow both enforce limits (gateway: 500 for the operator, PayFlow: 1,000 for everyone), which is defence in depth.
- *Elicitation relay:* the gateway is an MCP server to the client and an MCP client to each upstream, with one upstream connection per client session, so PayFlow's confirmation question reaches the right person. The gateway only advertises elicitation upstream if the person's client supports it, so PayFlow's fail-safe still works through the gateway.
- *Hash-chained audit log:* each record includes the previous record's hash. That makes edits and deletions detectable (`npm run verify-audit`) with a plain file, no database. Limit: someone who can rewrite the whole file can rebuild the chain, so production would also ship records to write-once storage.
- *Least privilege for analytics:* the Python server uses a new `analytics-agent` account with only the AUDITOR role. Verified live: reconciliation reads return 200, creating a payment returns 403.
- *Three languages, one protocol:* Java (Spring AI), Python (MCP SDK 2.3) and TypeScript (MCP SDK 1.32) all speak MCP 2025-11-25 over Streamable HTTP. The live run through the gateway showed they interoperate, including elicitation from Java via TypeScript to the client.

**Bugs caught during this step:**
- *Found by the gateway tests:* the canonical JSON used for fingerprints and audit hashes wrote fields with an `undefined` value, but `JSON.stringify` drops them when a record is saved. So every saved audit log failed its own check, and a tool without a `title` would have been quarantined after a restart, because its fingerprint changed after a round trip through `pins.json`. Fixed by skipping undefined fields exactly like JSON does.
- *Found by the Python tests:* `largest_movements` fetched data from PayFlow before validating `limit`, so a bad request still hit PayFlow. It now validates first.
- *Found in the live run, not by tests:* the analytics server reported "PayFlow is unavailable". Its default URLs used `localhost`, which on this Windows laptop resolves to the WSL port relay, and that drops the connection. Defaults are now `127.0.0.1`, as in the rest of the project. Unit tests couldn't catch this because they use a fake PayFlow.
- *Found in the Docker run:* the gateway started before the Java server was listening, so PayFlow's tools were missing for up to a minute (the 60 s catalog refresh then recovered by itself). The Java server now has a Compose healthcheck and the gateway waits for it. The gateway also logs why an upstream is unreachable; before, it swallowed the error.
- *Found in the Docker build:* `npm ci` on Linux failed because the lockfile made on Windows left out optional packages for other platforms. Regenerated the lockfile from scratch; CI runs `npm ci` on Linux, so this would have failed there too.
- *Dependency hygiene:* `npm audit` flagged 2 critical and 1 moderate advisories in the test runner (vitest 3.2.7 and its worker pool). They never ship in the gateway, but a security project shouldn't carry known-vulnerable tooling, so it was upgraded to vitest 5.1.0 (not 5.1.1, which had been released the same day). CI now fails on high-severity advisories in runtime dependencies.
- *Not a code bug:* vitest 5 refused to run from this VS Code window because the working directory used a lowercase `c:` drive letter, which loads vitest twice. Running from `C:\` works; Linux CI is unaffected.
