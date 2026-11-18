"""Tools through a real MCP client (in memory), and the HTTP app's authentication and Host checks."""

import hashlib
import json

import pytest
from mcp.client.client import Client
from starlette.testclient import TestClient

from analytics_mcp.server import build_app, build_server, parse_clients

from .conftest import FakePayFlow

EXPECTED_TOOLS = {"account_statement", "volume_by_day", "largest_movements", "unusual_outflows",
                  "reconciliation_history"}


def text(result) -> str:
    return "\n".join(c.text for c in result.content if getattr(c, "type", "") == "text")


async def test_offers_five_read_only_tools(sample):
    async with Client(build_server(FakePayFlow(sample))) as client:
        tools = (await client.list_tools()).tools

    assert {t.name for t in tools} == EXPECTED_TOOLS
    for t in tools:
        assert t.annotations.read_only_hint is True, t.name
        assert t.annotations.destructive_hint is False, t.name


async def test_statement_through_mcp(sample):
    api = FakePayFlow(sample)
    async with Client(build_server(api)) as client:
        result = await client.call_tool("account_statement", {"account": "ACC100001", "days": 3})

    assert not result.is_error
    body = json.loads(text(result))
    assert body["data"]["totals"][0]["net"] == "8861.00"
    assert api.requested_days == [3]


async def test_unusual_outflows_through_mcp(sample):
    async with Client(build_server(FakePayFlow(sample))) as client:
        result = await client.call_tool("unusual_outflows", {"days": 7, "factor": 3})

    assert json.loads(text(result))["data"]["flagged"][0]["account"] == "ACC900009"


@pytest.mark.parametrize("tool,args,message", [
    ("account_statement", {"account": "acc 1"}, "6-34 uppercase"),
    ("volume_by_day", {"days": 90}, "between 1 and 31"),
    ("largest_movements", {"limit": 500}, "between 1 and 50"),
    ("unusual_outflows", {"factor": 1.0}, "between 1.5 and 20"),
])
async def test_bad_input_is_refused_before_calling_payflow(sample, tool, args, message):
    api = FakePayFlow(sample)
    async with Client(build_server(api)) as client:
        result = await client.call_tool(tool, args)

    assert result.is_error
    assert message in text(result)
    assert api.requested_days == []


async def test_payflow_outage_gives_a_safe_message():
    async with Client(build_server(FakePayFlow(fail=True))) as client:
        result = await client.call_tool("volume_by_day", {"days": 1})

    assert result.is_error
    assert "PayFlow is unavailable" in text(result)
    assert "Traceback" not in text(result)


async def test_reconciliation_history_keeps_only_summary_fields():
    runs = [{"id": "r1", "status": "MATCHED", "mismatchCount": 0, "details": "[...]", "secret": "x"}]
    async with Client(build_server(FakePayFlow(runs=runs))) as client:
        result = await client.call_tool("reconciliation_history", {"limit": 5})

    row = json.loads(text(result))["data"][0]
    assert row["status"] == "MATCHED" and "details" not in row and "secret" not in row


# ---- HTTP: tokens and Host checks ----------------------------------------------------------

TOKEN = "test-token"
CLIENTS = parse_clients("gateway:" + hashlib.sha256(TOKEN.encode()).hexdigest())
INIT = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}}}
HEADERS = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}


@pytest.fixture
def http(sample):
    app = build_app(FakePayFlow(sample), CLIENTS, ["testserver"])
    with TestClient(app) as c:
        yield c


def test_health_is_open(http):
    assert http.get("/health").json() == {"status": "UP"}


def test_mcp_without_or_with_wrong_token_is_unauthorized(http):
    assert http.post("/mcp", json=INIT, headers=HEADERS).status_code == 401
    assert http.post("/mcp", json=INIT, headers={**HEADERS, "Authorization": "Bearer nope"}).status_code == 401


def test_mcp_with_token_answers(http):
    r = http.post("/mcp", json=INIT, headers={**HEADERS, "Authorization": f"Bearer {TOKEN}"})
    assert r.status_code == 200
    assert "payflow-analytics" in r.text


def test_unknown_host_or_browser_origin_is_rejected(http):
    auth = {**HEADERS, "Authorization": f"Bearer {TOKEN}"}
    assert http.post("/mcp", json=INIT, headers={**auth, "Host": "evil.example"}).status_code in (403, 421)
    assert http.post("/mcp", json=INIT, headers={**auth, "Origin": "https://evil.example"}).status_code in (403, 421)
