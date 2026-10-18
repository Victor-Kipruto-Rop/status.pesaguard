"""
Gold layer for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .bronze_layer import BronzeLayer, BronzeLayerError
from .lakehouse_types import LakehouseConfig
from .parquet_io import ParquetIO


@dataclass(frozen=True)
class GoldWriteResult:
    table_name: str
    object_key: str
    records_written: int
    aggregation_type: str
    bytes_written: int
    schema_version: str
    partition_path: str
    written_at: str


@dataclass(frozen=True)
class GoldReadResult:
    table_name: str
    records: List[dict]
    record_count: int
    schema_version: str


class GoldLayer:
    def __init__(self, config: LakehouseConfig, parquet_io=None):
        self.config = config
        self.parquet_io = parquet_io or ParquetIO(config.warehouse_root)
        self._bronze = BronzeLayer(config, self.parquet_io)
        self._tables: Dict[str, Dict[str, Any]] = {}

    def write(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        records: List[dict],
        *,
        schema_version: str = "1.0",
        observed_at: Optional[str] = None,
        aggregation_type: str = "aggregate",
    ) -> GoldWriteResult:
        if not records:
            raise BronzeLayerError("Cannot write empty record set to gold layer")

        observed_at_str = observed_at or datetime.now(timezone.utc).isoformat()
        enriched = []
        for record in records:
            if not isinstance(record, dict):
                raise BronzeLayerError("Gold records must be dictionaries")
            record_tenant_id = record.get("tenant_id")
            if record_tenant_id is not None and record_tenant_id != tenant_id:
                raise BronzeLayerError("Record tenant_id does not match the storage tenant")
            item = dict(record)
            item["tenant_id"] = tenant_id
            item["_schema_version"] = schema_version
            item["_layer"] = "gold"
            item["_processed_at"] = observed_at_str
            item["_aggregation_type"] = aggregation_type
            enriched.append(item)

        partition_path = self._bronze._table_path(
            table_name, tenant_id, source, observed_at_str
        )
        object_key = self._bronze._object_key(
            table_name, tenant_id, source, "gold", observed_at_str
        )

        parquet_bytes = self.parquet_io.write_records(
            enriched,
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
                "aggregation_types": set(),
            }
        table_meta = self._tables[table_key]
        table_meta["schema_versions"].add(schema_version)
        table_meta["total_records"] += len(enriched)
        table_meta["total_bytes"] += len(parquet_bytes)
        table_meta["write_count"] += 1
        table_meta["aggregation_types"].add(aggregation_type)

        return GoldWriteResult(
            table_name=table_name,
            object_key=object_key,
            records_written=len(enriched),
            aggregation_type=aggregation_type,
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
    ) -> GoldReadResult:
        object_keys = self._bronze._object_keys(
            table_name, tenant_id, source, "gold", observed_at
        )
        if not object_keys:
            return GoldReadResult(
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
        return GoldReadResult(
            table_name=table_name,
            records=records,
            record_count=len(records),
            schema_version=records[0].get("_schema_version", "") if records else "",
        )

    def get_table_metadata(self, tenant_id: str, table_name: str) -> Dict[str, Any]:
        table_key = f"{tenant_id}:{table_name}"
        if table_key not in self._tables:
            raise BronzeLayerError(
                f"Table {table_name} not found for tenant {tenant_id}"
            )
        meta = dict(self._tables[table_key])
        meta["aggregation_types"] = sorted(meta.get("aggregation_types", []))
        return meta
