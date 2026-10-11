import importlib.util
import pathlib
import sys


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
    assert result["metadata"]["amount"] == 2500.0
    assert result["metadata"]["state"] == "VALIDATED"


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
        "history": [
            {
                "TransID": "FRAUD-41",
                "TransAmount": 5000,
                "MSISDN": "254700000666",
                "BusinessShortCode": "123456",
            }
        ],
        "tenant_baseline": {"mean_amount": 5000, "std_amount": 2000},
    }

    result = ALGORITHMS.evaluate_algorithm("Risk Score Calculation Algorithm", payload)

    assert result["status"] in {"passed", "needs_review"}
    assert 0.0 <= float(result["metadata"]["risk_score"]) <= 1.0


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
