"""
Bronze layer for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from urllib.parse import quote
from uuid import uuid4

from .lakehouse_types import (
    LakehouseConfig, LakehouseError, TableFormatType,
)
from .parquet_io import ParquetIO


class BronzeLayerError(LakehouseError):
    pass


@dataclass(frozen=True)
class BronzeWriteResult:
    table_name: str
    object_key: str
    records_written: int
    bytes_written: int
    schema_version: str
    partition_path: str
    written_at: str


@dataclass(frozen=True)
class BronzeReadResult:
    table_name: str
    records: List[dict]
    record_count: int
    schema_version: str


class BronzeLayer:
    def __init__(
        self,
        config: LakehouseConfig,
        parquet_io: Optional[ParquetIO] = None,
    ):
        self.config = config
        self.parquet_io = parquet_io or ParquetIO(config.warehouse_root)
        self._tables: Dict[str, Dict[str, Any]] = {}

    def _table_path(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        observed_at: Optional[str] = None,
    ) -> str:
        from partitioning import raw_object_partition
        return raw_object_partition(tenant_id, source, observed_at)

    @staticmethod
    def _table_component(table_name: str) -> str:
        if not isinstance(table_name, str) or not table_name.strip() or len(table_name) > 128:
            raise BronzeLayerError("table_name must be a non-empty string of at most 128 characters")
        return quote(table_name.strip(), safe="-._~")

    def _object_key(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        layer: str,
        observed_at: str,
    ) -> str:
        partition_path = self._table_path(table_name, tenant_id, source, observed_at)
        timestamp = observed_at.replace(":", "-").replace("+", "plus")
        return (
            f"{partition_path}/layer={layer}/{self._table_component(table_name)}_"
            f"{timestamp}_{uuid4().hex}.parquet"
        )

    def _object_keys(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        layer: str,
        observed_at: Optional[str] = None,
    ) -> List[str]:
        partition_path = self._table_path(table_name, tenant_id, source, observed_at)
        layer_path = f"{partition_path}/layer={layer}/"
        name_prefix = f"{layer_path}{self._table_component(table_name)}_"
        if observed_at is not None:
            timestamp = observed_at.replace(":", "-").replace("+", "plus")
            name_prefix = f"{name_prefix}{timestamp}_"
        return [
            key for key in self.parquet_io.list_objects(layer_path)
            if key.startswith(name_prefix)
        ]

    def write(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        records: List[dict],
        *,
        schema_version: str = "1.0",
        observed_at: Optional[str] = None,
        format_type: TableFormatType = TableFormatType.PARQUET,
    ) -> BronzeWriteResult:
        if not records:
            raise BronzeLayerError("Cannot write empty record set to bronze layer")

        observed_at_str = observed_at or datetime.now(timezone.utc).isoformat()
        partition_path = self._table_path(table_name, tenant_id, source, observed_at_str)
        object_key = self._object_key(
            table_name, tenant_id, source, "bronze", observed_at_str
        )

        enriched_records = []
        for record in records:
            if not isinstance(record, dict):
                raise BronzeLayerError("Bronze records must be dictionaries")
            record_tenant_id = record.get("tenant_id")
            if record_tenant_id is not None and record_tenant_id != tenant_id:
                raise BronzeLayerError("Record tenant_id does not match the storage tenant")
            enriched = dict(record)
            enriched["_layer"] = "bronze"
            enriched_records.append(enriched)

        parquet_bytes = self.parquet_io.write_records(
            enriched_records,
            schema_version=schema_version,
            table_name=table_name,
        )
        self.parquet_io.store_object(object_key, parquet_bytes)

        table_key = f"{tenant_id}:{table_name}"
        if table_key not in self._tables:
            self._tables[table_key] = {
                "schema_versions": set(),
                "total_records": 0,
                "total_bytes": 0,
                "write_count": 0,
            }
        table_meta = self._tables[table_key]
        table_meta["schema_versions"].add(schema_version)
        table_meta["total_records"] += len(records)
        table_meta["total_bytes"] += len(parquet_bytes)
        table_meta["write_count"] += 1

        return BronzeWriteResult(
            table_name=table_name,
            object_key=object_key,
            records_written=len(records),
            bytes_written=len(parquet_bytes),
            schema_version=schema_version,
            partition_path=partition_path,
            written_at=observed_at_str,
        )

    def read(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        observed_at: Optional[str] = None,
    ) -> BronzeReadResult:
        object_keys = self._object_keys(
            table_name, tenant_id, source, "bronze", observed_at
        )
        if not object_keys:
            return BronzeReadResult(
                table_name=table_name,
                records=[],
                record_count=0,
                schema_version="",
            )
        if observed_at is None:
            object_keys = [sorted(object_keys)[-1]]
        records = []
        for object_key in object_keys:
            records.extend(self.parquet_io.read_records(self.parquet_io.get_object(object_key)))
        return BronzeReadResult(
            table_name=table_name,
            records=records,
            record_count=len(records),
            schema_version=records[0].get("_schema_version", "") if records else "",
        )

    def list_tables(self, tenant_id: str) -> List[str]:
        return sorted(
            {
                key.split(":")[1]
                for key in self._tables
                if key.startswith(f"{tenant_id}:")
            }
        )

    def get_table_metadata(self, tenant_id: str, table_name: str) -> Dict[str, Any]:
        table_key = f"{tenant_id}:{table_name}"
        if table_key not in self._tables:
            raise BronzeLayerError(
                f"Table {table_name} not found for tenant {tenant_id}"
            )
        return dict(self._tables[table_key])
