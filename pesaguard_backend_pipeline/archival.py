"""Policy-driven hot, warm, and cold transaction archival."""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any, Mapping, Optional


class ArchivalPolicyError(ValueError):
    """Raised when archival windows are missing or inconsistent."""


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


class ArchiveManager:
    def __init__(self, policy: RetentionPolicy, store: Optional[ArchiveObjectStore] = None):
        self.policy = policy
        self.store = store or ArchiveObjectStore()

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
        payload_hash = hashlib.sha256(payload).hexdigest()
        timestamp = _as_utc(occurred_at)
        tenant_partition = hashlib.sha256(tenant_id.encode("utf-8")).hexdigest()[:32]
        transaction_partition = hashlib.sha256(transaction_id.encode("utf-8")).hexdigest()[:32]
        key = (
            f"tier={tier}/tenant={tenant_partition}/year={timestamp.year:04d}/"
            f"month={timestamp.month:02d}/day={timestamp.day:02d}/{transaction_partition}.json"
        )
        self.store.put(key, payload)
        return ArchiveManifest(
            tenant_id,
            transaction_id,
            tier,
            key,
            payload_hash,
            _as_utc(now or datetime.now(timezone.utc)).isoformat(),
        )

    def read_verified(self, manifest: ArchiveManifest) -> bytes:
        """Read an archived transaction only when its content hash matches its manifest."""
        content = self.store.get(manifest.object_key)
        if hashlib.sha256(content).hexdigest() != manifest.payload_hash:
            raise ArchivalPolicyError("archived payload hash does not match its manifest")
        return content


def _as_utc(value: datetime) -> datetime:
    if not isinstance(value, datetime):
        raise ArchivalPolicyError("archive timestamps must be datetime values")
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)