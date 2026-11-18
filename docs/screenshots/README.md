# Screenshots

Put PNG screenshots here with the exact names below, then un-comment the "Screenshots" block in the main [README](../../README.md).

| File | What to capture | How |
|---|---|---|
| `grafana-dashboard.png` | Grafana "PayFlow overview" dashboard with data on it | `docker compose --profile apps --profile observability up -d --build`, run a few requests from `demo.http`, open http://127.0.0.1:3000 → Dashboards → PayFlow → PayFlow overview |
| `demo-flow.png` | VS Code with `demo.http` on the left and a response on the right, e.g. step 4 (the ledger posting with its DEBIT and CREDIT) | REST Client extension, "Send Request" |
| `circuit-breaker.png` | The log line `Fraud-service circuit breaker: State transition from CLOSED to OPEN` | Run steps 10 and 11 of `demo.http`, then `docker compose logs payment-service` |
| `tests-passing.png` | Terminal showing `BUILD SUCCESS` and the test counts | `./mvnw verify` (Windows: `.\mvnw.cmd verify`) |

Tips: crop to the useful part, keep each image under ~500 KB, and don't capture anything personal (other browser tabs, email, file paths with your name).
