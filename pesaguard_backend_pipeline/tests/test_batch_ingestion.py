import csv
import io
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from batch_ingestion import BatchImportError, BatchImportService, ObjectStorage
from event_store import ProcessResult
from models import Base, ImportJob


class FakeIngestionService:
    adapters = {"bank": object(), "csv": object()}

    def ingest(self, provider, payload, *, tenant_id):
        if payload.get("transaction_id") == "bad":
            raise ValueError("invalid transaction")
        return type("Result", (), {"result": ProcessResult.STORED})()


def _csv_content():
    output = io.StringIO()
    writer = csv.DictWriter(output, fieldnames=["transaction_id", "amount", "account_id"])
    writer.writeheader()
    writer.writerow({"transaction_id": "bank-1", "amount": "10.00", "account_id": "acct-1"})
    writer.writerow({"transaction_id": "bad", "amount": "1.00", "account_id": "acct-1"})
    return output.getvalue().encode()


def test_submit_and_process_tracks_batch_counters(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'imports.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    service = BatchImportService(FakeIngestionService(), ObjectStorage(str(tmp_path / "objects")))

    with Session() as session:
        job = service.submit(
            session,
            tenant_id="tenant-a",
            source="bank",
            filename="statement.csv",
            content=_csv_content(),
        )
        assert job.status == "queued"
        processed = service.process(session, job.id, tenant_id="tenant-a")
        assert processed.status == "failed"
        assert processed.records_received == 2
        assert processed.records_valid == 1
        assert processed.records_failed == 1
        assert processed.completed_at is not None
        assert processed.error_summary[0]["record"] == 2


def test_submit_rejects_unsupported_formats_and_sources(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'imports.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    service = BatchImportService(FakeIngestionService(), ObjectStorage(str(tmp_path / "objects")))
    with Session() as session:
        try:
            service.submit(session, tenant_id="tenant-a", source="bank", filename="statement.exe", content=b"x")
        except BatchImportError as exc:
            assert "format" in str(exc)
        else:
            raise AssertionError("unsupported formats must be rejected")
        try:
            service.submit(session, tenant_id="tenant-a", source="unknown", filename="statement.csv", content=b"a\n")
        except BatchImportError as exc:
            assert "source" in str(exc)
        else:
            raise AssertionError("unsupported sources must be rejected")


def test_failed_import_retry_restarts_counters_without_double_counting(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'retry-imports.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    service = BatchImportService(FakeIngestionService(), ObjectStorage(str(tmp_path / "retry-objects")))

    with Session() as session:
        job = service.submit(
            session,
            tenant_id="tenant-a",
            source="bank",
            filename="statement.csv",
            content=_csv_content(),
        )
        first = service.process(session, job.id, tenant_id="tenant-a")
        first_attempts = first.attempts
        second = service.process(session, job.id, tenant_id="tenant-a")

        assert first_attempts == 1
        assert second.attempts == 2
        assert second.records_received == 2
        assert second.records_valid == 1
        assert second.records_failed == 1


def test_live_import_lease_prevents_a_second_worker_from_processing(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'leased-imports.db'}")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine, expire_on_commit=False)
    service = BatchImportService(FakeIngestionService(), ObjectStorage(str(tmp_path / "leased-objects")))

    with Session() as session:
        job = ImportJob(
            id="imp_live_lease",
            tenant_id="tenant-a",
            source="bank",
            filename="statement.csv",
            file_format="csv",
            object_key="unused.csv",
            status="running",
            attempts=1,
            lease_expires_at=datetime.now(timezone.utc) + timedelta(minutes=5),
        )
        session.add(job)
        session.commit()

        with pytest.raises(BatchImportError, match="already running"):
            service.process(session, job.id, tenant_id="tenant-a")


def test_object_storage_rejects_paths_outside_its_root(tmp_path):
    storage = ObjectStorage(str(tmp_path / "objects"))

    with pytest.raises(BatchImportError, match="object"):
        storage.get("..\\outside.csv")