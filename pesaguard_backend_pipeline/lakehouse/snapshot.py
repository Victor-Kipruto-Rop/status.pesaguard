"""
Snapshot-based history for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional

from .lakehouse_types import LakehouseError


class SnapshotError(LakehouseError):
    pass


@dataclass(frozen=True)
class Snapshot:
    snapshot_id: str
    table_name: str
    snapshot_timestamp: str
    object_keys: List[str]
    record_count: int
    total_size_bytes: int
    schema_version: str
    properties: Dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class SnapshotResult:
    table_name: str
    snapshot_id: str
    snapshot_timestamp: str
    object_keys: List[str]
    record_count: int


class SnapshotManager:
    def __init__(self, parquet_io, max_snapshots: int = 50):
        self.parquet_io = parquet_io
        self.max_snapshots = max_snapshots
        self._snapshots: Dict[str, List[Snapshot]] = {}

    def create_snapshot(
        self,
        table_name: str,
        object_keys: List[str],
        record_count: int,
        total_size_bytes: int,
        schema_version: str = "1.0",
        snapshot_timestamp: Optional[str] = None,
        properties: Optional[Dict[str, str]] = None,
    ) -> SnapshotResult:
        snapshot_id = (
            f"snap_{table_name}_"
            f"{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S_%f')}"
        )
        timestamp = snapshot_timestamp or datetime.now(timezone.utc).isoformat()
        snapshot = Snapshot(
            snapshot_id=snapshot_id,
            table_name=table_name,
            snapshot_timestamp=timestamp,
            object_keys=list(object_keys),
            record_count=record_count,
            total_size_bytes=total_size_bytes,
            schema_version=schema_version,
            properties=properties or {},
        )
        snapshots = self._snapshots.setdefault(table_name, [])
        snapshots.append(snapshot)
        if len(snapshots) > self.max_snapshots:
            self._snapshots[table_name] = snapshots[-self.max_snapshots:]
        return SnapshotResult(
            table_name=table_name,
            snapshot_id=snapshot_id,
            snapshot_timestamp=timestamp,
            object_keys=list(object_keys),
            record_count=record_count,
        )

    def get_snapshot(
        self, table_name: str, snapshot_id: str
    ) -> Optional[Snapshot]:
        snapshots = self._snapshots.get(table_name, [])
        for s in snapshots:
            if s.snapshot_id == snapshot_id:
                return s
        return None

    def get_snapshot_at_time(
        self, table_name: str, timestamp: str
    ) -> Optional[Snapshot]:
        snapshots = self._snapshots.get(table_name, [])
        if not snapshots:
            return None
        target = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        best: Optional[Snapshot] = None
        for s in snapshots:
            s_time = datetime.fromisoformat(
                s.snapshot_timestamp.replace("Z", "+00:00")
            )
            if s_time <= target:
                if best is None or s_time > datetime.fromisoformat(
                    best.snapshot_timestamp.replace("Z", "+00:00")
                ):
                    best = s
        return best

    def list_snapshots(self, table_name: str) -> List[Snapshot]:
        return list(self._snapshots.get(table_name, []))

    def delete_snapshot(self, table_name: str, snapshot_id: str) -> bool:
        snapshots = self._snapshots.get(table_name, [])
        for i, s in enumerate(snapshots):
            if s.snapshot_id == snapshot_id:
                snapshots.pop(i)
                return True
        return False

    def get_latest_snapshot(self, table_name: str) -> Optional[Snapshot]:
        snapshots = self._snapshots.get(table_name, [])
        if not snapshots:
            return None
        return snapshots[-1]
