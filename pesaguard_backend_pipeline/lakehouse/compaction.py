"""
Data compaction and file maintenance for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, List, Optional
from uuid import uuid4

from .lakehouse_types import LakehouseError
from .parquet_io import ParquetIO


class CompactionError(LakehouseError):
    pass


@dataclass(frozen=True)
class CompactionResult:
    table_name: str
    partition_path: str
    input_files: int
    output_files: int
    input_size_bytes: int
    output_size_bytes: int
    records_compacted: int
    completed_at: str


class CompactionManager:
    def __init__(
        self,
        parquet_io: ParquetIO,
        threshold_files: int = 10,
        threshold_size_mb: int = 1024,
    ):
        self.parquet_io = parquet_io
        self.threshold_files = threshold_files
        self.threshold_size_mb = threshold_size_mb
        self._compacted: Dict[str, CompactionResult] = {}

    def should_compact(
        self, table_name: str, partition_path: str,
        file_count: int, total_size_bytes: int
    ) -> bool:
        if file_count < self.threshold_files:
            return False
        size_mb = total_size_bytes / (1024 * 1024)
        if size_mb < self.threshold_size_mb:
            return False
        return True

    def compact_partition(
        self,
        table_name: str,
        partition_path: str,
        object_keys: List[str],
    ) -> CompactionResult:
        if not object_keys:
            raise CompactionError("Cannot compact empty object list")
        if len(set(object_keys)) != len(object_keys):
            raise CompactionError("Compaction input contains duplicate object keys")
        input_prefix = f"{partition_path.rstrip('/')}/"
        if not partition_path or any(not key.startswith(input_prefix) for key in object_keys):
            raise CompactionError("Every compaction object must belong to the requested partition")

        all_records: List[dict] = []
        total_input_size = 0
        for key in object_keys:
            data = self.parquet_io.get_object(key)
            total_input_size += len(data)
            records = self.parquet_io.read_records(data)
            all_records.extend(records)

        output_key = (
            f"{partition_path.rstrip('/')}/compacted_"
            f"{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}_{uuid4().hex}.parquet"
        )
        output_bytes = self.parquet_io.write_records(
            all_records,
            schema_version=all_records[0].get("_schema_version", "1.0")
            if all_records else "1.0",
            table_name=table_name,
        )
        self.parquet_io.store_object(output_key, output_bytes)
        for key in object_keys:
            self.parquet_io.delete_object(key)

        result = CompactionResult(
            table_name=table_name,
            partition_path=partition_path,
            input_files=len(object_keys),
            output_files=1,
            input_size_bytes=total_input_size,
            output_size_bytes=len(output_bytes),
            records_compacted=len(all_records),
            completed_at=datetime.now(timezone.utc).isoformat(),
        )
        self._compacted[f"{table_name}:{partition_path}"] = result
        return result

    def get_compaction_result(
        self, table_name: str, partition_path: str
    ) -> Optional[CompactionResult]:
        key = f"{table_name}:{partition_path}"
        return self._compacted.get(key)

    def list_compactions(self, table_name: Optional[str] = None) -> List[CompactionResult]:
        results = list(self._compacted.values())
        if table_name:
            results = [r for r in results if r.table_name == table_name]
        return sorted(results, key=lambda r: r.completed_at)
