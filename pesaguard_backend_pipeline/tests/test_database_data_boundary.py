"""Database/data infrastructure boundary tests (139-148), focused on service 145.

Services 139-148 already carry broad coverage from earlier suites:
`test_database_data_services.py` (DatabaseService sessions/transactions/pool,
Redis URL validation and client ownership, migration upgrade/revision
validation, archival path-traversal/tamper/idempotency),
`test_postgres_concurrency.py`, `test_partitioning.py`,
`test_schema_compatibility.py` + `test_schema_registry.py` +
`test_schema_validation.py` + `test_api_schema_validation.py`,
`test_retention_cleanup.py` + `test_retention_policy.py`,
`test_archival.py`, `test_backup_integrity.py`, `test_disaster_recovery.py`,
`test_phase8_backup_dr.py`, `test_phase4_event_bus.py` (producer/consumer),
and `test_redis_cache.py`.

This suite closes the remaining verified gaps:

  - Service 145 (Schema Evolution): `schema_evolution.py` had **zero** direct
    test coverage. The suite pins `assess_schema_compatibility` (compatible /
    migration_required / incompatible), `schema_version_from_payload` (all key
    variants + missing), `pipeline_version_from_context`, and `version_pair`.
  - Service 145 integration: regression for a real defect found and fixed in
    this pass (human-approved under Rule #61). `DataProvenance.process` called
    `assess_schema_compatibility(raw, self.schema_version)` -- passing the
    payload dict where a version string was expected (AttributeError crash) and
    comparing the returned ``(compatibility, note)`` tuple to the string
    "incompatible" so the quarantine branch could never fire. With the fixed
    call order and tuple unpacking, a data-quality-valid payload carrying an
    unknown schema version is quarantined with reason `schema_incompatible`
    instead of crashing, while `migration_required` versions proceed normally.
  - Service 140 (Migration): `__init__` rejects empty URL and missing config
    file; `downgrade("")` rejects.
  - Service 142 (Redis): `ping` on a non-success reply raises `ConnectionError`.

All behaviours below were verified against the running implementation first
(Rule #62). Production code changed only for the integer-schema defect above.
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, QuarantineRecord, TransactionProvenance
from provenance import DataProvenance
from schema_evolution import (
    assess_schema_compatibility,
    pipeline_version_from_context,
    schema_version_from_payload,
    version_pair,
)


def _valid_payload(**overrides):
    payload = {
        "TransID": "dq-1",
        "TenantID": "tenant-a",
        "Provider": "mpesa",
        "TransAmount": "100.00",
        "Currency": "KES",
        "BillRefNumber": "member-1",
        "schema_version": "1.0",
    }
    payload.update(overrides)
    return payload


def _isolated_session(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'provenance-schema.db'}")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)


# --- Service 145: Schema Evolution (unit contract) ------------------------------


def test_schema_evolution_same_version_is_compatible():
    compatibility, note = assess_schema_compatibility("1.0", "1.0")
    assert compatibility == "compatible"
    assert note


def test_schema_evolution_minor_version_requires_migration():
    compatibility, _ = assess_schema_compatibility("1.0", "1.1")
    assert compatibility == "migration_required"


def test_schema_evolution_unknown_version_is_incompatible():
    compatibility, note = assess_schema_compatibility("1.0", "9.9")
    assert compatibility == "incompatible"
    assert note


def test_schema_evolution_never_silently_accepts_unknown():
    # An unknown incoming version must never resolve to "compatible".
    for incoming in ("2.0", "0.9", "v1", "", "99.99"):
        assert assess_schema_compatibility("1.0", incoming)[0] in {
            "migration_required",
            "incompatible",
        }


def test_schema_version_extraction_checks_all_declared_keys():
    assert schema_version_from_payload({"SchemaVersion": "1.1"}) == "1.1"
    assert schema_version_from_payload({"schema_version": "2.0"}) == "2.0"
    assert schema_version_from_payload({"event_version": "1"}) == "1"
    assert schema_version_from_payload({"TransID": "x"}) is None


def test_schema_version_extraction_coerces_numeric_versions():
    assert schema_version_from_payload({"schema_version": 2}) == "2"
    assert schema_version_from_payload({"schema_version": "   "}) is None


def test_pipeline_version_extraction_and_pair():
    assert pipeline_version_from_context({"pipeline_version": "3.0"}) == "3.0"
    assert pipeline_version_from_context({}) is None
    pair = version_pair("1.1", "2.0")
    assert pair["schema_version"] == "1.1" and pair["pipeline_version"] == "2.0"


# --- Service 145 integration: schema-incompatible quarantine (regression) --------

# This is the user-approved defect fix from the design review (Rule #61):
# DataProvenance.process previously crashed (AttributeError) on any
# data-quality-valid payload, and its tuple-vs-string comparison meant the
# schema quarantine branch could never fire.


def test_incompatible_schema_quarantines_instead_of_crashing(tmp_path):
    payload = _valid_payload(schema_version="9.9")  # passes DQ, unknown version
    with _isolated_session(tmp_path)() as session:
        decision = DataProvenance(schema_version="1.0").process(payload, session)
        session.commit()
        assert decision["decision"] == "quarantined"
        quarantine = session.query(QuarantineRecord).all()
        assert len(quarantine) == 1
        assert quarantine[0].reason.startswith("schema_incompatible")


def test_minor_schema_version_is_not_quarantined_but_recorded(tmp_path):
    payload = _valid_payload(schema_version="1.1")  # migration_required, not incompatible
    with _isolated_session(tmp_path)() as session:
        decision = DataProvenance(schema_version="1.0").process(payload, session)
        session.commit()
        assert decision["decision"] == "accepted"
        assert session.query(QuarantineRecord).count() == 0
        # The anchor records the *processing* version, so the decision stays
        # traceable to the version that produced it (schema-evolution design).
        rows = session.query(TransactionProvenance).all()
        assert len(rows) == 1
        assert rows[0].schema_version == "1.0"


def test_missing_schema_version_fails_the_quality_gate_safely(tmp_path):
    # A missing version never reaches the compatibility check: the data-quality
    # gate quarantines it first with an explicit reason (fail-closed ordering).
    payload = _valid_payload()
    del payload["schema_version"]
    with _isolated_session(tmp_path)() as session:
        decision = DataProvenance(schema_version="1.0").process(payload, session)
        session.commit()
        assert decision["decision"] == "quarantined"
        quarantine = session.query(QuarantineRecord).all()
        assert len(quarantine) == 1
        assert "schema version is missing or empty" in quarantine[0].reason


# --- Service 140 (Migration) / Service 142 (Redis) validation edges -------------


def test_migration_service_requires_database_url_and_config(tmp_path):
    from migration_service import MigrationService

    with pytest.raises(ValueError, match="database_url is required"):
        MigrationService("   ")
    with pytest.raises(FileNotFoundError):
        MigrationService(
            "postgresql://user:password@localhost/db",
            config_path=tmp_path / "missing-alembic.ini",
        )


def test_migration_service_rejects_empty_downgrade_revision(tmp_path):
    from migration_service import MigrationService

    config_file = tmp_path / "alembic.ini"
    config_file.write_text("[alembic]\nscript_location = .\n", encoding="utf-8")
    service = MigrationService("sqlite:///:memory:", config_path=config_file)
    with pytest.raises(ValueError, match="revision"):
        service.downgrade("   ")


def test_redis_service_rejects_non_success_ping():
    from redis_service import RedisService

    class LyingRedis:
        def ping(self):
            return "ok-bytes-not-bool"

    service = RedisService("redis://localhost/0", client=LyingRedis())
    with pytest.raises(ConnectionError, match="did not return success"):
        service.ping()


def test_redis_service_requires_positive_timeouts():
    from redis_service import RedisService

    with pytest.raises(ValueError, match="[Tt]imeout"):
        RedisService("redis://localhost/0", socket_connect_timeout=0)
    with pytest.raises(ValueError, match="[Tt]imeout"):
        RedisService("redis://localhost/0", socket_timeout=-1)

