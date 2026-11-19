# Screenshots

All images were captured from the real running system on 2026-11-18 with headless Microsoft Edge (`msedge --headless=new --screenshot=...`). Nothing is mocked up.

| File | What it shows | How it was made |
|---|---|---|
| `grafana-dashboard.png` | Grafana "PayFlow overview" over a 4-minute run: ~630 payments, with fraud-service failing every call from minute 1:40 to 2:30 | `docker compose --profile apps --profile observability up -d --build`, a script sending 3 payments every ~0.4 s, chaos mode switched on and off via `PUT /api/chaos`, then a headless screenshot of the dashboard in kiosk mode |
| `circuit-breaker.png` | The same dashboard ~25 s into the outage, circuit breaker open | Same run, captured while the breaker was open |
| `demo-flow.png` | Seven real API calls and their responses | A small Python script calling the live API, writing each request and the response (fields trimmed, values untouched) to an HTML page, then screenshotted |
| `mcp-session.png` | A live MCP session against the running stack plus the audit table | A small Python MCP client (JSON-RPC over Streamable HTTP) run against `mcp-server`, its real output rendered to HTML and screenshotted |
| `gateway-demo.png` | The MCP gateway in front of the Java and Python MCP servers: allowed reads, blocked calls, the confirmation relay, audit chain verified and tampering detected, PayFlow's own audit table | `npm run demo` and `npm run verify-audit` against the Docker stack, real output rendered to HTML and screenshotted |
| `ci-passing.png` | A passing GitHub Actions run | Headless screenshot of the public run page, cropped |

To refresh them, start the stack as above, generate some traffic (for example by clicking through `demo.http`), and take new screenshots with the same file names.
