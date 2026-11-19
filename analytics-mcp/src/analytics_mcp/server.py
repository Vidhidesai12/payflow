"""The MCP server: five read-only analytics tools over PayFlow's ledger, behind a bearer-token check."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import re
from decimal import Decimal

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError as SdkToolError
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations

from . import analysis
from .payflow import HttpPayFlowApi, PayFlowApi, PayFlowError

log = logging.getLogger("analytics_mcp")

ACCOUNT = re.compile(r"[A-Z0-9]{6,34}")
READ_ONLY = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)
NOTICE = "Read-only analytics over PayFlow's ledger. Amounts are exact decimal strings."


class ToolError(SdkToolError):
    """
    An expected failure (bad input, PayFlow down). The SDK passes this message to the client;
    any other exception is reported only as a generic "Error executing tool", so internals never leak.
    """


def _days(days: int) -> int:
    if not isinstance(days, int) or not 1 <= days <= 31:
        raise ToolError("days must be between 1 and 31")
    return days


def _limit(limit: int) -> int:
    if not isinstance(limit, int) or not 1 <= limit <= 50:
        raise ToolError("limit must be between 1 and 50")
    return limit


def build_server(api: PayFlowApi) -> MCPServer:
    mcp = MCPServer(
        name="payflow-analytics",
        version="0.1.0",
        instructions="Read-only ledger analytics for PayFlow: statements, daily volumes, largest movements, "
                     "unusual outflows and reconciliation history. This server cannot change anything.",
    )

    async def postings(days: int):
        try:
            return await api.postings(_days(days))
        except PayFlowError as e:
            raise ToolError(str(e)) from e

    @mcp.tool(name="account_statement", title="Account statement", annotations=READ_ONLY,
              description="Every movement in and out of one account over the last N days (1-31), with totals.")
    async def account_statement(account: str, days: int = 7) -> dict:
        if not ACCOUNT.fullmatch(account or ""):
            raise ToolError("account must be 6-34 uppercase letters or digits")
        return {"notice": NOTICE, "data": analysis.account_statement(await postings(days), account)}

    @mcp.tool(name="volume_by_day", title="Daily volume", annotations=READ_ONLY,
              description="Number of booked payments and total amount per day and currency over the last N days.")
    async def volume_by_day(days: int = 7) -> dict:
        return {"notice": NOTICE, "data": analysis.volume_by_day(await postings(days))}

    @mcp.tool(name="largest_movements", title="Largest movements", annotations=READ_ONLY,
              description="The largest booked payments over the last N days, with debtor and creditor accounts.")
    async def largest_movements(days: int = 7, limit: int = 10) -> dict:
        top = _limit(limit)  # validate everything before calling PayFlow
        return {"notice": NOTICE, "data": analysis.largest(await postings(days), top)}

    @mcp.tool(name="unusual_outflows", title="Unusual outflows", annotations=READ_ONLY,
              description="Accounts whose outflow over the last N days is more than `factor` times the median "
                          "outflow (default 3). Flags accounts for a person to review; it is not a fraud verdict.")
    async def unusual_outflows(days: int = 7, factor: float = 3.0) -> dict:
        if not 1.5 <= factor <= 20:
            raise ToolError("factor must be between 1.5 and 20")
        return {"notice": NOTICE,
                "data": analysis.unusual_outflows(await postings(days), Decimal(str(factor)))}

    @mcp.tool(name="reconciliation_history", title="Reconciliation history", annotations=READ_ONLY,
              description="The most recent reconciliation runs: did payments and the ledger agree?")
    async def reconciliation_history(limit: int = 10) -> dict:
        try:
            runs = await api.reconciliation_runs()
        except PayFlowError as e:
            raise ToolError(str(e)) from e
        return {"notice": NOTICE, "data": analysis.reconciliation_summary(runs, _limit(limit))}

    return mcp


def parse_clients(spec: str) -> dict[bytes, str]:
    """ "name:sha256hex,name2:sha256hex" -> {digest: name}. Only hashes are configured, never tokens."""
    clients = {}
    for item in filter(None, (s.strip() for s in spec.split(","))):
        name, _, digest = item.partition(":")
        clients[bytes.fromhex(digest)] = name
    return clients


class BearerTokenMiddleware:
    """Pure ASGI middleware (streaming-safe): every request needs a known bearer token."""

    def __init__(self, app, clients: dict[bytes, str], open_paths: tuple[str, ...] = ("/health",)):
        self.app, self.clients, self.open_paths = app, clients, open_paths

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] in self.open_paths:
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers") or [])
        auth = headers.get(b"authorization", b"").decode()
        client = None
        if auth.startswith("Bearer "):
            digest = hashlib.sha256(auth[7:].strip().encode()).digest()
            client = next((n for d, n in self.clients.items() if hmac.compare_digest(d, digest)), None)
        if client is None:
            body = json.dumps({"error": "unauthorized"}).encode()
            await send({"type": "http.response.start", "status": 401,
                        "headers": [(b"content-type", b"application/json"), (b"www-authenticate", b"Bearer")]})
            return await send({"type": "http.response.body", "body": body})
        log.info("client=%s %s %s", client, scope.get("method"), scope["path"])
        return await self.app(scope, receive, send)


def build_app(api: PayFlowApi, clients: dict[bytes, str], allowed_hosts: list[str]):
    server = build_server(api)
    app = server.streamable_http_app(
        streamable_http_path="/mcp",
        # Built-in DNS-rebinding protection: unknown Host headers and browser Origins are rejected.
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
                                                     allowed_hosts=allowed_hosts, allowed_origins=[]),
    )

    from starlette.responses import JSONResponse
    from starlette.routing import Route

    app.router.routes.append(Route("/health", lambda request: JSONResponse({"status": "UP"})))
    return BearerTokenMiddleware(app, clients)


def main() -> None:
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    port = int(os.environ.get("PORT", "8084"))
    api = HttpPayFlowApi(
        ledger_url=os.environ.get("PAYFLOW_LEDGER_URL", "http://127.0.0.1:8081"),
        payments_url=os.environ.get("PAYFLOW_PAYMENTS_URL", "http://127.0.0.1:8080"),
        user=os.environ.get("PAYFLOW_USER", "analytics-agent"),
        password=os.environ.get("PAYFLOW_PASSWORD", "analytics-agent-pass"),
    )
    # Demo client for local runs: token "analytics-demo-token". Override with ANALYTICS_CLIENTS.
    clients = parse_clients(os.environ.get(
        "ANALYTICS_CLIENTS", "gateway:" + hashlib.sha256(b"analytics-demo-token").hexdigest()))
    hosts = os.environ.get("ALLOWED_HOSTS", f"127.0.0.1:{port},localhost:{port}").split(",")
    uvicorn.run(build_app(api, clients, hosts), host=os.environ.get("HOST", "127.0.0.1"), port=port)


if __name__ == "__main__":
    main()
