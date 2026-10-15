from decimal import Decimal

import pytest

from aggregation_service import aggregate_transactions, update_merchant_metrics
from api_validation import ApiContractError, validate_transaction_create
from anomaly_rules import check_for_anomalies, score_transaction_anomaly
from fraud_risk_engine import assess_transaction
from ingestion import IngestionService, MpesaAdapter
from models import Base, MerchantMetric
from normalization import (
    CurrencyMismatchError,
    NormalizationError,
    Money,
    MAX_TRANSACTION_AMOUNT,
    normalize_amount,
    normalize_currency,
    quantize_money,
)
from reconciliation_engine import ReconciliationEngine, evaluate_transaction
from reconciliation_scoring import score_match
from realtime_pipeline import normalize_transaction_payload
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


@pytest.mark.parametrize(
    ("value", "currency", "expected"),
    [
        ("12.34", "KES", "12.34"),
        ("1e2", "USD", "100.00"),
        (1e2, "EUR", "100.00"),
        ("123", "UGX", "123"),
        ("1.234", "BHD", "1.234"),
    ],
)
def test_normalization_is_exact_and_currency_scaled(value, currency, expected):
    assert normalize_amount(value, currency=currency) == expected


@pytest.mark.parametrize(
    ("value", "currency"),
    [
        ("NaN", "KES"),
        ("Infinity", "KES"),
        ("-Infinity", "KES"),
        (float("nan"), "KES"),
        (float("inf"), "KES"),
        (True, "KES"),
        ("0", "KES"),
        ("-1", "USD"),
        ("0.001", "KES"),
        ("0.5", "UGX"),
        ("1.2345", "BHD"),
        ("1e-9999", "BHD"),
        ("1e100", "KES"),
        (MAX_TRANSACTION_AMOUNT + Decimal("1"), "BHD"),
        ("1.00", "XYZ"),
    ],
)
def test_normalization_rejects_unsafe_amounts_and_unsupported_currencies(value, currency):
    with pytest.raises(NormalizationError):
        normalize_amount(value, currency=currency)


@pytest.mark.parametrize(("value", "currency", "expected"), [
    ("1.005", "KES", Decimal("1.01")),
    ("1.2345", "BHD", Decimal("1.235")),
    ("1.5", "UGX", Decimal("2")),
])
def test_derived_quantization_uses_explicit_half_up_minor_unit_rounding(value, currency, expected):
    from normalization import parse_decimal

    assert quantize_money(parse_decimal(value), currency=currency) == expected


def test_amount_representation_is_identical_in_batch_and_realtime_ingestion():
    original = {
        "transaction_id": "multi-currency-1",
        "amount": "1.234",
        "currency": "BHD",
        "account_id": "account-1",
        "timestamp": "2026-10-13T10:00:00Z",
    }

    class Store:
        def mark_processed(self, payload, **kwargs):
            self.payload = payload
            from event_store import ProcessResult

            return ProcessResult.STORED

    store = Store()
    batch = IngestionService(store).ingest("bank", original, tenant_id="tenant-a")
    realtime = normalize_transaction_payload(
        {
            "TransID": "multi-currency-1",
            "TransAmount": "1.234",
            "Currency": "bhd",
            "provider": "bank",
            "provider_account_id": "account-1",
        }
    )

    assert batch.envelope.canonical.amount == realtime["TransAmount"] == "1.234"
    assert batch.envelope.canonical.currency == realtime["Currency"] == "BHD"
    assert batch.envelope.raw_payload["amount"] == "1.234"
    assert batch.envelope.payload["raw_payload"]["amount"] == "1.234"
    assert store.payload["raw_payload"]["amount"] == "1.234"
    assert realtime["raw_payload"]["TransAmount"] == "1.234"

    spoofed_raw_payload = normalize_transaction_payload(
        {
            "TransID": "multi-currency-2",
            "TransAmount": "2.345",
            "Currency": "BHD",
            "provider": "bank",
            "provider_account_id": "account-1",
            "raw_payload": {"TransAmount": "999.999"},
        }
    )
    assert spoofed_raw_payload["raw_payload"]["TransAmount"] == "2.345"


def test_mpesa_adapter_preserves_provider_amount_representation():
    source = {
        "TransID": "MP-RAW-AMOUNT",
        "TransAmount": "1.0",
        "Currency": "kes",
        "MSISDN": "254700000000",
        "BusinessShortCode": "600000",
        "TransTime": "20261013120000",
        "TransactionType": "Pay Bill",
    }

    envelope = MpesaAdapter().normalize(source, tenant_id="tenant-a")

    assert envelope.canonical.amount == "1.00"
    assert envelope.canonical.currency == "KES"
    assert envelope.raw_payload["TransAmount"] == "1.0"
    assert envelope.raw_payload["Currency"] == "kes"


def test_secondary_ingestion_and_quality_paths_use_the_shared_money_contract():
    from core_data_engineering import normalize_record
    from dq_rules import _rule_amount_positive, _rule_currency_valid

    valid_record = normalize_record(
        "tenant-a",
        "import",
        {
            "TransID": "secondary-bhd",
            "TransAmount": "1.234",
            "Currency": "BHD",
            "TransTime": "20261013120000",
        },
    )
    assert valid_record.amount == Decimal("1.234")
    assert valid_record.currency == "BHD"

    for amount in ("NaN", "Infinity", "1.001"):
        passed, failure = _rule_amount_positive(
            {"TransAmount": amount, "Currency": "KES"},
            None,
        )
        assert not passed
        assert failure is not None

    assert _rule_amount_positive(
        {"TransAmount": "1.234", "Currency": "BHD"}, None
    ) == (True, None)
    assert _rule_currency_valid({"Currency": "GBP"}, None) == (True, None)


def test_legacy_reconciliation_utility_uses_minor_units_and_rejects_mixed_currencies():
    from reconciliation_utils import exact_match, normalize_daraja_event

    normalized = normalize_daraja_event(
        {"TransID": "raw-1", "TransAmount": "1.234", "Currency": "BHD"}
    )
    assert normalized["TransAmount"] == "1.234"
    assert normalized["Currency"] == "BHD"
    assert exact_match(
        {"TransID": "bhd-1", "TransAmount": "1.234", "Currency": "BHD"},
        {"reference": "bhd-1", "amount": "1.234", "currency": "BHD"},
    )
    assert not exact_match(
        {"TransID": "mixed-1", "TransAmount": "1.00", "Currency": "KES"},
        {"reference": "mixed-1", "amount": "1.00", "currency": "USD"},
    )


def test_legacy_reconciliation_uses_currency_scale_and_skips_mixed_currency_records():
    event = {
        "TransID": "legacy-bhd",
        "TransAmount": "1.234",
        "Currency": "BHD",
        "MSISDN": "254700000000",
        "TransTime": "20261013120000",
    }
    record = {
        "internal_ref": "ledger-bhd",
        "amount": "1.234",
        "currency": "BHD",
        "phone_number": "254700000000",
        "timestamp": "2026-10-13T12:00:00Z",
        "status": "pending",
    }

    result = evaluate_transaction(event, [record], seen_trans_ids=set())

    assert result["status"] == "matched"

    mixed_currency_record = {**record, "currency": "KES"}
    mixed_result = evaluate_transaction(event, [mixed_currency_record], seen_trans_ids=set())

    assert mixed_result["status"] == "missing_payment"
    assert mixed_result["match"]["reason"] == "no_matching_record"


def test_ingestion_and_api_reject_nonfinite_and_excess_precision():
    payload = {
        "provider": "bank",
        "provider_transaction_id": "tx-1",
        "provider_account_id": "account-1",
        "TransAmount": "Infinity",
        "Currency": "KES",
        "MSISDN": "254700000000",
        "TransTime": "20261013120000",
    }
    with pytest.raises(ApiContractError, match="amount"):
        validate_transaction_create(payload)

    class Store:
        def mark_processed(self, *_args, **_kwargs):
            raise AssertionError("invalid data must not reach persistence")

    with pytest.raises(ValueError):
        IngestionService(Store()).ingest(
            "bank",
            {**payload, "amount": "NaN"},
            tenant_id="tenant-a",
        )


@pytest.mark.parametrize("value", ["NaN", "Infinity", "-Infinity", "1e100"])
def test_scoring_entry_points_reject_nonfinite_and_out_of_range_amounts(value):
    event = {"TransID": "score-1", "TransAmount": value, "Currency": "KES"}
    with pytest.raises(NormalizationError):
        score_transaction_anomaly(event)
    with pytest.raises(NormalizationError):
        assess_transaction(event)
    assert "invalid_or_zero_amount" in check_for_anomalies(event, set())
    with pytest.raises(NormalizationError):
        score_match(
            {"TransID": "score-1", "TransAmount": value, "Currency": "KES"},
            {"internal_ref": "score-1", "amount": "1.00", "currency": "KES"},
        )


def test_scoring_and_reconciliation_do_not_compare_different_currencies():
    event = {
        "TransID": "ref-1",
        "TransAmount": "100.00",
        "Currency": "KES",
        "TransTime": "2026-10-13T10:00:00Z",
    }
    internal = {
        "internal_ref": "ref-1",
        "reference": "ref-1",
        "amount": "100.00",
        "currency": "USD",
        "timestamp": "2026-10-13T10:00:00Z",
    }
    result = ReconciliationEngine().reconcile(event, [internal])
    assert result["status"] == "EXCEPTION"
    assert "currency_mismatch" in result["evidence"]["matching_rules"]

    score, reasons = score_match(event, internal)
    assert score == 0.0
    assert "currency_mismatch" in reasons["anomaly_features"]
    with pytest.raises(CurrencyMismatchError):
        _ = Money("1.00", "KES") + Money("1.00", "USD")


def test_aggregation_and_persisted_metrics_are_separated_by_currency(tmp_path):
    records = [
        {"merchant_id": "shop-1", "amount": "10.00", "currency": "KES"},
        {"merchant_id": "shop-1", "amount": "10.00", "currency": "USD"},
    ]
    aggregates = aggregate_transactions(records)
    assert aggregates[("shop-1", "KES")].total_volume == Decimal("10.00")
    assert aggregates[("shop-1", "USD")].total_volume == Decimal("10.00")

    engine = create_engine(f"sqlite:///{tmp_path / 'currency-metrics.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    with session_factory() as session:
        for record in records:
            update_merchant_metrics(
                session,
                {**record, "transaction_time": "2026-10-13T10:00:00Z"},
                tenant_id="tenant-a",
                granularities=("day",),
            )
        session.commit()
        rows = session.query(MerchantMetric).all()
        assert {(row.currency, row.total_volume) for row in rows} == {
            ("KES", Decimal("10.00")),
            ("USD", Decimal("10.00")),
        }
    engine.dispose()


def test_currency_alias_and_string_number_normalization_are_equivalent():
    assert normalize_currency("ksh") == normalize_currency("KES") == "KES"
    assert normalize_amount(12.5, currency="USD") == normalize_amount("1.25e1", currency="USD")
