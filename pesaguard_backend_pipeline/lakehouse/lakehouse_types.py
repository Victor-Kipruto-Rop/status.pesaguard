"""
Core data types for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional


class LayerType(Enum):
    BRONZE = "bronze"
    SILVER = "silver"
    GOLD = "gold"


class TableFormatType(Enum):
    PARQUET = "parquet"
    ICEBERG = "iceberg"
    DELTA = "delta"


@dataclass(frozen=True)
class TableFormat:
    format_type: TableFormatType
    version: str
    properties: Dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class PartitionSpec:
    partition_fields: List[str]
    transforms: Dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class PartitionEvolution:
    evolution_id: str
    table_name: str
    old_spec: PartitionSpec
    new_spec: PartitionSpec
    effective_from: str
    status: str


@dataclass(frozen=True)
class LakehouseConfig:
    warehouse_root: str
    default_table_format: TableFormatType = TableFormatType.PARQUET
    default_partition_fields: List[str] = field(default_factory=list)
    compaction_enabled: bool = True
    compaction_threshold_files: int = 10
    compaction_threshold_size_mb: int = 1024
    snapshot_retention_days: int = 30
    max_snapshots: int = 50
    tenant_isolation_enabled: bool = True


class LakehouseError(Exception):
    pass


class TableNotFoundError(LakehouseError):
    pass


class PartitionError(LakehouseError):
    pass


class SchemaEvolutionError(LakehouseError):
    pass


BRONZE_LAYER = LayerType.BRONZE.value
SILVER_LAYER = LayerType.SILVER.value
GOLD_LAYER = LayerType.GOLD.value
