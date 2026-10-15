from event_bus import build_event
from realtime_pipeline import RealtimeTransactionPipeline


def _received():
    return build_event(
        "transaction.received",
        "tenant-a",
        "tx-1",
        {
            "TransID": "tx-1",
            "TransAmount": "10",
            "Currency": "kes",
            "provider": "mpesa",
            "provider_account_id": "123456",
        },
        source="mpesa",
        source_event_id="provider-event-1",
    )


def test_realtime_pipeline_preserves_lineage_across_stages():
    published = []
    pipeline = RealtimeTransactionPipeline(publisher=published.append)
    validated = pipeline.validate(_received())
    normalized = pipeline.normalize(validated)
    enriched = pipeline.enrich(normalized)
    processed = pipeline.process(enriched)

    assert [event.event_type for event in published] == [
        "transaction.validated",
        "transaction.normalized",
        "transaction.enriched",
        "transaction.processed",
    ]
    assert normalized.payload["TransAmount"] == "10.00"
    assert normalized.payload["Currency"] == "KES"
    assert enriched.payload["enrichment"]["enriched_by"] == "pesaguard.realtime_pipeline"
    assert normalized.causation_id == validated.event_id
    assert processed.causation_id == enriched.event_id
    assert processed.correlation_id == validated.correlation_id
    assert processed.source == "mpesa"


def _validate(amount: str):
    from realtime_pipeline import PipelineContractError, validate_transaction_payload

    return validate_transaction_payload(
        {
            "TransID": "tx-1",
            "TransAmount": amount,
            "Currency": "KES",
            "provider": "mpesa",
            "provider_account_id": "123456",
        }
    )


def _normalize(amount: str):
    from realtime_pipeline import normalize_transaction_payload

    return normalize_transaction_payload(
        {
            "TransID": "tx-1",
            "TransAmount": amount,
            "Currency": "KES",
            "provider": "mpesa",
            "provider_account_id": "123456",
        }
    )


def test_realtime_pipeline_amount_invariants_match_canonical_normalizer():
    """The real-time path must reject the same unsafe amounts as the canonical
    service-26 normalizer instead of crashing or silently truncating them.

    Regression for: `Infinity`/oversized values passed validation then crashed
    normalization with an uncaught `InvalidOperation`, and sub-minor-unit
    fractions (`12.345`) were silently truncated to `12.34` -- a silent
    financial-data change (Rules #7/#32/#33/#57).
    """
    from realtime_pipeline import PipelineContractError
    import pytest

    # Happy paths stay byte-identical to the canonical currency scale.
    assert _normalize("10")["TransAmount"] == "10.00"
    assert _normalize("25.50")["TransAmount"] == "25.50"
    assert _normalize("1,234.56")["TransAmount"] == "1234.56"

    rejected = {
        "Infinity": "amount must be finite",
        "-Infinity": "amount must be finite",
        "NaN": "amount must be finite",
        "1e30": "amount exceeds the supported transaction precision",
        "-5": "amount must be greater than zero",
        "0": "amount must be greater than zero",
        "12.345": "amount precision exceeds the minor unit",
        "abc": "amount must be a valid decimal",
    }
    for amount, message in rejected.items():
        with pytest.raises(PipelineContractError) as excinfo:
            _normalize(amount)
        assert message in str(excinfo.value), f"unexpected message for {amount!r}: {excinfo.value}"
        # Validation must reject it up-front too, never defer to a later crash.
        with pytest.raises(PipelineContractError):
            _validate(amount)
