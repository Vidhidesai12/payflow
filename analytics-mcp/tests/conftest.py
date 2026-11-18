from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from analytics_mcp.payflow import Entry, PayFlowError, Posting

NOW = datetime(2026, 11, 18, 12, 0, tzinfo=timezone.utc)


def posting(pid: str, amount: str, frm: str, to: str, days_ago: int = 0, currency: str = "USD") -> Posting:
    a = Decimal(amount)
    return Posting(pid, a, currency, NOW - timedelta(days=days_ago),
                   (Entry(frm, "DEBIT", a, currency), Entry(to, "CREDIT", a, currency)))


class FakePayFlow:
    """Stands in for PayFlow's REST APIs and records what the tools asked for."""

    def __init__(self, postings=None, runs=None, fail=False):
        self._postings = postings or []
        self._runs = runs or []
        self.fail = fail
        self.requested_days = []

    async def postings(self, days: int):
        self.requested_days.append(days)
        if self.fail:
            raise PayFlowError("PayFlow is unavailable; try again later")
        return self._postings

    async def reconciliation_runs(self):
        if self.fail:
            raise PayFlowError("PayFlow is unavailable; try again later")
        return self._runs


@pytest.fixture
def sample():
    return [
        posting("p1", "150.00", "ACC100001", "ACC200002", days_ago=1),
        posting("p2", "25.50", "ACC200002", "ACC300003", days_ago=1),
        posting("p3", "9000.00", "ACC900009", "ACC100001", days_ago=0),
        posting("p4", "10.00", "ACC400004", "ACC500005", days_ago=0),
        posting("p5", "12.00", "ACC500005", "ACC400004", days_ago=0),
        posting("p6", "11.00", "ACC600006", "ACC100001", days_ago=2),
    ]
