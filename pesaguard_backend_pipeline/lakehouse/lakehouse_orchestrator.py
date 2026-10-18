"""
Main orchestration layer for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from .lakehouse_types import (
    LakehouseConfig, LakehouseError, PartitionSpec,
    TableFormat, TableFormatType,
)
from .analytical_query import AnalyticalQueryEngine
from .bronze_layer import BronzeLayer
from .compaction import CompactionManager
from .gold_layer import GoldLayer
from .lakehouse_types import BRONZE_LAYER, GOLD_LAYER, SILVER_LAYER
from .partition_evolution import PartitionEvolutionManager
from .parquet_io import ParquetIO
from .silver_layer import SilverLayer
from .snapshot import SnapshotManager
from .table_format import TableFormatManager


class LakehouseOrchestrator:
    def __init__(self, config: LakehouseConfig):
        self.config = config
        self.parquet_io = ParquetIO(config.warehouse_root)
        self.table_manager = TableFormatManager(config)
        self.partition_manager = PartitionEvolutionManager(config)
        self.compaction_manager = CompactionManager(
            self.parquet_io,
            threshold_files=config.compaction_threshold_files,
            threshold_size_mb=config.compaction_threshold_size_mb,
        )
        self.snapshot_manager = SnapshotManager(
            self.parquet_io,
            max_snapshots=config.max_snapshots,
        )
        self.analytics_engine = AnalyticalQueryEngine(self.parquet_io)
        self._layers: Dict[str, Any] = {}
        self._init_layers()

    def _init_layers(self) -> None:
        self._layers[BRONZE_LAYER] = BronzeLayer(self.config, self.parquet_io)
        self._layers[SILVER_LAYER] = SilverLayer(self.config, self.parquet_io)
        self._layers[GOLD_LAYER] = GoldLayer(self.config, self.parquet_io)

    def get_layer(self, layer_name: str) -> Any:
        if layer_name not in self._layers:
            raise LakehouseError(f"Unknown layer: {layer_name}")
        return self._layers[layer_name]

    def create_table(
        self,
        table_name: str,
        format_type: TableFormatType = TableFormatType.PARQUET,
        schema: Optional[Dict[str, str]] = None,
        partition_spec: Optional[PartitionSpec] = None,
        version: str = "1.0",
    ) -> Any:
        fmt = TableFormat(format_type=format_type, version=version)
        partition_dict = None
        if partition_spec:
            partition_dict = {
                "partition_fields": partition_spec.partition_fields,
                "transforms": partition_spec.transforms,
            }
        metadata = self.table_manager.create_table(
            table_name=table_name,
            format_type=format_type,
            schema=schema or {},
            partition_spec=partition_dict,
            version=version,
        )
        if partition_spec:
            self.partition_manager.register_table_partition(table_name, partition_spec)
        return metadata

    def write_to_bronze(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        records: List[dict],
        *,
        schema_version: str = "1.0",
        observed_at: Optional[str] = None,
    ) -> Any:
        layer = self.get_layer(BRONZE_LAYER)
        return layer.write(
            table_name=table_name,
            tenant_id=tenant_id,
            source=source,
            records=records,
            schema_version=schema_version,
            observed_at=observed_at,
        )

    def write_to_silver(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        records: List[dict],
        *,
        schema_version: str = "1.0",
        observed_at: Optional[str] = None,
        required_fields: Optional[List[str]] = None,
    ) -> Any:
        layer = self.get_layer(SILVER_LAYER)
        return layer.write(
            table_name=table_name,
            tenant_id=tenant_id,
            source=source,
            records=records,
            schema_version=schema_version,
            observed_at=observed_at,
            required_fields=required_fields,
        )

    def write_to_gold(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        records: List[dict],
        *,
        schema_version: str = "1.0",
        observed_at: Optional[str] = None,
        aggregation_type: str = "aggregate",
    ) -> Any:
        layer = self.get_layer(GOLD_LAYER)
        return layer.write(
            table_name=table_name,
            tenant_id=tenant_id,
            source=source,
            records=records,
            schema_version=schema_version,
            observed_at=observed_at,
            aggregation_type=aggregation_type,
        )

    def read_from_bronze(
        self,
        table_name: str,
        tenant_id: str,
        source: str,
        observed_at: Optional[str] = None,
    ) -> Any:
        layer = self.get_layer(BRONZE_LAYER)
        return layer.read(
            table_name=table_name,
            tenant_id=tenant_id,
            source=source,
            observed_at=observed_at,
        )


    def execute_analytical_query(
        self,
        query_id: str,
        table_name: str,
        tenant_id: str,
        filters: Optional[Dict[str, Any]] = None,
        columns: Optional[List[str]] = None,
        limit: Optional[int] = None,
        snapshot_id: Optional[str] = None,
        layer_name: str = GOLD_LAYER,
    ) -> Any:
        return self.analytics_engine.execute_analytical_query(
            query_id=query_id,
            table_name=table_name,
            tenant_id=tenant_id,
            filters=filters,
            columns=columns,
            limit=limit,
            snapshot_id=snapshot_id,
            layer_name=layer_name,
        )

    def create_snapshot(
        self,
        table_name: str,
        object_keys: List[str],
        record_count: int,
        total_size_bytes: int,
        schema_version: str = "1.0",
        snapshot_timestamp: Optional[str] = None,
        properties: Optional[Dict[str, str]] = None,
    ) -> Any:
        return self.snapshot_manager.create_snapshot(
            table_name=table_name,
            object_keys=object_keys,
            record_count=record_count,
            total_size_bytes=total_size_bytes,
            schema_version=schema_version,
            snapshot_timestamp=snapshot_timestamp,
            properties=properties,
        )

    def compact_partition(
        self,
        table_name: str,
        partition_path: str,
        object_keys: List[str],
    ) -> Any:
        return self.compaction_manager.compact_partition(
            table_name=table_name,
            partition_path=partition_path,
            object_keys=object_keys,
        )

    def plan_partition_evolution(
        self,
        table_name: str,
        new_spec: PartitionSpec,
        effective_from: Optional[str] = None,
    ) -> Any:
        return self.partition_manager.plan_evolution(
            table_name=table_name,
            new_spec=new_spec,
            effective_from=effective_from,
        )

    def execute_partition_evolution(self, evolution_id: str) -> Any:
        return self.partition_manager.execute_evolution(evolution_id=evolution_id)

    def rollback_partition_evolution(self, evolution_id: str) -> Any:
        return self.partition_manager.rollback_evolution(evolution_id=evolution_id)

    def get_metrics(self) -> Dict[str, Any]:
        return {
            "tables": self.table_manager.list_tables(),
            "table_count": len(self.table_manager.list_tables()),
            "snapshots": {
                table: len(self.snapshot_manager.list_snapshots(table))
                for table in self.table_manager.list_tables()
            },
            "compactions": len(self.compaction_manager.list_compactions()),
            "analytics": self.analytics_engine.get_query_metrics(),
        }
