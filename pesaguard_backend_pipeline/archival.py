"""Policy-driven hot, warm, and cold transaction archival."""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any, Mapping, Optional


class ArchivalPolicyError(ValueError):
    """Raised when archival windows are missing or inconsistent."""


MAX_ARCHIVE_OBJECT_BYTES = 16 * 1024 * 1024


@dataclass(frozen=True)
class RetentionPolicy:
    """Business-approved windows, expressed in days from transaction time."""

    hot_days: int
    warm_days: int
    cold_days: int

    def __post_init__(self) -> None:
        if not 0 < self.hot_days < self.warm_days < self.cold_days:
            raise ArchivalPolicyError("retention windows must satisfy 0 < hot_days < warm_days < cold_days")


@dataclass(frozen=True)
class ArchiveManifest:
    tenant_id: str
    transaction_id: str
    tier: str
    object_key: str
    payload_hash: str
    archived_at: str


class ArchiveObjectStore:
    """Local object-store implementation with a replaceable S3-compatible boundary."""

    def __init__(self, root: Optional[str] = None):
        self.root = Path(root or "var/archive").resolve()

    def put(self, key: str, content: bytes) -> None:
        destination = self._resolve_key(key)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination = self._resolve_key(key)
        temporary_path = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="wb",
                dir=destination.parent,
                prefix="tmp-archive-",
                suffix=".tmp",
                delete=False,
            ) as output:
                temporary_path = Path(output.name)
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            try:
                os.link(temporary_path, destination)
            except FileExistsError:
                if destination.read_bytes() != content:
                    raise ArchivalPolicyError("archive key already contains different immutable content")
        finally:
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()

    def get(self, key: str) -> bytes:
        return self._resolve_key(key).read_bytes()

    def _resolve_key(self, key: str) -> Path:
        if not isinstance(key, str) or not key or "\\" in key or "\x00" in key:
            raise ArchivalPolicyError("archive object key must be a safe relative path")
        relative = PurePosixPath(key)
        if relative.is_absolute() or any(
            part in {"", ".", ".."}
            or part.startswith(".archive-")
            or not re.fullmatch(r"[A-Za-z0-9._=-]+", part)
            for part in relative.parts
        ):
            raise ArchivalPolicyError("archive object key must be a safe relative path")
        destination = self.root / Path(*relative.parts)
        if any(part.is_symlink() for part in (destination.parent, *destination.parents) if part != self.root):
            raise ArchivalPolicyError("archive object key traverses a symbolic link")
        destination = destination.resolve()
        try:
            destination.relative_to(self.root)
        except ValueError as exc:
            raise ArchivalPolicyError("archive object key escapes the configured root") from exc
        return destination


class S3ArchiveObjectStore:
    """Write-once encrypted S3 storage with bucket-level object-lock safeguards."""

    def __init__(self, client: Any, bucket: str, kms_key_id: str):
        if not isinstance(bucket, str) or not bucket.strip():
            raise ArchivalPolicyError("archive bucket is required")
        if not isinstance(kms_key_id, str) or not kms_key_id.strip():
            raise ArchivalPolicyError("an archive KMS key is required")
        self.client = client
        self.bucket = bucket.strip()
        self.kms_key_id = kms_key_id.strip()
        self._verify_bucket_controls()

    def _verify_bucket_controls(self) -> None:
        versioning = self.client.get_bucket_versioning(Bucket=self.bucket)
        if versioning.get("Status") != "Enabled":
            raise ArchivalPolicyError("archive bucket versioning must be enabled")
        lock = self.client.get_object_lock_configuration(Bucket=self.bucket).get(
            "ObjectLockConfiguration", {}
        )
        default_retention = (lock.get("Rule") or {}).get("DefaultRetention")
        if (
            lock.get("ObjectLockEnabled") != "Enabled"
            or not isinstance(default_retention, dict)
            or default_retention.get("Mode") != "COMPLIANCE"
            or not (default_retention.get("Days") or default_retention.get("Years"))
        ):
            raise ArchivalPolicyError(
                "archive bucket requires object lock and a configured default retention"
            )

    def put(self, key: str, content: bytes) -> None:
        _validate_object_key(key)
        if not isinstance(content, bytes):
            raise ArchivalPolicyError("archive object content must be bytes")
        if len(content) > MAX_ARCHIVE_OBJECT_BYTES:
            raise ArchivalPolicyError("archive object exceeds the configured size limit")
        try:
            self.client.put_object(
                Bucket=self.bucket,
                Key=key,
                Body=content,
                ContentType="application/json",
                IfNoneMatch="*",
                ServerSideEncryption="aws:kms",
                SSEKMSKeyId=self.kms_key_id,
            )
        except Exception as exc:
            error = getattr(exc, "response", {}).get("Error", {})
            status = getattr(exc, "response", {}).get("ResponseMetadata", {}).get(
                "HTTPStatusCode"
            )
            if error.get("Code") not in {"PreconditionFailed", "ConditionalRequestConflict"} and status not in {409, 412}:
                raise
            if self.get(key) != content:
                raise ArchivalPolicyError(
                    "archive key already contains different immutable content"
                ) from exc

    def get(self, key: str) -> bytes:
        _validate_object_key(key)
        try:
            response = self.client.get_object(Bucket=self.bucket, Key=key)
        except Exception as exc:
            error = getattr(exc, "response", {}).get("Error", {})
            status = getattr(exc, "response", {}).get("ResponseMetadata", {}).get(
                "HTTPStatusCode"
            )
            if error.get("Code") in {"NoSuchKey", "NotFound"} or status == 404:
                raise FileNotFoundError(key) from exc
            raise
        stream = response["Body"]
        content_length = response.get("ContentLength")
        if content_length is not None and content_length > MAX_ARCHIVE_OBJECT_BYTES:
            stream.close()
            raise ArchivalPolicyError("archive object exceeds the configured size limit")
        try:
            body = stream.read(MAX_ARCHIVE_OBJECT_BYTES + 1)
        finally:
            stream.close()
        if not isinstance(body, bytes):
            raise ArchivalPolicyError("archive object store returned non-byte content")
        if len(body) > MAX_ARCHIVE_OBJECT_BYTES:
            raise ArchivalPolicyError("archive object exceeds the configured size limit")
        return body


class ArchiveManager:
    def __init__(self, policy: RetentionPolicy, store: Optional[ArchiveObjectStore] = None):
        self.policy = policy
        self.store = store or ArchiveObjectStore()

    @classmethod
    def from_environment(cls) -> "ArchiveManager":
        """Build the production S3 manager from explicit deployment settings."""
        bucket = os.getenv("PESAGUARD_ARCHIVE_BUCKET", "").strip()
        region = os.getenv("PESAGUARD_ARCHIVE_REGION", "").strip()
        kms_key_id = os.getenv("PESAGUARD_ARCHIVE_KMS_KEY_ID", "").strip()
        if not bucket or not region or not kms_key_id:
            raise ArchivalPolicyError(
                "PESAGUARD_ARCHIVE_BUCKET, PESAGUARD_ARCHIVE_REGION, and "
                "PESAGUARD_ARCHIVE_KMS_KEY_ID are required"
            )
        try:
            hot_days = int(os.environ["PESAGUARD_ARCHIVE_HOT_DAYS"])
            warm_days = int(os.environ["PESAGUARD_ARCHIVE_WARM_DAYS"])
            cold_days = int(os.environ["PESAGUARD_ARCHIVE_COLD_DAYS"])
        except (KeyError, ValueError) as exc:
            raise ArchivalPolicyError(
                "PESAGUARD_ARCHIVE_HOT_DAYS, PESAGUARD_ARCHIVE_WARM_DAYS, and "
                "PESAGUARD_ARCHIVE_COLD_DAYS must be configured"
            ) from exc

        try:
            import boto3
            from botocore.config import Config
        except ImportError as exc:
            raise ArchivalPolicyError(
                "boto3 is required for the configured S3 archive store"
            ) from exc

        client = boto3.client(
            "s3",
            region_name=region,
            endpoint_url=os.getenv("PESAGUARD_ARCHIVE_ENDPOINT_URL") or None,
            config=Config(
                signature_version="s3v4",
                connect_timeout=5,
                read_timeout=30,
                retries={"max_attempts": 5, "mode": "standard"},
            ),
        )
        store = S3ArchiveObjectStore(client, bucket, kms_key_id)
        return cls(
            RetentionPolicy(hot_days=hot_days, warm_days=warm_days, cold_days=cold_days),
            store,
        )

    def tier_for(self, occurred_at: datetime, *, now: Optional[datetime] = None) -> str:
        current = _as_utc(now or datetime.now(timezone.utc))
        timestamp = _as_utc(occurred_at)
        age = current - timestamp
        if age < timedelta(days=self.policy.hot_days):
            return "hot"
        if age < timedelta(days=self.policy.warm_days):
            return "warm"
        if age < timedelta(days=self.policy.cold_days):
            return "cold"
        return "expired"

    def archive(self, transaction: Mapping[str, Any], *, now: Optional[datetime] = None) -> Optional[ArchiveManifest]:
        tenant_id = str(transaction.get("tenant_id") or "").strip()
        transaction_id = str(transaction.get("transaction_id") or transaction.get("TransID") or "").strip()
        if not tenant_id or not transaction_id:
            raise ArchivalPolicyError("tenant_id and transaction_id are required for archival")
        timestamp_value = transaction.get("transaction_time") or transaction.get("TransTime")
        if not isinstance(timestamp_value, str) or not timestamp_value.strip():
            raise ArchivalPolicyError("transaction_time is required for archival")
        try:
            occurred_at = datetime.fromisoformat(timestamp_value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ArchivalPolicyError("transaction_time must be an ISO-8601 timestamp") from exc
        tier = self.tier_for(occurred_at, now=now)
        if tier in {"hot", "expired"}:
            return None
        payload = json.dumps(dict(transaction), sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
        if len(payload) > MAX_ARCHIVE_OBJECT_BYTES:
            raise ArchivalPolicyError("archive object exceeds the configured size limit")
        payload_hash = hashlib.sha256(payload).hexdigest()
        timestamp = _as_utc(occurred_at)
        tenant_partition = hashlib.sha256(tenant_id.encode("utf-8")).hexdigest()[:32]
        transaction_partition = hashlib.sha256(transaction_id.encode("utf-8")).hexdigest()[:32]
        key = (
            f"tier={tier}/tenant={tenant_partition}/year={timestamp.year:04d}/"
            f"month={timestamp.month:02d}/day={timestamp.day:02d}/{transaction_partition}.json"
        )
        self.store.put(key, payload)
        manifest_key = f"{key}.manifest.json"
        try:
            persisted_manifest = json.loads(self.store.get(manifest_key))
        except FileNotFoundError:
            manifest = ArchiveManifest(
                tenant_id,
                transaction_id,
                tier,
                key,
                payload_hash,
                _as_utc(now or datetime.now(timezone.utc)).isoformat(),
            )
            manifest_content = json.dumps(
                manifest.__dict__,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
            self.store.put(manifest_key, manifest_content)
            return manifest

        if (
            persisted_manifest.get("tenant_id") != tenant_id
            or persisted_manifest.get("transaction_id") != transaction_id
            or persisted_manifest.get("tier") != tier
            or persisted_manifest.get("object_key") != key
            or persisted_manifest.get("payload_hash") != payload_hash
            or not isinstance(persisted_manifest.get("archived_at"), str)
        ):
            raise ArchivalPolicyError("persisted archive manifest does not match the transaction")
        return ArchiveManifest(
            tenant_id,
            transaction_id,
            tier,
            key,
            payload_hash,
            persisted_manifest["archived_at"],
        )

    def read_verified(self, manifest: ArchiveManifest) -> bytes:
        """Read an archived transaction only when its content hash matches its manifest."""
        manifest_content = self.store.get(f"{manifest.object_key}.manifest.json")
        try:
            persisted = json.loads(manifest_content)
        except (TypeError, json.JSONDecodeError) as exc:
            raise ArchivalPolicyError("archive manifest is not valid JSON") from exc
        if persisted != manifest.__dict__:
            raise ArchivalPolicyError("archive manifest does not match the requested transaction")
        content = self.store.get(manifest.object_key)
        if hashlib.sha256(content).hexdigest() != manifest.payload_hash:
            raise ArchivalPolicyError("archived payload hash does not match its manifest")
        return content


def _validate_object_key(key: str) -> None:
    if not isinstance(key, str) or not key or "\\" in key or "\x00" in key:
        raise ArchivalPolicyError("archive object key must be a safe relative path")
    relative = PurePosixPath(key)
    if relative.is_absolute() or any(
        part in {"", ".", ".."}
        or part.startswith(".archive-")
        or not re.fullmatch(r"[A-Za-z0-9._=-]+", part)
        for part in relative.parts
    ):
        raise ArchivalPolicyError("archive object key must be a safe relative path")


def _as_utc(value: datetime) -> datetime:
    if not isinstance(value, datetime):
        raise ArchivalPolicyError("archive timestamps must be datetime values")
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)