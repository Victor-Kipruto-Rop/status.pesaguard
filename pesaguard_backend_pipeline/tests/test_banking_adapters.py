"""Tests for banking partner adapters: Equity, KCB, Absa."""

from connectors.registry import get_connector
from event_store import ProcessResult
from ingestion import (
    AbsaAdapter,
    EquityAdapter,
    IngestionService,
    KcbAdapter,
)


class FakeEventStore:
    def __init__(self):
        self.calls = []
        self.seen = set()

    def mark_processed(
        self,
        payload,
        *,
        tenant_id,
        idempotency_key_override,
        source_ip=None,
        signature_verified=False,
    ):
        self.calls.append((payload, tenant_id, idempotency_key_override))
        if idempotency_key_override in self.seen:
            return ProcessResult.DUPLICATE
        self.seen.add(idempotency_key_override)
        return ProcessResult.STORED


def equity_payload():
    return {
        "transaction_id": "EQ-2026-001",
        "amount": "1250.00",
        "account_id": "equity-acct-1",
        "account_number": "0789012345",
        "sort_code": "680123",
        "branch_code": "EQU-NAI-01",
        "beneficiary_name": "John Doe",
        "swift_code": "EQBLKENA",
        "clearing_code": "EQ",
        "narration": "Salary payment",
        "balance_after": "45000.00",
        "currency": "KES",
        "timestamp": "2026-09-23T10:00:00Z",
        "status": "COMPLETED",
    }


def kcb_payload():
    return {
        "transaction_id": "KCB-2026-002",
        "amount": "750.50",
        "account_id": "kcb-acct-1",
        "merchant_id": "KCBMERCH01",
        "terminal_id": "TERM-042",
        "branch_code": "KCB-WEST-02",
        "narration": "Merchant settlement",
        "currency": "KES",
        "timestamp": "2026-09-23T11:00:00Z",
        "status": "COMPLETED",
    }


def absa_payload():
    return {
        "transaction_id": "ABSA-2026-003",
        "amount": "3200.00",
        "account_id": "absa-acct-1",
        "account_number": "1234567890",
        "sort_code": "030102",
        "beneficiary_name": "Jane Smith",
        "cheque_number": "CHQ-001234",
        "currency": "KES",
        "timestamp": "2026-09-23T12:00:00Z",
        "status": "COMPLETED",
    }


def test_equity_adapter_maps_bank_fields_into_metadata():
    service = IngestionService(FakeEventStore())
    result = service.ingest("equity", equity_payload(), tenant_id="tenant-a")
    env = result.envelope
    assert env.provider == "equity"
    assert env.tenant_id == "tenant-a"
    canon = env.canonical
    assert canon.provider_transaction_id == "EQ-2026-001"
    assert canon.amount == "1250.00"
    assert canon.currency == "KES"
    assert canon.status == "COMPLETED"
    assert canon.transaction_time == "2026-09-23T10:00:00Z"
    md = canon.metadata
    assert md["account_number"] == "0789012345"
    assert md["sort_code"] == "680123"
    assert md["branch_code"] == "EQU-NAI-01"
    assert md["beneficiary_name"] == "John Doe"
    assert md["swift_code"] == "EQBLKENA"
    assert md["clearing_code"] == "EQ"
    assert md["narration"] == "Salary payment"
    assert md["balance_after"] == "45000.00"
    # merchant_id falls back to branch_code for equity
    assert canon.merchant_id == "EQU-NAI-01"


def test_kcb_adapter_maps_bank_fields_into_metadata():
    service = IngestionService(FakeEventStore())
    result = service.ingest("kcb", kcb_payload(), tenant_id="tenant-a")
    env = result.envelope
    assert env.provider == "kcb"
    canon = env.canonical
    assert canon.provider_transaction_id == "KCB-2026-002"
    assert canon.amount == "750.50"
    md = canon.metadata
    assert md["branch_code"] == "KCB-WEST-02"
    assert md["narration"] == "Merchant settlement"
    # merchant_id comes from merchant_id field
    assert canon.merchant_id == "KCBMERCH01"


def test_absa_adapter_maps_bank_fields_into_metadata():
    service = IngestionService(FakeEventStore())
    result = service.ingest("absa", absa_payload(), tenant_id="tenant-a")
    env = result.envelope
    assert env.provider == "absa"
    canon = env.canonical
    assert canon.provider_transaction_id == "ABSA-2026-003"
    assert canon.amount == "3200.00"
    md = canon.metadata
    assert md["account_number"] == "1234567890"
    assert md["sort_code"] == "030102"
    assert md["beneficiary_name"] == "Jane Smith"
    assert md["cheque_number"] == "CHQ-001234"
    # merchant_id falls back to branch_code which is absent, so empty
    assert canon.merchant_id == ""


def test_banking_adapters_share_envelope_contract():
    service = IngestionService(FakeEventStore())
    for provider, payload in (
        ("equity", equity_payload()),
        ("kcb", kcb_payload()),
        ("absa", absa_payload()),
    ):
        result = service.ingest(provider, payload, tenant_id="tenant-b")
        assert result.envelope.provider == provider
        assert result.envelope.tenant_id == "tenant-b"
        assert result.envelope.external_reference
        assert result.envelope.schema_version == 1


def test_banking_adapter_idempotency():
    store = FakeEventStore()
    service = IngestionService(store)
    first = service.ingest("equity", equity_payload(), tenant_id="tenant-a")
    second = service.ingest("equity", equity_payload(), tenant_id="tenant-a")
    assert first.result is ProcessResult.STORED
    assert second.result is ProcessResult.DUPLICATE


def test_banking_connector_registry():
    service = IngestionService(FakeEventStore())
    for source in ("equity", "kcb", "absa"):
        connector = get_connector(source, service)
        assert connector.source == source
        results = connector.ingest(
            {"equity": equity_payload(), "kcb": kcb_payload(), "absa": absa_payload()}[source],
            tenant_id="tenant-a",
        )
        assert len(results) == 1
        assert results[0].result is ProcessResult.STORED