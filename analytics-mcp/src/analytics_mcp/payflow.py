"""PayFlow's REST APIs, read-only. The analytics server never writes anything and never sees a database."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Protocol

import httpx


class PayFlowError(Exception):
    """A failure talking to PayFlow; the message is safe to show to an AI client."""


@dataclass(frozen=True)
class Entry:
    account: str
    direction: str  # DEBIT or CREDIT
    amount: Decimal
    currency: str


@dataclass(frozen=True)
class Posting:
    payment_id: str
    amount: Decimal
    currency: str
    posted_at: datetime
    entries: tuple[Entry, ...]


class PayFlowApi(Protocol):
    async def postings(self, days: int) -> list[Posting]: ...

    async def reconciliation_runs(self) -> list[dict]: ...


class HttpPayFlowApi:
    """Calls ledger-service (no auth, internal) and payment-service (as a read-only AUDITOR account)."""

    def __init__(self, ledger_url: str, payments_url: str, user: str, password: str, timeout: float = 5.0):
        self._ledger = httpx.AsyncClient(base_url=ledger_url, timeout=timeout)
        self._payments = httpx.AsyncClient(base_url=payments_url, timeout=timeout, auth=(user, password))

    async def postings(self, days: int) -> list[Posting]:
        to = datetime.now(timezone.utc) + timedelta(minutes=1)
        frm = to - timedelta(days=days)
        rows = await self._get(self._ledger, "/api/ledger/postings",
                               {"from": frm.isoformat().replace("+00:00", "Z"), "to": to.isoformat().replace("+00:00", "Z")})
        return [parse_posting(r) for r in rows]

    async def reconciliation_runs(self) -> list[dict]:
        return await self._get(self._payments, "/api/reconciliation/runs", None)

    @staticmethod
    async def _get(client: httpx.AsyncClient, path: str, params: dict | None):
        try:
            response = await client.get(path, params=params)
        except httpx.HTTPError as e:
            raise PayFlowError("PayFlow is unavailable; try again later") from e
        if response.status_code >= 500:
            raise PayFlowError("PayFlow is unavailable; try again later")
        if response.status_code >= 400:
            raise PayFlowError(f"PayFlow rejected the request (HTTP {response.status_code})")
        # parse_float=Decimal keeps money exact: 150.00 stays 150.00, never 150.0
        return response.json(parse_float=Decimal)


def parse_posting(row: dict) -> Posting:
    return Posting(
        payment_id=str(row["paymentId"]),
        amount=Decimal(str(row["amount"])),
        currency=row["currency"],
        posted_at=datetime.fromisoformat(str(row["postedAt"]).replace("Z", "+00:00")),
        entries=tuple(Entry(e["account"], e["direction"], Decimal(str(e["amount"])), e["currency"])
                      for e in row.get("entries", [])),
    )
