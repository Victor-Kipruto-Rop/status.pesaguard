from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import DateTime, Numeric, String, create_engine, text

from base_connector import (
    BaseConnector,
    CsvLedgerConnector,
    ExcelLedgerConnector,
    GoogleSheetsConnector,
    PostgresConnector,
    RestConnector,
)
from reconciliation_engine import ReconciliationEngine


def _payment(transaction_id: str, amount: str, reference: str, timestamp: str = "20261013120000"):
    return {
        "TransID": transaction_id,
        "TransAmount": amount,
        "Currency": "KES",
        "TransTime": timestamp,
        "BillRefNumber": reference,
    }


def _entry(entry_id: str, amount: str, reference: str, timestamp: str = "2026-10-13T12:00:00Z"):
    return {
        "internal_ref": entry_id,
        "reference": reference,
        "amount": amount,
        "currency": "KES",
        "timestamp": timestamp,
    }


class _MemoryConnector(BaseConnector):
    def __init__(self, records):
        self.records = records

    def fetch_recent_records(self, since_minutes=15):
        return list(self.records)


def test_one_payment_matches_multiple_unique_ledger_rows_only_on_exact_total():
    engine = ReconciliationEngine()

    result = engine.reconcile(
        _payment("MPESA-1", "100.00", " invoice 42 "),
        [
            _entry("line-2", "60.00", "INVOICE42"),
            _entry("line-1", "40.00", "invoice42"),
        ],
    )

    assert result["status"] == "MATCHED"
    assert result["evidence"]["matching_rules"] == [
        "reference_exact",
        "currency_exact",
        "timestamp_within_window",
        "one_to_many_exact_amount",
    ]
    assert [row["internal_ref"] for row in result["evidence"]["matched_records"]] == [
        "line-1",
        "line-2",
    ]
    assert result["evidence"]["amounts"] == {
        "currency": "KES",
        "payment_amount": "100.00",
        "ledger_amount": "100.00",
        "difference": "0.00",
    }


def test_multiple_payments_match_one_ledger_record_only_on_exact_group_total():
    result = ReconciliationEngine().reconcile_batch(
        [
            _payment("MPESA-1", "40.00", "invoice-42"),
            _payment("MPESA-2", "60.00", "invoice-42"),
        ],
        [_entry("invoice-42", "100.00", "invoice-42")],
    )

    assert [item["result"]["status"] for item in result["results"]] == ["MATCHED", "MATCHED"]
    for item in result["results"]:
        evidence = item["result"]["evidence"]
        assert evidence["matching_rules"] == [
            "reference_exact",
            "currency_exact",
            "timestamp_within_window",
            "many_to_one_exact_amount",
        ]
        assert evidence["group_transaction_ids"] == ["MPESA-1", "MPESA-2"]
        assert evidence["matched_internal_refs"] == ["INVOICE-42"]
        assert evidence["amounts"] == {
            "currency": "KES",
            "payment_amount": "100.00",
            "ledger_amount": "100.00",
            "difference": "0.00",
        }
    assert result["summary"]["matched_payment_count"] == 2
    assert result["summary"]["matched_ledger_record_count"] == 1
    assert result["unmatched_ledger_records"] == []


@pytest.mark.parametrize(
    ("payment_amounts", "expected_status"),
    [(("40.00", "50.00"), "PARTIAL"), (("40.00", "70.00"), "MISMATCH")],
)
def test_non_exact_many_to_one_group_remains_for_review(payment_amounts, expected_status):
    result = ReconciliationEngine().reconcile_batch(
        [
            _payment("MPESA-1", payment_amounts[0], "invoice-42"),
            _payment("MPESA-2", payment_amounts[1], "invoice-42"),
        ],
        [_entry("invoice-42", "100.00", "invoice-42")],
    )

    assert [item["result"]["status"] for item in result["results"]] == [
        expected_status,
        expected_status,
    ]
    expected_payment_total = f"{sum(Decimal(amount) for amount in payment_amounts):.2f}"
    expected_difference = "-10.00" if expected_status == "PARTIAL" else "10.00"
    for item in result["results"]:
        assert item["result"]["evidence"]["amounts"] == {
            "currency": "KES",
            "payment_amount": expected_payment_total,
            "ledger_amount": "100.00",
            "difference": expected_difference,
        }
    assert result["summary"]["matched_payment_count"] == 0
    assert result["summary"]["matched_ledger_record_count"] == 0
    assert [row["internal_ref"] for row in result["unmatched_ledger_records"]] == ["invoice-42"]


def test_fallback_connector_keeps_reference_and_amount_phone_candidates():
    connector = _MemoryConnector([
        _entry("reference-line", "20.00", "invoice-\t42"),
        {**_entry("amount-line", "100.20", "other"), "phone_number": "254700000000"},
        {**_entry("wrong-phone", "100.20", "other"), "phone_number": "254711111111"},
        _entry("wrong-amount", "50.00", "other"),
    ])

    rows = connector.fetch_candidate_records(
        amount=Decimal("100.00"),
        tolerance=Decimal("0.50"),
        phone_number="254700000000",
        reference="INVOICE-42",
    )

    assert [row["internal_ref"] for row in rows] == ["reference-line", "amount-line"]


def test_fallback_connector_only_amount_matches_within_the_requested_currency():
    connector = _MemoryConnector(
        [
            _entry("kes-line", "100.00", "KES-REF"),
            {
                **_entry("usd-line", "100.00", "USD-REF"),
                "currency": "USD",
            },
        ]
    )

    rows = connector.fetch_candidate_records(
        amount=Decimal("100.00"),
        tolerance=Decimal("0.01"),
        currency="KES",
    )

    assert [row["internal_ref"] for row in rows] == ["kes-line"]


def test_fallback_connector_fails_closed_when_candidate_limit_would_truncate_group():
    connector = _MemoryConnector([
        _entry("line-1", "40.00", "invoice-limit"),
        _entry("line-2", "60.00", "invoice-limit"),
    ])

    with pytest.raises(RuntimeError, match="candidate set exceeds"):
        connector.fetch_candidate_records(reference="invoice-limit", limit=1)


def test_ledger_connector_configuration_and_fetch_failures_are_not_empty_results(monkeypatch):
    import requests
    from unittest.mock import Mock

    with pytest.raises(ValueError, match="sheet_id"):
        GoogleSheetsConnector("").fetch_recent_records()
    with pytest.raises(RuntimeError, match="Google Sheets ledger fetch failed"):
        GoogleSheetsConnector("sheet-1").fetch_recent_records()
    with pytest.raises(ValueError, match="endpoint"):
        RestConnector("").fetch_recent_records()
    with pytest.raises(ValueError, match="CSV"):
        CsvLedgerConnector("").fetch_recent_records()
    with pytest.raises(ValueError, match="Excel"):
        ExcelLedgerConnector("").fetch_recent_records()

    connector = RestConnector("https://ledger.example.test/records")
    monkeypatch.setattr(
        connector._session,
        "get",
        Mock(side_effect=requests.Timeout("upstream timeout")),
    )
    with pytest.raises(RuntimeError, match="REST ledger fetch failed"):
        connector.fetch_recent_records()


def test_rest_connector_rejects_malformed_rows_instead_of_returning_partial_ledger(monkeypatch):
    from types import SimpleNamespace

    connector = RestConnector("https://ledger.example.test/records")
    response = SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: {"items": [{"id": "row-1", "amount": "10.00"}, None]},
    )
    monkeypatch.setattr(connector._session, "get", lambda *args, **kwargs: response)

    with pytest.raises(RuntimeError, match="REST ledger response processing failed"):
        connector.fetch_recent_records()


def test_rest_connector_uses_bearer_auth_and_preserves_mapped_fields(monkeypatch):
    from types import SimpleNamespace

    connector = RestConnector(
        "https://ledger.example.test/records",
        auth_type="bearer",
        auth_value="test-access-token",
        mapping={
            "internal_ref": "entry_id",
            "amount": "total",
            "phone_number": "payer_phone",
            "timestamp": "created_at",
            "status": "state",
            "reference": "invoice_ref",
            "currency": "currency_code",
        },
    )
    response = SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: {
            "items": [
                {
                    "entry_id": "row-1",
                    "total": "10.00",
                    "payer_phone": "254700000000",
                    "created_at": "2026-10-13T12:00:00Z",
                    "state": "paid",
                    "invoice_ref": "invoice-1",
                    "currency_code": "KES",
                }
            ]
        },
    )
    request = {}
    monkeypatch.setattr(
        connector._session,
        "get",
        lambda endpoint, **kwargs: request.update(endpoint=endpoint, **kwargs) or response,
    )

    rows = connector.fetch_recent_records(since_minutes=24 * 60)

    assert request["headers"]["Authorization"] == "Bearer test-access-token"
    assert rows[0]["internal_ref"] == "row-1"
    assert rows[0]["reference"] == "invoice-1"
    assert rows[0]["currency"] == "KES"


def test_google_sheets_connector_normalizes_naive_timestamps_to_utc(monkeypatch):
    from types import SimpleNamespace

    timestamp = datetime.now(timezone.utc).replace(tzinfo=None).isoformat()
    worksheet = SimpleNamespace(
        get_all_records=lambda: [
            {
                "entry_id": "sheet-row",
                "total": "10.00",
                "payer_phone": "254700000000",
                "created_at": timestamp,
                "state": "paid",
                "invoice_ref": "invoice-sheet",
                "currency_code": "KES",
            }
        ]
    )
    client = SimpleNamespace(
        open_by_key=lambda sheet_id: SimpleNamespace(worksheet=lambda name: worksheet)
    )
    connector = GoogleSheetsConnector(
        "sheet-id",
        credentials_json="configured-by-test",
        mapping={
            "internal_ref": "entry_id",
            "amount": "total",
            "phone_number": "payer_phone",
            "timestamp": "created_at",
            "status": "state",
            "reference": "invoice_ref",
            "currency": "currency_code",
        },
    )
    monkeypatch.setattr(connector, "_get_client", lambda: client)

    rows = connector.fetch_recent_records()

    assert rows[0]["timestamp"].endswith("+00:00")
    assert rows[0]["reference"] == "invoice-sheet"
    assert rows[0]["currency"] == "KES"


def test_csv_connector_preserves_mapped_reference_and_currency(tmp_path):
    source = tmp_path / "ledger.csv"
    source.write_text(
        "entry_id,total,payer_phone,created_at,state,invoice_ref,currency_code\n"
        "csv-row,10.00,254700000000,2026-10-13T12:00:00Z,paid,invoice-csv,KES\n",
        encoding="utf-8",
    )
    connector = CsvLedgerConnector(
        str(source),
        mapping={
            "internal_ref": "entry_id",
            "amount": "total",
            "phone_number": "payer_phone",
            "timestamp": "created_at",
            "status": "state",
            "reference": "invoice_ref",
            "currency": "currency_code",
        },
    )

    rows = connector.fetch_recent_records()

    assert rows == [
        {
            "internal_ref": "csv-row",
            "amount": "10.00",
            "phone_number": "254700000000",
            "timestamp": "2026-10-13T12:00:00Z",
            "status": "paid",
            "reference": "invoice-csv",
            "currency": "KES",
        }
    ]


def test_non_exact_group_amounts_remain_review_outcomes_with_signed_discrepancy():
    engine = ReconciliationEngine()

    underpaid = engine.reconcile(
        _payment("MPESA-2", "100.00", "invoice-2"),
        [_entry("part-a", "40.00", "invoice-2"), _entry("part-b", "59.00", "invoice-2")],
    )
    overpaid = engine.reconcile(
        _payment("MPESA-3", "100.00", "invoice-3"),
        [_entry("part-a", "40.00", "invoice-3"), _entry("part-b", "61.00", "invoice-3")],
    )

    assert underpaid["status"] == "PARTIAL"
    assert underpaid["evidence"]["amounts"]["difference"] == "1.00"
    assert overpaid["status"] == "MISMATCH"
    assert overpaid["evidence"]["amounts"]["difference"] == "-1.00"


def test_group_matching_rejects_duplicate_row_ids_and_mixed_currency():
    engine = ReconciliationEngine()

    duplicate_rows = engine.reconcile(
        _payment("MPESA-4", "100.00", "invoice-4"),
        [_entry("same-line", "40.00", "invoice-4"), _entry("same-line", "60.00", "invoice-4")],
    )
    mixed_currency = engine.reconcile(
        _payment("MPESA-5", "100.00", "invoice-5"),
        [
            _entry("kes-line", "40.00", "invoice-5"),
            {**_entry("usd-line", "60.00", "invoice-5"), "currency": "USD"},
        ],
    )

    assert duplicate_rows["status"] == "DUPLICATE"
    assert "duplicate_internal_reference" in duplicate_rows["evidence"]["matching_rules"]
    assert mixed_currency["status"] == "EXCEPTION"
    assert "currency_mismatch" in mixed_currency["evidence"]["matching_rules"]


def test_supplied_invalid_timestamps_are_validation_exceptions():
    engine = ReconciliationEngine()

    invalid_payment_time = engine.reconcile(
        _payment("MPESA-6", "10.00", "invoice-6", timestamp="20261332120000"),
        [_entry("line-6", "10.00", "invoice-6")],
    )
    invalid_ledger_time = engine.reconcile(
        _payment("MPESA-7", "10.00", "invoice-7"),
        [_entry("line-7", "10.00", "invoice-7", timestamp="not-a-timestamp")],
    )

    assert invalid_payment_time["status"] == "EXCEPTION"
    assert "timestamp" in invalid_payment_time["reason"]
    assert invalid_ledger_time["status"] == "EXCEPTION"
    assert "timestamp" in invalid_ledger_time["reason"]


def test_explicit_invalid_currency_is_a_validation_exception():
    result = ReconciliationEngine().reconcile(
        {**_payment("MPESA-CURRENCY", "10.00", "invoice-currency"), "Currency": "KES "},
        [_entry("line-currency", "10.00", "invoice-currency")],
    )

    assert result["status"] == "MATCHED"

    invalid = ReconciliationEngine().reconcile(
        {**_payment("MPESA-CURRENCY-2", "10.00", "invoice-currency-2"), "Currency": "XYZ"},
        [_entry("line-currency-2", "10.00", "invoice-currency-2")],
    )

    assert invalid["status"] == "EXCEPTION"
    assert "currency" in invalid["reason"]


def test_reconciliation_rejects_explicit_cross_tenant_ledger_rows():
    result = ReconciliationEngine().reconcile(
        {**_payment("MPESA-TENANT", "10.00", "invoice-tenant"), "tenant_id": "tenant-a"},
        [
            {
                **_entry("tenant-b-line", "10.00", "invoice-tenant"),
                "tenant_id": "tenant-b",
            }
        ],
    )

    assert result["status"] == "EXCEPTION"
    assert result["evidence"]["matching_rules"] == ["tenant_scope_mismatch"]


def test_batch_rejects_transaction_identifier_reuse_with_changed_financial_data():
    engine = ReconciliationEngine()

    with pytest.raises(ValueError, match="conflicting financial data"):
        engine.reconcile_batch(
            [
                _payment("MPESA-CONFLICT", "10.00", "invoice-conflict"),
                _payment("MPESA-CONFLICT", "11.00", "invoice-conflict"),
            ],
            [],
        )


def test_batch_rejects_mixed_tenant_payment_and_ledger_inputs():
    with pytest.raises(ValueError, match="cannot span tenants"):
        ReconciliationEngine().reconcile_batch(
            [{**_payment("MPESA-TENANT-A", "10.00", "invoice-a"), "tenant_id": "tenant-a"}],
            [
                {
                    **_entry("tenant-b-line", "10.00", "invoice-a"),
                    "tenant_id": "tenant-b",
                }
            ],
        )


def test_batch_control_totals_are_deterministic_and_ledger_rows_are_not_reused():
    engine = ReconciliationEngine()
    payments = [
        _payment("MPESA-A", "100.00", "invoice-a"),
        _payment("MPESA-A", "100.00", "invoice-a"),
        _payment("MPESA-B", "50.00", "invoice-b"),
    ]
    entries = [
        _entry("a-2", "60.00", "invoice-a"),
        _entry("orphan", "20.00", "invoice-orphan"),
        _entry("b-1", "50.00", "invoice-b"),
        _entry("a-1", "40.00", "invoice-a"),
    ]

    first = engine.reconcile_batch(payments, entries)
    second = engine.reconcile_batch(list(reversed(payments)), list(reversed(entries)))

    assert [(item["transaction_id"], item["result"]["status"]) for item in first["results"]] == [
        ("MPESA-A", "MATCHED"),
        ("MPESA-A", "DUPLICATE"),
        ("MPESA-B", "MATCHED"),
    ]
    assert first["unmatched_ledger_records"] == [_entry("orphan", "20.00", "invoice-orphan")]
    assert first["control_totals"] == {
        "KES": {
            "payment_count": 2,
            "payment_total": "150.00",
            "ledger_count": 4,
            "ledger_total": "170.00",
            "difference": "-20.00",
            "matched_payment_total": "150.00",
            "unmatched_payment_total": "0.00",
            "unmatched_ledger_total": "20.00",
        }
    }
    assert first["control_totals"] == second["control_totals"]
    assert [(item["transaction_id"], item["result"]["status"]) for item in first["results"]] == [
        (item["transaction_id"], item["result"]["status"]) for item in second["results"]
    ]
    assert first["summary"] == {
        "payment_event_count": 3,
        "unique_payment_count": 2,
        "duplicate_payment_count": 1,
        "matched_payment_count": 2,
        "unmatched_payment_count": 0,
        "ledger_record_count": 4,
        "matched_ledger_record_count": 3,
        "unmatched_ledger_record_count": 1,
    }


def test_batch_index_preserves_amount_fallback_and_original_reference_matching():
    engine = ReconciliationEngine()
    payments = [
        _payment("MPESA-AMOUNT", "10.00", "payment-ref-not-found"),
        {
            **_payment("MPESA-REVERSAL", "20.00", "reversal-ref"),
            "TransactionType": "reversal",
            "OriginalTransID": "MPESA-ORIGINAL",
        },
    ]
    entries = [
        _entry("amount-candidate", "10.00", "different-ref"),
        {
            **_entry("original-entry", "20.00", "ledger-ref"),
            "original_reference": "MPESA-ORIGINAL",
        },
    ]
    original_entries = [dict(entry) for entry in entries]

    result = engine.reconcile_batch(payments, entries)

    assert [item["result"]["status"] for item in result["results"]] == [
        "PARTIAL",
        "MATCHED",
    ]
    assert result["results"][1]["result"]["evidence"]["matched_internal_refs"] == [
        "ORIGINAL-ENTRY"
    ]
    assert entries == original_entries


def test_postgres_connector_fetches_reference_group_and_preserves_decimal_amounts(tmp_path):
    database_path = tmp_path / "ledger.db"
    engine = create_engine(f"sqlite:///{database_path}")
    with engine.begin() as connection:
        connection.execute(text(
            """
            CREATE TABLE ledger (
                entry_id VARCHAR PRIMARY KEY,
                invoice_ref VARCHAR NOT NULL,
                amount NUMERIC NOT NULL,
                phone VARCHAR,
                created_at DATETIME NOT NULL,
                status VARCHAR NOT NULL,
                currency VARCHAR
            )
            """
        ))
        connection.execute(text(
            """
            INSERT INTO ledger (entry_id, invoice_ref, amount, phone, created_at, status, currency)
            VALUES
              ('line-1', 'invoice-42', 40.10, '', :created_at, 'paid', 'KES'),
              ('line-2', 'invoice-42', 59.90, '', :created_at, 'paid', 'KES'),
              ('legacy-kes', 'legacy-kes', 100.00, '', :created_at, 'paid', NULL),
              ('legacy-ksh', 'legacy-ksh', 100.00, '', :created_at, 'paid', 'KSH'),
              ('other', 'invoice-other', 100.00, '', :created_at, 'paid', 'KES'),
              ('usd-amount', 'invoice-usd-other', 100.00, '', :created_at, 'paid', 'USD')
            """
        ), {"created_at": datetime(2026, 10, 13, 12, 0, tzinfo=timezone.utc)})
        connection.execute(
            text(
                """
                INSERT INTO ledger (entry_id, invoice_ref, amount, phone, created_at, status, currency)
                VALUES ('line-3', :reference, 1.00, '', :created_at, 'paid', 'KES')
                """
            ),
            {
                "reference": "invoice-" + "\t" + "42",
                "created_at": datetime(2026, 10, 13, 12, 0, tzinfo=timezone.utc),
            },
        )
    engine.dispose()

    connector = PostgresConnector(
        f"sqlite:///{database_path}",
        table_name="ledger",
        mapping={
            "internal_ref": "entry_id",
            "reference": "invoice_ref",
            "amount": "amount",
            "phone_number": "phone",
            "timestamp": "created_at",
            "status": "status",
            "currency": "currency",
        },
    )

    rows = connector.fetch_candidate_records(
        since_minutes=24 * 60,
        amount=Decimal("100.00"),
        tolerance=Decimal("0.50"),
        reference="INVOICE-42",
    )

    assert [row["internal_ref"] for row in rows] == [
        "legacy-kes",
        "legacy-ksh",
        "line-1",
        "line-2",
        "line-3",
        "other",
    ]
    assert [row["reference"] for row in rows] == [
        "legacy-kes",
        "legacy-ksh",
        "invoice-42",
        "invoice-42",
        "invoice-\t42",
        "invoice-other",
    ]
    assert [Decimal(str(row["amount"])) for row in rows] == [
        Decimal("100.00"),
        Decimal("100.00"),
        Decimal("40.10"),
        Decimal("59.90"),
        Decimal("1.00"),
        Decimal("100.00"),
    ]
    assert [row.get("currency", "KES") for row in rows] == [
        "KES",
        "KSH",
        "KES",
        "KES",
        "KES",
        "KES",
    ]
    recent_rows = connector.fetch_recent_records(since_minutes=24 * 60)
    assert Decimal(str(recent_rows[0]["amount"])) == Decimal("100.00")
    assert recent_rows[0]["reference"] == "legacy-kes"

    usd_rows = connector.fetch_candidate_records(
        since_minutes=24 * 60,
        amount=Decimal("100.00"),
        tolerance=Decimal("0.50"),
        reference="INVOICE-42",
        currency="USD",
    )
    assert [row["internal_ref"] for row in usd_rows] == [
        "line-1",
        "line-2",
        "line-3",
        "usd-amount",
    ]


def test_persistence_keeps_grouped_match_rows_as_auditable_evidence(tmp_path, monkeypatch):
    from sqlalchemy.orm import sessionmaker

    import reconciliation_job
    from action_audit import Base as AuditBase
    from models import Base, ReconciliationMatch

    database_url = f"sqlite:///{tmp_path / 'grouped-match.db'}"
    engine = create_engine(database_url)
    Base.metadata.create_all(engine)
    AuditBase.metadata.create_all(engine)
    monkeypatch.setattr(
        reconciliation_job,
        "AuditSession",
        sessionmaker(bind=engine, expire_on_commit=False),
    )
    monkeypatch.setattr(reconciliation_job.settings_store, "get", lambda tenant_id: {})
    monkeypatch.setattr(reconciliation_job, "check_for_anomalies", lambda *args: [])

    event = {
        **_payment("MPESA-PERSIST-GROUP", "100.00", "invoice-persist"),
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "tenant_id": "tenant-persist",
    }
    rows = [
        _entry("persist-line-1", "40.00", "invoice-persist"),
        _entry("persist-line-2", "60.00", "invoice-persist"),
    ]

    class ReferenceAwareConnector:
        def fetch_candidate_records(self, **kwargs):
            return rows

    class Registry:
        def get_connector(self, tenant_id):
            assert tenant_id == "tenant-persist"
            return ReferenceAwareConnector()

    try:
        assert reconciliation_job._process_message_unbounded(
            event,
            None,
            None,
            Registry(),
        ) is True
        with sessionmaker(bind=engine)() as session:
            match = session.query(ReconciliationMatch).one()
            assert match.status == "MATCHED"
            assert match.matched_record == {
                "match_type": "one_to_many",
                "records": rows,
                "internal_refs": ["PERSIST-LINE-1", "PERSIST-LINE-2"],
            }
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    ("amount", "expected_tolerance"),
    [("100.00", Decimal("0.5000")), ("0.25", Decimal("0.01"))],
    ids=["percentage-tolerance", "currency-minor-unit-floor"],
)
def test_reconciliation_worker_requests_all_ledger_rows_for_the_payment_reference(
    monkeypatch,
    amount,
    expected_tolerance,
):
    import reconciliation_job
    from event_store import ProcessResult

    received = {}

    class ReferenceAwareConnector:
        def fetch_candidate_records(
            self,
            *,
            since_minutes,
            amount,
            phone_number,
            tolerance,
            currency,
            limit,
            reference,
        ):
            received.update(
                reference=reference,
                amount=amount,
                tolerance=tolerance,
                currency=currency,
                limit=limit,
            )
            return []

    class Registry:
        def get_connector(self, tenant_id):
            return ReferenceAwareConnector()

    monkeypatch.setattr(reconciliation_job.settings_store, "get", lambda tenant_id: {})
    monkeypatch.setattr(reconciliation_job, "check_for_anomalies", lambda *args: [])
    monkeypatch.setattr(
        reconciliation_job.reconciliation_engine,
        "reconcile",
        lambda *args, **kwargs: {
            "status": "MATCHED",
            "evidence": {"matching_rules": [], "match_timestamp": "2026-10-13T12:00:00+00:00"},
            "processing_latency_ms": 0,
        },
    )
    monkeypatch.setattr(
        reconciliation_job,
        "_persist_atomically",
        lambda *args, **kwargs: ProcessResult.STORED,
    )

    processed = reconciliation_job._process_message_unbounded(
        {
            "TransID": "MPESA-WORKER",
            "TransAmount": amount,
            "Currency": "KES",
            "TransTime": "20261013120000",
            "BillRefNumber": " invoice 42 ",
            "tenant_id": "tenant-a",
        },
        None,
        None,
        Registry(),
    )

    assert processed is True
    assert received == {
        "reference": "invoice 42",
        "amount": Decimal(amount),
        "tolerance": expected_tolerance,
        "currency": "KES",
        "limit": 500,
    }
