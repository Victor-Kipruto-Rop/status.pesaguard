from datetime import datetime, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from data_quality import (
    DataQualityStatus,
    dq_batch_report,
    dq_report,
    run_data_quality,
    run_data_quality_batch,
)
from data_protection import unprotect_payload
from dq_rules import (
    _parse_timestamp,
    _rule_amount_positive,
    _rule_currency_valid,
    _rule_provider_valid,
    _rule_reference_valid,
    _rule_schema_version_present,
    _rule_tenant_exists,
    _rule_transaction_id_not_null,
)
from event_store import EventStore, ProcessResult
from ingestion import IngestionService
from models import (
    Base,
    ProcessedTransaction,
    QuarantineRecord,
    Transaction,
    TransactionProvenance,
)
from provenance import (
    DataProvenance,
    list_quarantine_remediations,
    record_quarantine_remediation,
)


NOW = datetime(2026, 10, 13, 12, 0, tzinfo=timezone.utc)


def _payload(**overrides):
    payload = {
        "TransID": "dq-1",
        "TenantID": "tenant-a",
        "Provider": "mpesa",
        "TransAmount": "100.00",
        "Currency": "KES",
        "BillRefNumber": "member-1",
        "TransTime": "2026-10-13T11:00:00+00:00",
        "MSISDN": "254700000000",
        "schema_version": "1.0",
    }
    payload.update(overrides)
    return payload


def test_individual_rules_cover_required_and_invalid_values():
    for rule in (
        _rule_transaction_id_not_null,
        _rule_provider_valid,
        _rule_tenant_exists,
        _rule_schema_version_present,
    ):
        passed, failure = rule({}, None)
        assert not passed
        assert failure is not None

    passed, failure = _rule_amount_positive({"TransAmount": "NaN"}, None)
    assert not passed
    assert failure is not None
    assert _rule_currency_valid({"Currency": "KES"}, None) == (True, None)
    assert not _rule_currency_valid({"Currency": "BAD"}, None)[0]
    assert _rule_reference_valid({}, None) == (True, None)
    assert not _rule_reference_valid({"BillRefNumber": ""}, None)[0]
    assert _parse_timestamp("2026-10-13 12:00:00") == NOW
    assert _parse_timestamp("not-a-time") is None


def test_report_documents_weighted_checks_and_equal_dimension_score_formula():
    result = run_data_quality(_payload(TransAmount="invalid"), now=NOW)
    report = dq_report(result)

    assert result.status == DataQualityStatus.FAIL
    assert report["scoring"]["version"] == "equal_dimension_rule_mean_v1"
    assert report["scoring"]["overall_score"] == result.overall_score
    assert report["dimension_scores"]["validity"] < 1.0
    assert report["overall_score"] == round(
        sum(report["dimension_scores"].values()) / 6, 4
    )
    assert report["checks"]
    assert report["profile"] == result.profile
    assert "freshness" in report["profile"]


def test_freshness_is_measured_and_stale_event_is_nonblocking_by_default():
    stale_payload = _payload(TransTime="2026-10-10T12:00:00+00:00")
    result = run_data_quality(
        stale_payload,
        now=NOW,
        freshness_max_age_seconds=3600,
    )
    freshness = result.profile["freshness"]

    assert result.status == DataQualityStatus.PASS
    assert freshness["age_seconds"] == 3 * 24 * 60 * 60
    assert freshness["status"] == "stale"
    assert any(warning["rule"] == "event_freshness" for warning in dq_report(result)["warnings"])


def test_malformed_or_future_timestamps_fail_quality_gate():
    for timestamp in ("not-a-time", "2026-10-14T00:00:00+00:00"):
        result = run_data_quality(_payload(TransTime=timestamp), now=NOW)
        assert result.status == DataQualityStatus.FAIL
        assert any(failure.rule.code == "transaction_time_valid" for failure in result.failures)


def test_schema_contract_versions_are_enforced_and_migration_versions_warn():
    incompatible = run_data_quality(
        _payload(schema_version="9.9"),
        now=NOW,
        expected_schema_version="1.0",
    )
    migration_required = run_data_quality(
        _payload(schema_version="1.1"),
        now=NOW,
        expected_schema_version="1.0",
    )

    assert incompatible.status == DataQualityStatus.FAIL
    assert any(
        failure.rule.code == "schema_contract_compatible"
        for failure in incompatible.failures
    )
    assert migration_required.status == DataQualityStatus.PASS
    assert any(
        warning.rule.code == "schema_contract_compatible"
        for warning in migration_required.warnings
    )


def test_conflicting_amount_aliases_fail_accuracy_check():
    result = run_data_quality(
        _payload(amount="101.00"),
        now=NOW,
    )

    assert result.status == DataQualityStatus.FAIL
    assert any(
        failure.rule.code == "amount_alias_consistency"
        for failure in result.failures
    )


def test_robust_mad_outlier_is_reported_without_quarantine():
    peers = [
        _payload(TransID=f"peer-{index}", TransAmount="100.00")
        for index in range(20)
    ]
    result = run_data_quality(
        _payload(TransAmount="1000.00"),
        now=NOW,
        reference_records=peers,
    )

    assert result.status == DataQualityStatus.PASS
    assert any(warning["rule"] == "amount_outlier_mad" for warning in dq_report(result)["warnings"])
    assert result.dimension_scores["accuracy"] < 1.0


def test_outlier_baseline_excludes_other_tenants_and_currencies():
    peers = [
        _payload(
            TransID=f"peer-{index}",
            TenantID="tenant-b",
            TransAmount="100.00",
        )
        for index in range(25)
    ]
    peers += [
        _payload(
            TransID=f"usd-{index}",
            TransAmount="100.00",
            Currency="USD",
        )
        for index in range(25)
    ]
    result = run_data_quality(
        _payload(TransAmount="1000.00"),
        now=NOW,
        reference_records=peers,
    )

    assert not any(warning["rule"] == "amount_outlier_mad" for warning in result.warnings)


def test_batch_detects_duplicates_and_conflicting_reuse_without_cross_tenant_leakage():
    records = [
        _payload(),
        _payload(),
        _payload(TransAmount="200.00"),
        _payload(TenantID="tenant-b"),
    ]

    result = run_data_quality_batch(records, now=NOW)
    report = dq_batch_report(result)

    assert result.duplicate_count == 2
    assert result.results[1].status == DataQualityStatus.FAIL
    assert any(
        warning["rule"] == "duplicate_transaction"
        for warning in dq_report(result.results[1])["warnings"]
    )
    assert result.results[2].status == DataQualityStatus.FAIL
    assert result.results[0].status == DataQualityStatus.FAIL
    assert result.results[1].status == DataQualityStatus.FAIL
    assert any(
        failure.rule.code == "duplicate_transaction_conflict"
        for failure in result.results[2].failures
    )
    assert result.results[3].status == DataQualityStatus.PASS
    assert report["record_count"] == 4
    assert report["duplicate_count"] == 2


def test_batch_rejects_unbounded_input():
    with pytest.raises(ValueError, match="maximum batch size"):
        run_data_quality_batch([_payload()] * 10_001, now=NOW)


def test_batch_detects_existing_duplicate_records_within_tenant_scope():
    result = run_data_quality_batch(
        [_payload()],
        existing_records=[_payload(TransID="other-tenant", TenantID="tenant-b")],
        now=NOW,
    )
    assert result.duplicate_count == 0

    result = run_data_quality_batch(
        [_payload()],
        existing_records=[_payload()],
        now=NOW,
    )
    assert result.duplicate_count == 1
    assert any(
        warning.rule.code == "duplicate_transaction"
        for warning in result.results[0].warnings
    )


def test_quarantine_remediation_is_append_only_stateful_and_tenant_scoped(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'quality-remediation.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)

    with session_factory() as session:
        result = DataProvenance().process(_payload(TransAmount="-1"), session)
        quarantine = session.query(QuarantineRecord).one()
        assert quarantine.payload["MSISDN"] != "254700000000"
        assert unprotect_payload(quarantine.payload)["MSISDN"] == "254700000000"
        first = record_quarantine_remediation(
            session,
            tenant_id="tenant-a",
            quarantine_id=quarantine.id,
            actor_id="operator-1",
            action="review_started",
        )
        second = record_quarantine_remediation(
            session,
            tenant_id="tenant-a",
            quarantine_id=quarantine.id,
            actor_id="operator-1",
            action="correction_recorded",
            corrected_payload={"TransAmount": "1.00"},
        )
        session.commit()

        history = list_quarantine_remediations(
            session, tenant_id="tenant-a", quarantine_id=quarantine.id
        )
        assert result["decision"] == "quarantined"
        assert [entry.id for entry in history] == [first.id, second.id]
        assert [(entry.from_status, entry.to_status) for entry in history] == [
            ("open", "reviewing"),
            ("reviewing", "corrected"),
        ]
        assert second.evidence_sha256
        with pytest.raises(LookupError):
            list_quarantine_remediations(
                session, tenant_id="tenant-b", quarantine_id=quarantine.id
            )
        with pytest.raises(ValueError, match="invalid remediation transition"):
            record_quarantine_remediation(
                session,
                tenant_id="tenant-a",
                quarantine_id=quarantine.id,
                actor_id="operator-1",
                action="review_started",
            )


def test_remediation_rejects_unknown_tenant_and_terminal_state(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'quality-remediation-terminal.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)

    with session_factory() as session:
        DataProvenance().process(_payload(TransAmount="-1"), session)
        quarantine = session.query(QuarantineRecord).one()
        with pytest.raises(LookupError):
            record_quarantine_remediation(
                session,
                tenant_id="tenant-b",
                quarantine_id=quarantine.id,
                actor_id="operator-1",
                action="review_started",
            )
        record_quarantine_remediation(
            session,
            tenant_id="tenant-a",
            quarantine_id=quarantine.id,
            actor_id="operator-1",
            action="resolved",
        )
        with pytest.raises(ValueError, match="terminal"):
            record_quarantine_remediation(
                session,
                tenant_id="tenant-a",
                quarantine_id=quarantine.id,
                actor_id="operator-1",
                action="review_started",
            )


def test_remediation_history_rows_cannot_be_mutated_or_deleted(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'quality-remediation-immutable.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)

    with session_factory() as session:
        DataProvenance().process(_payload(TransAmount="-1"), session)
        quarantine = session.query(QuarantineRecord).one()
        entry = record_quarantine_remediation(
            session,
            tenant_id="tenant-a",
            quarantine_id=quarantine.id,
            actor_id="operator-1",
            action="review_started",
        )
        session.commit()
        entry.action = "resolved"
        with pytest.raises(ValueError, match="append-only"):
            session.flush()
        session.rollback()
        with pytest.raises(ValueError, match="append-only"):
            session.delete(entry)
            session.flush()


def test_mpesa_ingestion_persists_quality_provenance_in_same_transaction(tmp_path):
    store = EventStore(database_url=f"sqlite:///{tmp_path / 'quality-ingestion.db'}")
    result = IngestionService(store).ingest(
        "mpesa",
        {
            "TransactionType": "Pay Bill",
            "TransID": "quality-valid",
            "TransTime": "20261013120000",
            "TransAmount": "25.00",
            "BusinessShortCode": "123456",
            "MSISDN": "254700000000",
        },
        tenant_id="tenant-a",
    )

    assert result.result is ProcessResult.STORED
    with store.Session() as session:
        assert session.query(Transaction).count() == 1
        assert session.query(ProcessedTransaction).count() == 1
        provenance = session.query(TransactionProvenance).one()
        assert provenance.tenant_id == "tenant-a"
        assert provenance.dq_status == DataQualityStatus.PASS
        assert provenance.dq_timeliness == 1.0
        assert provenance.dq_overall_score == 1.0
        assert provenance.dq_checks


def test_mpesa_quality_failures_are_durably_quarantined_and_idempotent(tmp_path):
    store = EventStore(database_url=f"sqlite:///{tmp_path / 'quality-quarantine.db'}")
    service = IngestionService(store)
    payload = {
        "TransactionType": "Pay Bill",
        "TransID": "quality-invalid-reference",
        "TransTime": "20261013120000",
        "TransAmount": "25.00",
        "BusinessShortCode": "123456",
        "MSISDN": "254700000000",
        "BillRefNumber": "",
    }

    first = service.ingest("mpesa", payload, tenant_id="tenant-a")
    retry = service.ingest("mpesa", payload, tenant_id="tenant-a")

    assert first.result is ProcessResult.QUARANTINED
    assert retry.result is ProcessResult.QUARANTINED
    with store.Session() as session:
        assert session.query(QuarantineRecord).count() == 1
        assert session.query(Transaction).count() == 0
        assert session.query(ProcessedTransaction).count() == 0
