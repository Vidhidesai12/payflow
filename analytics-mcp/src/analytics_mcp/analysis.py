"""Pure calculations over ledger postings. No I/O here, so every rule is easy to test and explain."""

from __future__ import annotations

from collections import defaultdict
from decimal import Decimal
from statistics import median

from .payflow import Posting


def money(value: Decimal) -> str:
    """Money leaves this server as a string with exactly 2 decimals, never as a float."""
    return f"{value.quantize(Decimal('0.01'))}"


def account_statement(postings: list[Posting], account: str) -> dict:
    lines = []
    totals_in: dict[str, Decimal] = defaultdict(Decimal)
    totals_out: dict[str, Decimal] = defaultdict(Decimal)
    for p in sorted(postings, key=lambda p: p.posted_at):
        for e in p.entries:
            if e.account != account:
                continue
            if e.direction == "CREDIT":
                totals_in[e.currency] += e.amount
            else:
                totals_out[e.currency] += e.amount
            lines.append({"postedAt": p.posted_at.isoformat(), "paymentId": p.payment_id,
                          "direction": "IN" if e.direction == "CREDIT" else "OUT",
                          "amount": money(e.amount), "currency": e.currency})
    currencies = sorted(set(totals_in) | set(totals_out))
    return {
        "account": account,
        "movements": lines,
        "totals": [{"currency": c, "in": money(totals_in[c]), "out": money(totals_out[c]),
                    "net": money(totals_in[c] - totals_out[c])} for c in currencies],
    }


def volume_by_day(postings: list[Posting]) -> list[dict]:
    days: dict[tuple[str, str], list[Decimal]] = defaultdict(list)
    for p in postings:
        days[(p.posted_at.date().isoformat(), p.currency)].append(p.amount)
    return [{"day": day, "currency": cur, "payments": len(amounts), "total": money(sum(amounts, Decimal(0)))}
            for (day, cur), amounts in sorted(days.items())]


def largest(postings: list[Posting], limit: int) -> list[dict]:
    top = sorted(postings, key=lambda p: p.amount, reverse=True)[:limit]
    return [{"paymentId": p.payment_id, "amount": money(p.amount), "currency": p.currency,
             "postedAt": p.posted_at.isoformat(),
             "from": next((e.account for e in p.entries if e.direction == "DEBIT"), None),
             "to": next((e.account for e in p.entries if e.direction == "CREDIT"), None)} for p in top]


def unusual_outflows(postings: list[Posting], factor: Decimal, min_accounts: int = 5) -> dict:
    """
    Accounts whose total outflow is more than `factor` times the median outflow of all paying accounts.
    A simple, explainable rule; it flags accounts for a person to look at, it does not accuse anyone.
    """
    outflow: dict[tuple[str, str], Decimal] = defaultdict(Decimal)
    for p in postings:
        for e in p.entries:
            if e.direction == "DEBIT":
                outflow[(e.account, e.currency)] += e.amount
    by_currency: dict[str, list[tuple[str, Decimal]]] = defaultdict(list)
    for (acct, cur), total in outflow.items():
        by_currency[cur].append((acct, total))

    flagged, notes = [], []
    for cur, rows in sorted(by_currency.items()):
        if len(rows) < min_accounts:
            notes.append(f"{cur}: only {len(rows)} paying accounts, too few to compare")
            continue
        mid = Decimal(median([t for _, t in rows]))
        for acct, total in sorted(rows, key=lambda r: r[1], reverse=True):
            if mid > 0 and total > mid * factor:
                flagged.append({"account": acct, "currency": cur, "outflow": money(total),
                                "medianOutflow": money(mid), "timesMedian": f"{(total / mid):.1f}"})
    return {"rule": f"outflow > {factor} x median outflow per currency", "flagged": flagged, "notes": notes}


def reconciliation_summary(runs: list[dict], limit: int) -> list[dict]:
    keep = ("id", "windowStart", "windowEnd", "status", "paymentsChecked", "postingsChecked", "mismatchCount")
    return [{k: r.get(k) for k in keep} for r in runs[:limit]]
