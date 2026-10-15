from __future__ import annotations

import hashlib
import hmac
import importlib
import json

import pytest


@pytest.fixture()
def signed_webhook(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path / 'ingestion.db'}")
    monkeypatch.setenv("DARAJA_ALLOWED_IPS", "127.0.0.1")
    monkeypatch.setenv("DARAJA_SHARED_SECRET", "source-secret")
    monkeypatch.setenv("DARAJA_WEBHOOK_SIGNING_SECRET", "callback-signing-secret")
    monkeypatch.delenv("DARAJA_CONSUMER_SECRET", raising=False)
    monkeypatch.setenv("TENANT_ID", "tenant-a")
    webhook_app = importlib.reload(importlib.import_module("app"))
    webhook_app.app.config.update(TESTING=True)
    with webhook_app.app.test_client() as client:
        yield client, webhook_app
    if webhook_app.event_store.engine is not None:
        webhook_app.event_store.engine.dispose()


def _payload(transaction_id: str = "INGEST-1") -> dict[str, str]:
    return {
        "TransactionType": "Pay Bill",
        "TransID": transaction_id,
        "TransTime": "20261013120000",
        "TransAmount": "10.50",
        "BusinessShortCode": "123456",
        "MSISDN": "254700000000",
        "schema_version": "1.0",
    }


def _post_signed(
    client,
    payload: dict,
    secret: str = "callback-signing-secret",
    path: str = "/webhook/mpesa/confirmation",
):
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    signature = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return client.post(
        path,
        data=body,
        content_type="application/json",
        headers={
            "X-Daraja-Shared-Secret": "source-secret",
            "X-Daraja-Signature": signature,
        },
    )


def _post_raw_signed(client, body: bytes, path: str = "/webhook/mpesa/confirmation"):
    signature = hmac.new(b"callback-signing-secret", body, hashlib.sha256).hexdigest()
    return client.post(
        path,
        data=body,
        content_type="application/json",
        headers={
            "X-Daraja-Shared-Secret": "source-secret",
            "X-Daraja-Signature": signature,
        },
    )


def test_callback_requires_separate_raw_body_signature(signed_webhook, monkeypatch):
    client, _ = signed_webhook
    payload = _payload("NO-SIGNATURE")
    body = json.dumps(payload, separators=(",", ":")).encode()

    missing = client.post(
        "/webhook/mpesa/confirmation",
        data=body,
        content_type="application/json",
        headers={"X-Daraja-Shared-Secret": "source-secret"},
    )
    invalid = client.post(
        "/webhook/mpesa/confirmation",
        data=body,
        content_type="application/json",
        headers={
            "X-Daraja-Shared-Secret": "source-secret",
            "X-Daraja-Signature": "00" * 32,
        },
    )

    assert missing.status_code == 403
    assert invalid.status_code == 403

    monkeypatch.delenv("DARAJA_WEBHOOK_SIGNING_SECRET")
    unavailable = client.post(
        "/webhook/mpesa/confirmation",
        data=body,
        content_type="application/json",
        headers={
            "X-Daraja-Shared-Secret": "source-secret",
            "X-Daraja-Signature": "00" * 32,
        },
    )
    assert unavailable.status_code == 503


def test_signed_callback_is_tracked_and_duplicate_attempts_are_counted(signed_webhook):
    from models import ProcessedTransaction, Transaction

    client, webhook_app = signed_webhook
    payload = _payload("SIGNED-1")

    first = _post_signed(client, payload)
    duplicate = _post_signed(client, payload)

    assert first.status_code == 200
    assert duplicate.status_code == 200
    assert duplicate.json["ResultDesc"] == "Accepted (duplicate ignored)"
    with webhook_app.event_store.Session() as session:
        transaction = session.query(Transaction).filter_by(
            tenant_id="tenant-a",
            trans_id="SIGNED-1",
        ).one()
        processed = session.query(ProcessedTransaction).filter_by(
            tenant_id="tenant-a",
            daraja_trans_id="SIGNED-1",
        ).one()
        assert processed.status == "stored"
        assert processed.signature_verified is True
        assert processed.source_ip == "127.0.0.1"
        assert processed.webhook_attempt_number == 2
        assert transaction.source_schema_version == "1.0"
        assert transaction.raw_payload_hash
        assert len(transaction.raw_payload_hash) == 64


def test_invalid_version_is_dead_lettered_idempotently(signed_webhook):
    from models import DeadLetter, Transaction

    client, webhook_app = signed_webhook
    payload = _payload("BAD-SCHEMA")
    payload["schema_version"] = "2.0"

    first = _post_signed(client, payload)
    retry = _post_signed(client, payload)

    assert first.status_code == 400
    assert retry.status_code == 400
    with webhook_app.event_store.Session() as session:
        assert session.query(Transaction).filter_by(trans_id="BAD-SCHEMA").count() == 0
        dead_letter = session.query(DeadLetter).filter_by(
            tenant_id="tenant-a",
            reason="validation_failed",
        ).one()
        assert dead_letter.event_key


@pytest.mark.parametrize(
    "body",
    [
        b'{"TransID":"one","TransID":"two"}',
        b'{"TransAmount":NaN}',
    ],
)
def test_nonstandard_json_is_dead_lettered_without_ingestion(signed_webhook, body):
    from models import DeadLetter, Transaction

    client, webhook_app = signed_webhook
    response = _post_raw_signed(client, body)

    assert response.status_code == 400
    with webhook_app.event_store.Session() as session:
        assert session.query(Transaction).count() == 0
        row = session.query(DeadLetter).filter_by(reason="invalid_json").one()
        assert set(row.payload) == {"raw_body_sha256"}


def test_unavailable_dead_letter_storage_requests_provider_retry(signed_webhook, monkeypatch):
    client, webhook_app = signed_webhook
    payload = _payload("DLQ-UNAVAILABLE")
    payload["TransAmount"] = "invalid"
    monkeypatch.setattr(webhook_app.event_store, "write_dead_letter", lambda *args, **kwargs: False)

    response = _post_signed(client, payload)

    assert response.status_code == 503


def test_body_limit_check_preserves_chunked_request_body():
    from flask import Request
    from security_helpers import is_payload_within_limit

    body = b'{"TransID":"chunked"}'
    request = Request.from_values(data=body, content_type="application/json")
    request.environ.pop("CONTENT_LENGTH", None)
    request.environ["wsgi.input_terminated"] = True

    assert is_payload_within_limit(request, max_body_bytes=1024)
    assert request.get_data(cache=True) == body


def test_validation_webhook_also_requires_signature(signed_webhook):
    client, _ = signed_webhook
    payload = _payload("VALIDATE-1")

    unsigned = client.post(
        "/webhook/mpesa/validation",
        json=payload,
        headers={"X-Daraja-Shared-Secret": "source-secret"},
    )
    signed = _post_signed(client, payload, path="/webhook/mpesa/validation")

    assert unsigned.status_code == 403
    assert signed.status_code == 200


def test_transaction_raw_source_data_cannot_be_mutated(signed_webhook):
    from models import Transaction
    from sqlalchemy.exc import StatementError

    client, webhook_app = signed_webhook
    assert _post_signed(client, _payload("IMMUTABLE-1")).status_code == 200

    with webhook_app.event_store.Session() as session:
        transaction = session.query(Transaction).filter_by(trans_id="IMMUTABLE-1").one()
        transaction.raw_payload = {"TransID": "rewritten"}
        with pytest.raises((ValueError, StatementError)):
            session.commit()


def test_stk_callback_requires_account_mapping_and_ingests_canonical_fields(
    signed_webhook, monkeypatch
):
    from ingestion import IngestionError

    _, webhook_app = signed_webhook
    payload = {
        "schema_version": "1.0",
        "Body": {
            "stkCallback": {
                "ResultCode": 0,
                "CallbackMetadata": {
                    "Item": [
                        {"Name": "Amount", "Value": 37.5},
                        {"Name": "MpesaReceiptNumber", "Value": "STK123"},
                        {"Name": "TransactionDate", "Value": 20261013120000},
                        {"Name": "PhoneNumber", "Value": 254700000000},
                    ]
                },
            }
        },
    }

    with pytest.raises(IngestionError, match="provider account mapping"):
        webhook_app.ingestion_service.ingest("mpesa", payload, tenant_id="tenant-a")

    monkeypatch.setenv("DARAJA_BUSINESS_SHORT_CODE", "654321")
    result = webhook_app.ingestion_service.ingest("mpesa", payload, tenant_id="tenant-a")
    assert result.envelope.canonical.provider_account_id == "654321"
    assert result.envelope.canonical.provider_transaction_id == "STK123"
    assert result.envelope.canonical.amount == "37.50"
    assert result.result.value == "stored"


def test_signed_callback_is_acknowledged_only_after_data_quality_quarantine(signed_webhook):
    client, webhook_app = signed_webhook
    payload = _payload("DQ-INVALID")
    payload["BillRefNumber"] = ""

    response = _post_signed(client, payload)
    retry = _post_signed(client, payload)

    assert response.status_code == 200
    assert response.get_json() == {
        "ResultCode": 0,
        "ResultDesc": "Accepted for data-quality review",
    }
    assert retry.status_code == 200
    with webhook_app.event_store.Session() as session:
        from models import ProcessedTransaction, QuarantineRecord, Transaction

        assert session.query(QuarantineRecord).count() == 1
        assert session.query(Transaction).count() == 0
        assert session.query(ProcessedTransaction).count() == 0
