from datetime import datetime, timezone
from decimal import Decimal

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
