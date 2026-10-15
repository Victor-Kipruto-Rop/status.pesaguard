from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ledger_matching import LedgerMatcher
from purchase_orders import LedgerEntry, PurchaseOrder, parse_csv_records


def test_csv_rows_are_normalized_for_purchase_and_ledger_imports():
    rows = parse_csv_records(
        "po_number,vendor,amount,phone_number,timestamp\n"
        "PO-100,Acme,1500,0712345678,2026-10-10T12:00:00+00:00\n"
    )
    po = PurchaseOrder.from_mapping(rows[0])
    assert po.po_number == "PO-100"
    assert po.amount == Decimal("1500")
    assert po.phone_number == "+254712345678"


def test_purchase_orders_match_ledger_entries_on_reference_and_amount():
    order = PurchaseOrder(
        po_number="PO-100",
        amount=Decimal("1500.00"),
        phone_number="+254712345678",
        reference="PO-100",
        timestamp=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
    )
    entry = LedgerEntry(
        entry_id="txn-1",
        amount=Decimal("1500.00"),
        phone_number="+254712345678",
        reference="PO-100",
        timestamp=datetime(2026, 10, 10, 12, 5, tzinfo=timezone.utc),
    )

    result = LedgerMatcher().match_one(order, [entry])
    assert result is not None
    assert result["ledger_entry"].entry_id == "txn-1"
    assert "reference" in result["reasons"]
    assert "amount" in result["reasons"]


def test_invalid_purchase_orders_are_rejected_before_matching():
    order = PurchaseOrder(po_number="", amount=None, phone_number="", reference="")
    report = LedgerMatcher().validate_purchase_order(order)
    assert report.status == "rejected"
    assert "missing_po_number" in report.errors


def test_deduplicated_matches_are_recorded_with_validation_state():
    matcher = LedgerMatcher()
    order = PurchaseOrder(
        po_number="PO-INV-22",
        amount=Decimal("2200.00"),
        phone_number="+254712345678",
        reference="INV-22",
        timestamp=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
    )
    entry_a = LedgerEntry(
        entry_id="txn-a",
        amount=Decimal("2200.00"),
        phone_number="+254712345678",
        reference="INV-22",
        timestamp=datetime(2026, 10, 10, 12, 3, tzinfo=timezone.utc),
    )
    entry_b = LedgerEntry(
        entry_id="txn-b",
        amount=Decimal("2200.00"),
        phone_number="+254712345678",
        reference="INV-22",
        timestamp=datetime(2026, 10, 10, 12, 4, tzinfo=timezone.utc),
    )

    matches = matcher.match_many([order], [entry_a, entry_b])
    assert any(item["status"] == "matched" for item in matches)
    assert any(item["status"] == "duplicate" for item in matches)
    persisted = matcher.persist_matches(matches)
    assert len(persisted) >= 2
    assert persisted[0].status in {"matched", "duplicate"}


def test_purchase_and_ledger_imports_normalize_supported_currency_minor_units():
    row = {
        "po_number": "PO-BHD",
        "amount": "1.234",
        "currency": "bhd",
        "reference": "BHD-REF",
    }

    order = PurchaseOrder.from_mapping(row)
    entry = LedgerEntry.from_mapping(
        {
            "entry_id": "LEDGER-BHD",
            "amount": "1.234",
            "currency": "BHD",
            "reference": "BHD-REF",
        }
    )

    assert order.amount == entry.amount == Decimal("1.234")
    assert order.currency == entry.currency == "BHD"
    assert order.raw["amount"] == entry.raw["amount"] == "1.234"


@pytest.mark.parametrize("amount", ["NaN", "Infinity", "1.001", "1e100"])
def test_purchase_and_ledger_imports_reject_invalid_monetary_values(amount):
    with pytest.raises(ValueError, match="invalid numeric amount"):
        PurchaseOrder.from_mapping(
            {"po_number": "PO-INVALID", "amount": amount, "currency": "KES"}
        )
    with pytest.raises(ValueError, match="invalid numeric amount"):
        LedgerEntry.from_mapping(
            {"entry_id": "LEDGER-INVALID", "amount": amount, "currency": "KES"}
        )


def test_ledger_matcher_does_not_compare_across_currencies():
    order = PurchaseOrder(
        po_number="PO-CURRENCY",
        amount=Decimal("100.00"),
        currency="USD",
        reference="SAME-REF",
    )
    entry = LedgerEntry(
        entry_id="LEDGER-CURRENCY",
        amount=Decimal("100.00"),
        currency="KES",
        reference="SAME-REF",
    )

    assert LedgerMatcher().match_one(order, [entry]) is None


def test_ledger_matcher_rejects_direct_amounts_with_excess_precision():
    order = PurchaseOrder(
        po_number="PO-PRECISION",
        amount=Decimal("1.001"),
        currency="KES",
    )

    report = LedgerMatcher().validate_purchase_order(order)

    assert report.status == "rejected"
    assert "invalid_amount" in report.errors


def test_direct_match_inputs_use_the_same_canonical_decimal_amounts():
    order = PurchaseOrder(
        po_number="PO-REPRESENTATION",
        amount=1.2,
        currency="KES",
        reference="REPRESENTATION",
    )
    entry = LedgerEntry(
        entry_id="LEDGER-REPRESENTATION",
        amount=Decimal("1.20"),
        currency="KES",
        reference="REPRESENTATION",
    )

    result = LedgerMatcher().match_one(order, [entry])

    assert result is not None
    assert "amount" in result["reasons"]
