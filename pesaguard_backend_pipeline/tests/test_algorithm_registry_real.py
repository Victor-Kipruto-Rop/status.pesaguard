import hashlib
import hmac
import importlib.util
import pathlib
import sys
from types import SimpleNamespace

import pytest


MODULE_PATH = pathlib.Path(__file__).resolve().parents[1] / "algorithms.py"
SPEC = importlib.util.spec_from_file_location("pesaguard_algorithms_real", MODULE_PATH)
ALGORITHMS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ALGORITHMS
SPEC.loader.exec_module(ALGORITHMS)


def test_transaction_validation_algorithm_passes_valid_transaction():
    tx = {
        "transaction_id": "TX-1001",
        "amount": 2500.0,
        "currency": "KES",
        "timestamp": "2024-06-01T12:00:00Z",
        "status": "VALIDATED",
    }

    result = ALGORITHMS.evaluate_algorithm("Transaction Validation Algorithm", tx)

    assert result["status"] == "passed"
    assert result["metadata"]["amount"] == "2500.00"
    assert result["metadata"]["state"] == "VALIDATED"


@pytest.mark.parametrize("amount", ["NaN", "Infinity", "-Infinity", "1.001", "1e100"])
def test_transaction_validation_does_not_accept_invalid_money(amount):
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Validation Algorithm",
        {
            "transaction_id": "TX-INVALID-MONEY",
            "amount": amount,
            "currency": "KES",
            "timestamp": "2024-06-01T12:00:00Z",
            "status": "VALIDATED",
        },
    )

    assert result["status"] != "passed"


def test_transaction_validation_algorithm_rejects_unknown_lifecycle_state():
    tx = {
        "transaction_id": "TX-1002",
        "amount": 100.0,
        "currency": "KES",
        "timestamp": "2024-06-01T12:00:00Z",
        "status": "NOT_A_REAL_STATE",
    }

    result = ALGORITHMS.evaluate_algorithm("Transaction Validation Algorithm", tx)

    assert result["status"] == "failed"
    assert "not valid for the lifecycle model" in result["evidence"]


def test_transaction_normalization_uses_canonical_domain_normalizers():
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Normalization Algorithm",
        {
            "TransID": " mp-100 ",
            "TransAmount": "1,250.50",
            "Currency": "Kenyan Shilling",
            "TransTime": "2024-06-01 15:00 EAT",
            "TransactionType": "C2B",
            "Provider": "M-PESA",
        },
    )

    assert result["status"] == "passed"
    normalized = result["metadata"]["normalized"]
    assert normalized == {
        "transaction_id": "MP-100",
        "amount": "1250.50",
        "currency": "KES",
        "timestamp": "2024-06-01T12:00:00Z",
        "transaction_type": "PAYMENT",
        "provider": "mpesa",
    }


def test_transaction_normalization_rejects_invalid_amount_without_defaulting():
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Normalization Algorithm",
        {
            "TransID": "MP-101",
            "TransAmount": "NaN",
            "Currency": "KES",
            "TransTime": "20240601120000",
        },
    )

    assert result["status"] == "failed"
    assert "normalized" not in result["metadata"]


def test_transaction_normalization_uses_currency_minor_units():
    valid = ALGORITHMS.evaluate_algorithm(
        "Transaction Normalization Algorithm",
        {
            "TransID": "MP-BHD",
            "TransAmount": "1.234",
            "Currency": "BHD",
            "TransTime": "20240601120000",
        },
    )
    invalid = ALGORITHMS.evaluate_algorithm(
        "Transaction Normalization Algorithm",
        {
            "TransID": "MP-KES",
            "TransAmount": "1.001",
            "Currency": "KES",
            "TransTime": "20240601120000",
        },
    )

    assert valid["status"] == "passed"
    assert valid["metadata"]["normalized"]["amount"] == "1.234"
    assert invalid["status"] == "failed"
    assert "normalized" not in invalid["metadata"]


def test_transaction_classification_uses_canonical_type_mapping():
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Classification Algorithm",
        {"TransactionType": "STK Push"},
    )

    assert result["status"] == "passed"
    assert result["metadata"]["transaction_type"] == "PAYMENT"


def test_transaction_state_machine_checks_real_transition_graph():
    valid = ALGORITHMS.evaluate_algorithm(
        "Transaction State Machine Algorithm",
        {"from_state": "RECEIVED", "to_state": "VALIDATED"},
    )
    invalid = ALGORITHMS.evaluate_algorithm(
        "Transaction State Machine Algorithm",
        {"from_state": "RECEIVED", "to_state": "RECONCILED"},
    )

    assert valid["status"] == "passed"
    assert invalid["status"] == "failed"


def test_transaction_deduplication_detects_reused_provider_transaction_id():
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Deduplication Algorithm",
        {
            "transactions": [
                {"TransID": "MP-200", "TransAmount": "25.00", "TransTime": "20240601120000"},
                {"TransID": "MP-200", "TransAmount": "25.00", "TransTime": "20240601120000"},
            ]
        },
    )

    assert result["status"] == "failed"
    assert result["metadata"]["duplicate_count"] == 1


def test_transaction_deduplication_fails_closed_on_invalid_amount():
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Deduplication Algorithm",
        {"transactions": [{"TransID": "BAD-AMOUNT", "TransAmount": "NaN"}]},
    )

    assert result["status"] == "failed"
    assert result["metadata"]["invalid_record_indices"] == [0]


def test_transaction_deduplication_does_not_match_records_without_identity():
    result = ALGORITHMS.evaluate_algorithm(
        "Transaction Deduplication Algorithm",
        {"transactions": [{"amount": 10}, {"amount": 10}]},
    )

    assert result["status"] == "failed"
    assert result["metadata"]["invalid_record_indices"] == [0, 1]


def test_idempotency_key_is_stable_without_exposing_pii():
    payload = {
        "MSISDN": "0712345678",
        "TransAmount": "1,250.00",
        "TransTime": "2024-06-01T15:00:00+03:00",
    }

    first = ALGORITHMS.evaluate_algorithm("Idempotency Key Algorithm", payload)
    second = ALGORITHMS.evaluate_algorithm("Idempotency Key Algorithm", dict(payload))

    assert first["status"] == "passed"
    assert first["metadata"]["idempotency_key"] == second["metadata"]["idempotency_key"]
    assert "0712345678" not in first["metadata"]["idempotency_key"]


def test_transaction_ordering_flags_out_of_order_events():
    result = ALGORITHMS.evaluate_algorithm(
        "Out-of-Order Event Processing Algorithm",
        {
            "transactions": [
                {"TransID": "MP-1", "TransTime": "20240601120000"},
                {"TransID": "MP-2", "TransTime": "20240601115959"},
            ]
        },
    )

    assert result["status"] == "needs_review"
    assert result["metadata"]["out_of_order_indices"] == [1]


def test_daraja_callback_signature_requires_configured_secret_and_raw_body():
    result = ALGORITHMS.evaluate_algorithm(
        "Callback Signature Verification Algorithm",
        {
            "merchant_id": "123456",
            "amount": "2500",
            "timestamp": "20240601120000",
            "signature": "00" * 32,
        },
    )

    assert result["status"] == "failed"
    assert "secret" in result["evidence"].lower()
    assert "expected_signature" not in result["metadata"]


def test_daraja_callback_algorithm_verifies_hmac_signature():
    secret = "shared-secret-32-bytes-long-abc123"
    body = b'{"merchant_id":"123456","amount":"2500","timestamp":"20240601120000","reference":"INV-404"}'
    payload = {
        "merchant_id": "123456",
        "amount": "2500",
        "timestamp": "20240601120000",
        "reference": "INV-404",
        "shared_secret": secret,
        "signed_payload": body,
    }
    payload["signature"] = hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()

    result = ALGORITHMS.evaluate_algorithm("Callback Signature Verification Algorithm", payload)

    assert result["status"] == "passed"
    assert result["metadata"]["merchant_id"] == "123456"


def test_exact_matching_algorithm_uses_reconciliation_engine():
    payload = {
        "transaction": {
            "TransID": "MP-9001",
            "TransAmount": 2500,
            "BillRefNumber": "INV-100",
            "TransTime": "20240601120000",
            "MSISDN": "254700000001",
        },
        "internal_records": [
            {
                "internal_ref": "INV-100",
                "amount": 2500,
                "timestamp": "2024-06-01T12:00:00Z",
                "phone_number": "254700000001",
            }
        ],
    }

    result = ALGORITHMS.evaluate_algorithm("Exact Transaction Matching Algorithm", payload)

    assert result["status"] == "passed"
    assert result["metadata"]["reconciliation_status"] == "MATCHED"


def test_risk_scoring_algorithm_uses_fraud_engine():
    payload = {
        "TransID": "FRAUD-42",
        "TransAmount": 900000,
        "MSISDN": "254700000666",
        "BusinessShortCode": "123456",
        "TransTime": "20240601120000",
        "history": [
            {
                "TransID": "FRAUD-41",
                "TransAmount": 5000,
                "MSISDN": "254700000666",
                "BusinessShortCode": "123456",
                "TransTime": "20240601110000",
            }
        ],
        "tenant_baseline": {"mean_amount": 5000, "std_amount": 2000},
    }

    result = ALGORITHMS.evaluate_algorithm("Risk Score Calculation Algorithm", payload)

    assert result["status"] in {"passed", "needs_review"}
    assert "risk_score" in result["metadata"], result
    assert 0.0 <= float(result["metadata"]["risk_score"]) <= 1.0


def test_risk_scoring_accepts_derived_baselines_with_subminor_precision():
    result = ALGORITHMS.evaluate_algorithm(
        "Risk Score Calculation Algorithm",
        {
            "TransID": "DERIVED-BASELINE",
            "TransAmount": "10.00",
            "Currency": "KES",
            "TransTime": "20240601120000",
            "tenant_baseline": {
                "currency": "KES",
                "mean_amount": "10.005",
                "std_amount": "1.001",
            },
        },
    )

    assert result["status"] in {"passed", "needs_review"}
    assert "risk_score" in result["metadata"]


def test_risk_scoring_algorithm_routes_high_risk_to_human_review():
    payload = {
        "TransID": "FRAUD-2",
        "TransAmount": 300000,
        "MSISDN": "254700000666",
        "BusinessShortCode": "123456",
        "TransTime": "20240601030000",
        "history": [
            {
                "TransID": "FRAUD-41",
                "TransAmount": 299000,
                "MSISDN": "254700000666",
                "BusinessShortCode": "123456",
                "TransTime": "20240601025959",
            },
            *[
                {
                    "TransID": f"FRAUD-{index}",
                    "TransAmount": 298000 - index * 1000,
                    "MSISDN": "254700000666",
                    "BusinessShortCode": "123456",
                    "TransTime": f"202406010259{59 - index:02d}",
                }
                for index in range(4)
            ],
        ],
    }

    result = ALGORITHMS.evaluate_algorithm("Risk Score Calculation Algorithm", payload)

    assert result["status"] == "needs_review"
    assert result["metadata"]["risk_level"] in {"HIGH", "CRITICAL"}
    assert result["metadata"]["action"] in {"review", "escalate"}
    assert result["metadata"]["action"] != "confirm_fraud"
    assert "MSISDN" not in result["metadata"]


@pytest.mark.parametrize(
    "payload",
    [
        {"TransID": "MISSING-AMOUNT", "TransTime": "20240601120000"},
        {"TransID": "NAN-AMOUNT", "TransAmount": "nan", "TransTime": "20240601120000"},
        {"TransID": "UNREPRESENTABLE-AMOUNT", "TransAmount": "1e10000", "TransTime": "20240601120000"},
        {"TransID": "NO-TIME", "TransAmount": 100},
        {"TransID": "BAD-TIME", "TransAmount": 100, "TransTime": "not-a-time"},
        {
            "TransID": "BAD-HISTORY",
            "TransAmount": 100,
            "TransTime": "20240601120000",
            "history": ["not-a-transaction"],
        },
        {
            "TransID": "BAD-HISTORY-AMOUNT",
            "TransAmount": 100,
            "TransTime": "20240601120000",
            "history": [{"TransID": "OLD", "TransAmount": "NaN"}],
        },
        {
            "TransID": "BAD-BASELINE",
            "TransAmount": 100,
            "TransTime": "20240601120000",
            "tenant_baseline": {"mean_amount": "Infinity"},
        },
    ],
)
def test_risk_scoring_algorithm_fails_closed_on_invalid_inputs(payload):
    result = ALGORITHMS.evaluate_algorithm("Risk Score Calculation Algorithm", payload)

    assert result["status"] == "failed"
    assert "risk_score" not in result["metadata"]


def test_risk_scoring_algorithm_fails_when_engine_is_unavailable(monkeypatch):
    monkeypatch.setattr(ALGORITHMS, "_load_module", lambda _module_name: None)

    result = ALGORITHMS.evaluate_algorithm(
        "Risk Score Calculation Algorithm",
        {
            "TransID": "ENGINE-UNAVAILABLE",
            "TransAmount": 100,
            "TransTime": "20240601120000",
        },
    )

    assert result["status"] == "failed"
    assert "engine is unavailable" in result["evidence"]
    assert "risk_score" not in result["metadata"]


def test_risk_scoring_algorithm_rejects_invalid_engine_decision(monkeypatch):
    engine = SimpleNamespace(
        assess_transaction=lambda *_args: SimpleNamespace(
            risk_score=float("nan"),
            risk_level="LOW",
            action="process",
            model_version="test-model",
            reason_codes=(),
            rules_triggered=(),
        )
    )
    monkeypatch.setattr(ALGORITHMS, "_load_module", lambda _module_name: engine)

    result = ALGORITHMS.evaluate_algorithm(
        "Risk Score Calculation Algorithm",
        {
            "TransID": "INVALID-DECISION",
            "TransAmount": 100,
            "TransTime": "20240601120000",
        },
    )

    assert result["status"] == "failed"
    assert "risk_score" not in result["metadata"]


def test_callback_replay_protection_algorithm_generates_request_hash():
    payload = {
        "TransID": "MP-9002",
        "merchant_id": "123456",
        "amount": 100.0,
        "timestamp": "20240601120000",
        "reference": "INV-200",
    }

    result = ALGORITHMS.evaluate_algorithm("Callback Replay Protection Algorithm", payload)

    assert result["status"] == "passed"
    assert result["metadata"]["request_hash"]


def test_ledger_algorithm_enforces_balance_invariant():
    entry = {"debit": 200.0, "credit": 250.0, "balance": 50.0}

    result = ALGORITHMS.evaluate_algorithm("Debit-Credit Balance Validation Algorithm", entry)

    assert result["status"] == "passed"
    assert result["metadata"]["expected_balance"] == 50.0

    bad_entry = {"debit": 200.0, "credit": 250.0, "balance": 12.0}
    bad_result = ALGORITHMS.evaluate_algorithm("Debit-Credit Balance Validation Algorithm", bad_entry)

    assert bad_result["status"] == "failed"


@pytest.mark.parametrize(
    "entry",
    [
        {"debit": 10, "credit": 10},
        {"debit": "invalid", "credit": 0, "balance": 0},
        {"debit": "NaN", "credit": 0, "balance": 0},
        {"debit": 10, "credit": 10, "balance": "Infinity"},
        {"debit": "1e10000", "credit": 0, "balance": 0},
        {"debit": -1, "credit": 0, "balance": 0},
    ],
)
def test_ledger_algorithm_rejects_incomplete_or_invalid_values(entry):
    result = ALGORITHMS.evaluate_algorithm("Debit-Credit Balance Validation Algorithm", entry)

    assert result["status"] == "failed"


def test_ledger_algorithm_uses_decimal_precision_for_balance_invariant():
    entry = {"debit": "0.10", "credit": "0.30", "balance": "0.20"}

    result = ALGORITHMS.evaluate_algorithm("Debit-Credit Balance Validation Algorithm", entry)

    assert result["status"] == "passed"
    assert result["metadata"]["expected_balance"] == pytest.approx(0.2)


def test_ledger_algorithm_preserves_existing_one_cent_tolerance():
    within_tolerance = ALGORITHMS.evaluate_algorithm(
        "Debit-Credit Balance Validation Algorithm",
        {"debit": "0", "credit": "1", "balance": "0.99"},
    )
    outside_tolerance = ALGORITHMS.evaluate_algorithm(
        "Debit-Credit Balance Validation Algorithm",
        {"debit": "0", "credit": "1", "balance": "0.9899"},
    )

    assert within_tolerance["status"] == "passed"
    assert outside_tolerance["status"] == "failed"


def test_catalogued_ledger_algorithm_without_specific_evaluator_needs_review():
    result = ALGORITHMS.evaluate_algorithm(
        "Immutable Ledger Entry Algorithm",
        {"debit": 10, "credit": 10, "balance": 0},
    )

    assert result["status"] == "needs_review"
    assert result["metadata"]["implementation_status"] == "not_implemented"


def test_catalogued_fraud_algorithm_without_specific_evaluator_needs_review():
    result = ALGORITHMS.evaluate_algorithm(
        "Duplicate Transaction Anomaly Detection Algorithm",
        {
            "TransID": "DUP-1",
            "TransAmount": 100,
            "TransTime": "20240601120000",
        },
    )

    assert result["status"] == "needs_review"
    assert result["metadata"]["implementation_status"] == "not_implemented"


def test_daraja_callback_uses_tenant_configured_secret():
    import os
    secret = "tenant-secret-abc123"
    # Configure environment-level Daraja secret used by shared.daraja.config.DarajaConfig
    os.environ["DARAJA_CONSUMER_SECRET"] = secret

    body = b'{"merchant_id":"123456","amount":"2500","timestamp":"20240601120000","reference":"INV-404"}'
    payload = {
        "merchant_id": "123456",
        "amount": "2500",
        "timestamp": "20240601120000",
        "reference": "INV-404",
        # no shared_secret in payload; evaluator should look up tenant config
        "signed_payload": body,
    }
    payload["signature"] = hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()

    result = ALGORITHMS.evaluate_algorithm("Callback Signature Verification Algorithm", payload)

    assert result["status"] == "passed"
    assert result["metadata"]["merchant_id"] == "123456"
    result = ALGORITHMS.evaluate_algorithm(
        "Duplicate Transaction Anomaly Detection Algorithm",
        {
            "TransID": "DUP-1",
            "TransAmount": 100,
            "TransTime": "20240601120000",
        },
    )

    assert result["status"] == "needs_review"
    assert result["metadata"]["implementation_status"] == "not_implemented"
