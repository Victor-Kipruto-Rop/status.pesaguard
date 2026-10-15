from datetime import datetime, timezone
from io import BytesIO
import json

import pytest

from archival import (
    ArchiveManager,
    ArchiveObjectStore,
    ArchivalPolicyError,
    RetentionPolicy,
    S3ArchiveObjectStore,
)


class _S3Error(Exception):
    def __init__(self, code, status):
        self.response = {
            "Error": {"Code": code},
            "ResponseMetadata": {"HTTPStatusCode": status},
        }


class _S3Client:
    def __init__(self):
        self.objects = {}
        self.put_requests = []

    def get_bucket_versioning(self, **_kwargs):
        return {"Status": "Enabled"}

    def get_object_lock_configuration(self, **_kwargs):
        return {
            "ObjectLockConfiguration": {
                "ObjectLockEnabled": "Enabled",
                "Rule": {"DefaultRetention": {"Mode": "COMPLIANCE", "Days": 2555}},
            }
        }

    def put_object(self, **kwargs):
        self.put_requests.append(kwargs)
        key = kwargs["Key"]
        if key in self.objects:
            raise _S3Error("PreconditionFailed", 412)
        self.objects[key] = kwargs["Body"]

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            raise _S3Error("NoSuchKey", 404)
        return {"Body": BytesIO(self.objects[Key])}


def test_archival_moves_records_to_policy_selected_warm_and_cold_tiers(tmp_path):
    manager = ArchiveManager(RetentionPolicy(hot_days=90, warm_days=730, cold_days=2555), ArchiveObjectStore(str(tmp_path)))
    transaction = {"tenant_id": "tenant-a", "transaction_id": "txn-1", "transaction_time": "2025-01-01T00:00:00Z", "amount": "5000.00"}
    now = datetime(2026, 9, 23, tzinfo=timezone.utc)
    manifest = manager.archive(transaction, now=now)
    assert manifest is not None
    assert manifest.tier == "warm"
    assert manager.store.get(manifest.object_key)


def test_archival_requires_business_approved_windows():
    with pytest.raises(ArchivalPolicyError):
        RetentionPolicy(hot_days=90, warm_days=60, cold_days=365)


def test_s3_archive_writes_encrypted_immutable_objects_and_manifest():
    client = _S3Client()
    store = S3ArchiveObjectStore(client, "approved-archive", "kms-key-test")
    manager = ArchiveManager(
        RetentionPolicy(hot_days=90, warm_days=730, cold_days=2555),
        store,
    )
    transaction = {
        "tenant_id": "tenant-a",
        "transaction_id": "txn-1",
        "transaction_time": "2025-01-01T00:00:00Z",
        "amount": "5000.00",
    }
    now = datetime(2026, 9, 23, tzinfo=timezone.utc)

    first = manager.archive(transaction, now=now)
    second = manager.archive(
        transaction,
        now=datetime(2026, 9, 24, tzinfo=timezone.utc),
    )

    assert first is not None and second == first
    assert len(client.objects) == 2
    assert all(
        request["ServerSideEncryption"] == "aws:kms"
        and request["SSEKMSKeyId"] == "kms-key-test"
        and request["IfNoneMatch"] == "*"
        for request in client.put_requests
    )
    persisted_manifest = json.loads(
        client.objects[f"{first.object_key}.manifest.json"]
    )
    assert persisted_manifest == first.__dict__
    assert manager.read_verified(first) == next(
        value
        for key, value in client.objects.items()
        if key == first.object_key
    )


def test_s3_archive_rejects_bucket_without_compliance_object_lock():
    client = _S3Client()
    client.get_object_lock_configuration = lambda **_kwargs: {
        "ObjectLockConfiguration": {
            "ObjectLockEnabled": "Enabled",
            "Rule": {"DefaultRetention": {"Mode": "GOVERNANCE", "Days": 30}},
        }
    }

    with pytest.raises(ArchivalPolicyError, match="object lock"):
        S3ArchiveObjectStore(client, "archive-bucket", "kms-key-test")


def test_s3_archive_rejects_conflicting_immutable_key():
    client = _S3Client()
    store = S3ArchiveObjectStore(client, "archive-bucket", "kms-key-test")
    store.put("tenant=tenant-a/transaction.json", b"original")

    with pytest.raises(ArchivalPolicyError, match="different immutable content"):
        store.put("tenant=tenant-a/transaction.json", b"replacement")


def test_s3_archive_factory_requires_explicit_provider_region_and_kms(
    monkeypatch,
):
    from archival import ArchiveManager

    for key in (
        "PESAGUARD_ARCHIVE_BUCKET",
        "PESAGUARD_ARCHIVE_REGION",
        "PESAGUARD_ARCHIVE_KMS_KEY_ID",
        "PESAGUARD_ARCHIVE_HOT_DAYS",
        "PESAGUARD_ARCHIVE_WARM_DAYS",
        "PESAGUARD_ARCHIVE_COLD_DAYS",
    ):
        monkeypatch.delenv(key, raising=False)

    with pytest.raises(ArchivalPolicyError, match="PESAGUARD_ARCHIVE_BUCKET"):
        ArchiveManager.from_environment()