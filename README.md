# PayFlow

[![CI](https://github.com/Vidhidesai12/payflow/actions/workflows/ci.yml/badge.svg)](https://github.com/Vidhidesai12/payflow/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791)
![Kafka 4.2](https://img.shields.io/badge/Kafka-4.2-231F20)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

An event-driven payments system in Java 21 and Spring Boot 4. A personal project for learning how banks move money safely: no duplicate charges, no money booked twice, two-person approval for risky payments, a double-entry ledger, and a nightly check that everything agrees.

Everything runs locally in Docker with free tools. *Personal learning project; not used in production.*

**Start here if you're reviewing the code:**

| What | Where |
|---|---|
| Exactly-once payment creation under concurrent retries | [PaymentService.create](payment-service/src/main/java/com/payflow/payment/service/PaymentService.java) |
| Payment lifecycle and maker-checker rule | [Payment](payment-service/src/main/java/com/payflow/payment/domain/Payment.java), [PaymentStatus](payment-service/src/main/java/com/payflow/payment/domain/PaymentStatus.java) |
| Transactional outbox relay | [OutboxRelay](payment-service/src/main/java/com/payflow/payment/messaging/OutboxRelay.java) |
| Idempotent ledger consumer, double entry | [LedgerService](ledger-service/src/main/java/com/payflow/ledger/service/LedgerService.java) |
| Ledger rules enforced by PostgreSQL | [V1__create_ledger.sql](ledger-service/src/main/resources/db/migration/V1__create_ledger.sql) |
| Timeouts, retry, circuit breaker, fail-safe fallback | [FraudCheckClient](payment-service/src/main/java/com/payflow/payment/fraud/FraudCheckClient.java) |
| Reconciliation | [ReconciliationService](payment-service/src/main/java/com/payflow/payment/reconciliation/ReconciliationService.java) |
| The 20-request race test | [PaymentApiIntegrationTest](payment-service/src/test/java/com/payflow/payment/PaymentApiIntegrationTest.java) |

## Architecture

```mermaid
flowchart LR
    client([Client / demo.http]) -->|REST + HTTP Basic| PS[payment-service :8080]
    ai([AI assistant e.g. Claude]) -->|MCP, Streamable HTTP + token| MCP[mcp-server :8083]
    MCP -->|REST as mcp-agent| PS
    MCP -->|REST, read-only| LS
    MCP --- ADB[(PostgreSQL<br/>audit log)]
    PS -->|REST, timeout + retry + circuit breaker| FS[fraud-service :8082]
    PS --- PDB[(PostgreSQL<br/>payments + outbox)]
    PS -->|outbox relay| K{{Kafka<br/>payments.events}}
    K --> LS[ledger-service :8081]
    LS --- LDB[(PostgreSQL<br/>ledger + outbox)]
    LS -->|outbox relay| K2{{Kafka<br/>ledger.events}}
    K2 -->|LedgerPosted| PS
    PS -.->|nightly reconciliation| LS
    K -.->|poison messages| DLT{{*.DLT topics}}
```

1. A **maker** creates a payment. payment-service checks the `Idempotency-Key`, asks **fraud-service** for a verdict, and routes the payment: approve, hold for a checker, or fail.
2. The payment row and a `PaymentApproved` event are written in **one database transaction** (transactional outbox). A relay then publishes the event to Kafka.
3. **ledger-service** books it as a balanced **debit and credit**, skipping events it has already processed, and publishes `LedgerPosted` the same way.
4. payment-service marks the payment **SETTLED**.
5. Every night, **reconciliation** compares settled payments with ledger postings and records any mismatch.
6. AI assistants connect through **mcp-server** (Model Context Protocol). They can read payments, the ledger and reconciliation, and create small payments after the person confirms. They can never approve.

## Screenshots

All captured from the running system on 2026-11-18 (how: [docs/screenshots](docs/screenshots)).

**Grafana dashboard during a 4-minute run with a fraud-service outage in the middle.** Approved payments (green) drop and held payments (yellow) rise while the fraud check is down, then recover. The ledger follows the same curve, and nothing is dead-lettered or mismatched.

![Grafana PayFlow overview dashboard](docs/screenshots/grafana-dashboard.png)

**The same dashboard during the outage:** the circuit breaker is open (1), so payment-service stops calling fraud-service and holds new payments for a checker instead of approving them blindly.

![Circuit breaker open during the outage](docs/screenshots/circuit-breaker.png)

**Real API calls and responses:** create, idempotent replay, SETTLED via Kafka, the ledger's debit and credit, a held payment, the creator refused (403), a checker approving.

![API walkthrough with real responses](docs/screenshots/demo-flow.png)

**CI on GitHub Actions:** each service's tests run in parallel against real PostgreSQL and Kafka, then the Docker images are built.

![GitHub Actions run, all jobs green](docs/screenshots/ci-passing.png)

## What it does

| Area | Feature | How |
|---|---|---|
| Correctness | No double charges on retry | `Idempotency-Key` header per user. The app checks first, and a `UNIQUE (created_by, idempotency_key)` constraint catches simultaneous retries. A test fires 20 at once and gets exactly 1 payment. |
| | Key reuse detection | Same key with a different body returns `422` (SHA-256 request fingerprint) |
| | Money handled exactly | `BigDecimal` / `NUMERIC(19,2)`, never `double` |
| | Strict payment lifecycle | `CREATED → APPROVED → SETTLED`, `CREATED → PENDING_APPROVAL → APPROVED`, any non-final → `FAILED`. Illegal moves return `409`. |
| Messaging | No lost or phantom events | Transactional outbox in both services, published with `FOR UPDATE SKIP LOCKED` |
| | No double booking | Ledger records every processed event ID (`INSERT … ON CONFLICT DO NOTHING`) plus `UNIQUE (payment_id)` on postings |
| | Poison messages don't block | 3 retries with backoff, then a `.DLT` dead-letter topic. Bad JSON skips the retries. |
| Ledger | Double-entry bookkeeping | Each posting is one DEBIT and one CREDIT. A **deferred constraint trigger** refuses to commit an unbalanced posting. |
| | Append-only history | Triggers block `UPDATE`, `DELETE` and `TRUNCATE` on ledger tables |
| Security | Roles | `MAKER` creates, `CHECKER` approves/rejects, `AUDITOR` reads and reconciles. Unknown endpoints are denied by default. |
| | Maker-checker (four-eyes) | The creator can't approve their own payment, enforced in code **and** by a DB `CHECK` constraint |
| Resilience | Fraud check can fail safely | Connect/read timeouts, retry with exponential backoff, and a Resilience4j circuit breaker. If the check is unavailable, the payment is **held for a human**, never approved blindly. |
| Operations | Nightly reconciliation | Finds payments missing from the ledger, amount mismatches, unbooked settlements, unknown and unbalanced postings |
| | Metrics | Micrometer → `/actuator/prometheus`; optional Prometheus + Grafana dashboard |
| Delivery | CI | GitHub Actions runs every service's tests (real PostgreSQL + Kafka via Testcontainers) and builds the Docker images |

## MCP server: AI assistants, safely

`mcp-server` lets an AI assistant such as Claude work with PayFlow through the [Model Context Protocol](https://modelcontextprotocol.io). It is built with Spring AI 2.0 (`@McpTool`) and serves Streamable HTTP at `/mcp`.

| Tool | Scope | What it does |
|---|---|---|
| `get_payment`, `list_payments`, `list_held_payments` | `payments:read` | Payments, with why each is held or failed |
| `get_ledger_posting`, `get_account_balances`, `get_trial_balance` | `ledger:read` | The double-entry ledger |
| `get_latest_reconciliation` | `reconciliation:read` | Do payments and the ledger agree? |
| `create_payment` | `payments:create` | Up to 1,000.00, only after the person confirms in their AI client |
| *(no approve / reject tool)* | | Maker-checker stays with humans |

Plus a resource (`payflow://docs/payment-lifecycle`) and a prompt (`investigate_held_payment`).

**Safety by design**

- **Least privilege:** each client has its own token (only its SHA-256 hash is stored) and scopes. The server calls PayFlow as `mcp-agent`, which has the MAKER and AUDITOR roles and never CHECKER. The client's token is never passed on.
- **Human in the loop:** `create_payment` asks the person to confirm the exact amount and accounts through MCP elicitation. A client that can't ask is refused. Amounts over the cap are refused outright.
- **Prompt injection:** text typed by people (such as a checker's rejection reason) is cleaned and returned as `{"untrustedText": ...}`, with a notice to treat it as data. The real defence is capability: there is no tool that approves.
- **Audit:** every call is written to an append-only `tool_calls` table (PostgreSQL triggers block UPDATE/DELETE): client, tool, arguments, outcome, duration.
- **Transport hygiene:** requests from browser origins that aren't allowed get 403. Errors return safe messages, never stack traces.

![Live MCP session against PayFlow](docs/screenshots/mcp-session.png)

*Real output: a read-only client lists the tools, reads payments, the ledger and reconciliation; `create_payment` is denied for the read-only token and refused for a client that can't ask a person; every call lands in the audit table.*

**Connect Claude Code** (stack running, demo tokens are for local use only):

```bash
claude mcp add --transport http payflow http://127.0.0.1:8083/mcp --header "Authorization: Bearer payflow-demo-readonly"
```

Then ask, for example, *"Which PayFlow payments are held and why?"* Use `payflow-demo-operator` instead to allow `create_payment`. The server then asks you to confirm each payment; Claude Code shows this as an elicitation dialog in interactive terminal sessions. Run `/mcp` in Claude Code to check the connection.

## Run it (all free)

**Prerequisites:** [Docker](https://www.docker.com/products/docker-desktop/) (Docker Desktop or Rancher Desktop) and Git. For running from source you also need **JDK 21**. About 4 GB of free memory for Docker.

### Option A: everything in Docker (recommended)

```bash
git clone https://github.com/Vidhidesai12/payflow.git
cd payflow
docker compose --profile apps up -d --build      # first build takes a few minutes
```

Check that all three services are up (each should print `{"status":"UP"}`):

```bash
curl http://127.0.0.1:8080/actuator/health   # payment-service
curl http://127.0.0.1:8081/actuator/health   # ledger-service
curl http://127.0.0.1:8082/actuator/health   # fraud-service
```

Then open [demo.http](demo.http) in VS Code with the [REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client) extension and click **Send Request** above each step, top to bottom.

Add metrics dashboards with:

```bash
docker compose --profile apps --profile observability up -d --build
```

| What | URL | Login |
|---|---|---|
| payment-service API | http://127.0.0.1:8080/api/payments | demo users below |
| ledger-service API | http://127.0.0.1:8081/api/ledger/trial-balance | none (internal) |
| fraud-service API | http://127.0.0.1:8082/api/chaos | none (internal) |
| MCP server | http://127.0.0.1:8083/mcp | Bearer `payflow-demo-readonly` or `payflow-demo-operator` |
| Prometheus | http://127.0.0.1:9090 | none |
| Grafana, dashboard "PayFlow overview" | http://127.0.0.1:3000 | admin / admin |

Stop everything with `docker compose --profile apps --profile observability down`. Add `-v` to also delete all data.

### Option B: infrastructure in Docker, services from source

```bash
docker compose up -d                                   # PostgreSQL x2 (host ports 15432, 15433) + Kafka
./mvnw -pl fraud-service spring-boot:run               # Windows: .\mvnw.cmd ...
./mvnw -pl ledger-service spring-boot:run
./mvnw -pl payment-service spring-boot:run
```

Demo users (local only): `maker1`, `maker2` (MAKER), `checker1` (CHECKER), `lead1` (MAKER + CHECKER), `auditor1` (AUDITOR). The password is the username + `-pass`, for example `maker1-pass`.

```bash
curl -i -u maker1:maker1-pass -X POST 127.0.0.1:8080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-1" \
  -d '{"debtorAccount":"ACC10001","creditorAccount":"ACC20002","amount":150.00,"currency":"USD"}'
```

### What you should see

Real responses from a local run (IDs and timestamps will differ):

```text
POST /api/payments (maker1, 150.00)           -> 201  "status":"APPROVED"
POST /api/payments (same Idempotency-Key)     -> 200  Idempotent-Replayed: true   (same payment, no second charge)
GET  /api/payments/{id} about 1.5 s later     -> "status":"SETTLED"
GET  /api/ledger/postings/{id}                -> DEBIT ACC10001 150.00 / CREDIT ACC20002 150.00
GET  /api/ledger/trial-balance                -> {"currency":"USD","debits":150.00,"credits":150.00,"balanced":true}
POST /api/payments (lead1, 25000.00)          -> "status":"PENDING_APPROVAL", "holdReason":"Amount is above the approval threshold"
POST /api/payments/{id}/approve (lead1)       -> 403  "User 'lead1' created this payment and cannot also approve it"
POST /api/payments/{id}/approve (checker1)    -> "status":"APPROVED", "approvedBy":"checker1"   then SETTLED
PUT  /api/chaos {"failureRate":1.0} + create  -> "holdReason":"Fraud check unavailable; manual review required"
POST /api/reconciliation/runs (auditor1)      -> "status":"MATCHED", "mismatchCount":0
```

### Troubleshooting

| Problem | Fix |
|---|---|
| `port is already allocated` for 15432, 15433, 9092 or 808x | Something else uses that port. Stop it, or change the database ports with `PAYFLOW_DB_PORT=25432 LEDGER_DB_PORT=25433 docker compose ...` |
| `curl localhost:8080` hangs but `127.0.0.1:8080` works | On Windows with WSL, `localhost` can resolve to a WSL port forwarder. Use `127.0.0.1`. |
| payment-service exits at startup | Kafka or PostgreSQL wasn't ready yet. Run `docker compose --profile apps up -d` again. |
| Tests fail with "Could not find a valid Docker environment" | Start Docker Desktop / Rancher Desktop first: the tests start their own containers. |
| A payment stays `PENDING_APPROVAL` with "Fraud check unavailable" | Working as designed: fraud-service was down or the circuit breaker is open (30 s). Turn chaos mode off and approve it as `checker1`. |

## API

| Service | Endpoint | Who |
|---|---|---|
| payment | `POST /api/payments` (header `Idempotency-Key`) | MAKER |
| payment | `GET /api/payments/{id}`, `GET /api/payments?status=` | MAKER, CHECKER, AUDITOR |
| payment | `POST /api/payments/{id}/approve`, `POST /api/payments/{id}/reject` `{reason}` | CHECKER (not the creator) |
| payment | `POST /api/reconciliation/runs?from=&to=`, `GET /api/reconciliation/runs` | AUDITOR |
| ledger | `GET /api/ledger/postings/{paymentId}`, `GET /api/ledger/postings?from=&to=` | internal |
| ledger | `GET /api/ledger/accounts/{account}/balances`, `GET /api/ledger/trial-balance` | internal |
| fraud | `POST /api/fraud-checks`, `PUT /api/chaos` `{failureRate, delayMs}` | internal |
| mcp | `POST /mcp` (MCP, Streamable HTTP) | Bearer token with scopes |

Errors are RFC 9457 problem-detail JSON.

## Tests

```bash
./mvnw verify            # all services
```

93 tests, all run on every push by GitHub Actions. Integration tests start **real PostgreSQL 17 and Kafka 4.2 in Docker** (Testcontainers); external HTTP services are replaced by small stub servers so failures can be staged.

| Service | Tests | Highlights |
|---|---|---|
| payment-service | 53 | 20 simultaneous same-key requests create exactly 1 payment; roles and four-eyes rule; retries, timeout and circuit breaker against a failing fraud stub (plus a regression test that blips fixed by a retry never open the circuit); outbox publishing; settlement from Kafka; duplicate and poison events; every reconciliation mismatch type |
| ledger-service | 13 | Balanced debit/credit per payment; duplicate deliveries booked once; dead-letter topic; database refuses an unbalanced posting; ledger rows can't be updated or deleted |
| fraud-service | 5 | Allow / review / deny rules, validation, chaos mode |
| mcp-server | 22 | Driven by the official MCP Java client: tool list and safety hints, no approve tool, 401 without token, 403 for foreign origins, scopes, confirm/decline/no-elicitation paths, the 1,000 cap, untrusted text labelling, safe errors, append-only audit |

Measured locally (laptop, Docker with 8 CPUs, 10 concurrent curl clients, 200 payments): p50 120 ms and p95 194 ms per create; all 200 booked in the ledger about 1.3 s after the last request.

## Project layout

```
payflow/
  payment-service/   REST API, idempotency, fraud check client, maker-checker, outbox, reconciliation
  ledger-service/    Kafka consumer, double-entry ledger, outbox, read API
  fraud-service/     rule-based fraud check with a chaos switch for demos
  mcp-server/        MCP server for AI assistants: scoped tools, human confirmation, audit log
  observability/     Prometheus scrape config, Grafana data source + dashboard
  Dockerfile         one multi-stage build for all services
  docker-compose.yml infrastructure + optional "apps" and "observability" profiles
  demo.http          click-through demo
  AI_NOTES.md        how AI tools were used, and the design decisions
  LICENSE            MIT
```

## Known limits (deliberate, for a portfolio project)

- Demo users live in memory with HTTP Basic. Production would use OAuth2/JWT from an identity provider such as Keycloak.
- ledger-service and fraud-service APIs are unauthenticated "internal" APIs. Production would use mTLS or service tokens.
- Single Kafka broker and single database instances. No replication.
- The outbox relay polls every 500 ms. Change-data-capture (Debezium) would cut latency at the cost of more infrastructure.
- Account balances may go negative: overdraft and funds-availability rules are out of scope.

## License

[MIT](LICENSE)

See [AI_NOTES.md](AI_NOTES.md) for how AI tools were used on this project and the reasoning behind each design decision.
