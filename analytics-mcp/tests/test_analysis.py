from decimal import Decimal

from analytics_mcp import analysis


def test_statement_lists_movements_in_time_order_with_exact_totals(sample):
    s = analysis.account_statement(sample, "ACC100001")

    assert [m["paymentId"] for m in s["movements"]] == ["p6", "p1", "p3"]
    assert [m["direction"] for m in s["movements"]] == ["IN", "OUT", "IN"]
    assert s["totals"] == [{"currency": "USD", "in": "9011.00", "out": "150.00", "net": "8861.00"}]


def test_money_is_always_a_two_decimal_string():
    assert analysis.money(Decimal("150")) == "150.00"
    assert analysis.money(Decimal("0.1") + Decimal("0.2")) == "0.30"


def test_volume_groups_by_day_and_currency(sample):
    days = analysis.volume_by_day(sample)

    assert days[-1] == {"day": "2026-11-18", "currency": "USD", "payments": 3, "total": "9022.00"}
    assert sum(d["payments"] for d in days) == 6


def test_largest_sorts_by_amount_and_names_both_accounts(sample):
    top = analysis.largest(sample, 2)

    assert [t["paymentId"] for t in top] == ["p3", "p1"]
    assert top[0]["from"] == "ACC900009" and top[0]["to"] == "ACC100001"


def test_unusual_outflow_flags_accounts_far_above_the_median(sample):
    result = analysis.unusual_outflows(sample, Decimal("3"))

    # six paying accounts: 10, 11, 12, 25.50, 150, 9000 -> median (12 + 25.50) / 2 = 18.75
    assert [f["account"] for f in result["flagged"]] == ["ACC900009", "ACC100001"]
    assert result["flagged"][0]["medianOutflow"] == "18.75"
    assert result["flagged"][1]["timesMedian"] == "8.0"


def test_unusual_outflow_refuses_to_compare_too_few_accounts(sample):
    result = analysis.unusual_outflows(sample[:2], Decimal("3"))

    assert result["flagged"] == []
    assert "too few to compare" in result["notes"][0]
