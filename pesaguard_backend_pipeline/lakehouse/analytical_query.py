"""
Analytical query engine for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from urllib.parse import quote

from .lakehouse_types import GOLD_LAYER, BRONZE_LAYER, SILVER_LAYER, LakehouseError
from .parquet_io import ParquetIO
from tenant_context import is_valid_tenant_id


class AnalyticalQueryError(LakehouseError):
    pass


@dataclass(frozen=True)
class QueryResult:
    query_id: str
    query_type: str
    rows: List[dict]
    row_count: int
    executed_at: str
    source: str
    execution_time_ms: float


class AnalyticalQueryEngine:
    def __init__(self, parquet_io: ParquetIO):
        self.parquet_io = parquet_io
        self._query_history: List[QueryResult] = []

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
    ) -> QueryResult:
        if not isinstance(query_id, str) or not query_id.strip():
            raise AnalyticalQueryError("query_id is required")
        if not isinstance(table_name, str) or not table_name.strip():
            raise AnalyticalQueryError("table_name is required")
        if not is_valid_tenant_id(tenant_id):
            raise AnalyticalQueryError("tenant_id is invalid")
        if layer_name not in {BRONZE_LAYER, SILVER_LAYER, GOLD_LAYER}:
            raise AnalyticalQueryError(f"Unknown lakehouse layer: {layer_name}")
        if limit is not None and (isinstance(limit, bool) or not isinstance(limit, int) or limit < 0):
            raise AnalyticalQueryError("limit must be a non-negative integer")
        if snapshot_id is not None:
            raise AnalyticalQueryError("snapshot-scoped queries are not supported")

        start_time = datetime.now(timezone.utc)
        tenant_component = quote(tenant_id.strip(), safe="-._~")
        prefix = f"tenant={tenant_component}/"
        object_keys = self.parquet_io.list_objects(prefix)
        all_records: List[dict] = []
        for key in object_keys:
            try:
                data = self.parquet_io.get_object(key)
                records = self.parquet_io.read_records(data)
                # Filter records by table name embedded in Parquet metadata
                for record in records:
                    if (
                        record.get("_table_name") == table_name
                        and record.get("_layer", BRONZE_LAYER) == layer_name
                        and record.get("tenant_id", tenant_id) == tenant_id
                    ):
                        all_records.append(record)
            except FileNotFoundError:
                continue
        if filters:
            filtered = []
            for record in all_records:
                match = True
                for col, val in filters.items():
                    if record.get(col) != val:
                        match = False
                        break
                if match:
                    filtered.append(record)
            all_records = filtered
        if columns:
            all_records = [{col: r.get(col) for col in columns} for r in all_records]
        if limit is not None:
            all_records = all_records[:limit]
        execution_time_ms = (
            datetime.now(timezone.utc) - start_time
        ).total_seconds() * 1000
        result = QueryResult(
            query_id=query_id,
            query_type="analytical",
            rows=all_records,
            row_count=len(all_records),
            executed_at=datetime.now(timezone.utc).isoformat(),
            source="lakehouse",
            execution_time_ms=execution_time_ms,
        )
        self._query_history.append(result)
        return result

    def get_query_history(
        self, query_id: Optional[str] = None
    ) -> List[QueryResult]:
        if query_id:
            return [r for r in self._query_history if r.query_id == query_id]
        return list(self._query_history)

    def get_query_metrics(self) -> Dict[str, Any]:
        if not self._query_history:
            return {
                "total_queries": 0,
                "total_execution_time_ms": 0,
                "average_execution_time_ms": 0,
                "max_execution_time_ms": 0,
                "total_rows_returned": 0,
            }
        execution_times = [r.execution_time_ms for r in self._query_history]
        total_rows = sum(r.row_count for r in self._query_history)
        return {
            "total_queries": len(self._query_history),
            "total_execution_time_ms": sum(execution_times),
            "average_execution_time_ms": sum(execution_times) / len(execution_times),
            "max_execution_time_ms": max(execution_times),
            "total_rows_returned": total_rows,
        }
