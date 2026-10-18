"""
Table format management for the PesaGuard Data Lakehouse.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .lakehouse_types import LakehouseError, TableFormat, TableFormatType


class TableFormatError(LakehouseError):
    pass


@dataclass(frozen=True)
class TableMetadata:
    table_name: str
    format: TableFormat
    schema: Dict[str, str]
    partition_spec: Optional[Dict[str, str]] = None
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    updated_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    row_count: int = 0
    file_count: int = 0
    total_size_bytes: int = 0
    properties: Dict[str, str] = field(default_factory=dict)


class TableFormatManager:
    """Manages table formats in the lakehouse."""

    def __init__(self, config):
        self.config = config
        self._tables: Dict[str, TableMetadata] = {}
        self._format_handlers: Dict[TableFormatType, "TableFormatHandler"] = {}
        self._register_default_handlers()

    def _register_default_handlers(self) -> None:
        from .table_format_handlers import (
            ParquetFormatHandler,
        )
        self._format_handlers[TableFormatType.PARQUET] = ParquetFormatHandler()

    def register_handler(
        self, format_type: TableFormatType, handler: "TableFormatHandler"
    ) -> None:
        self._format_handlers[format_type] = handler

    def create_table(
        self,
        table_name: str,
        format_type: TableFormatType,
        schema: Dict[str, str],
        partition_spec: Optional[Dict[str, str]] = None,
        version: str = "1.0",
        properties: Optional[Dict[str, str]] = None,
    ) -> TableMetadata:
        if table_name in self._tables:
            raise TableFormatError(f"Table {table_name} already exists")

        handler = self._format_handlers.get(format_type)
        if handler is None:
            if format_type == TableFormatType.ICEBERG:
                raise TableFormatError(
                    "Iceberg requires an explicitly registered catalog-backed handler"
                )
            raise TableFormatError(
                f"No handler registered for format type {format_type.value}"
            )

        format_obj = TableFormat(
            format_type=format_type,
            version=version,
            properties=properties or {},
        )

        metadata = TableMetadata(
            table_name=table_name,
            format=format_obj,
            schema=dict(schema),
            partition_spec=dict(partition_spec) if partition_spec else None,
        )

        handler.initialize_table(metadata)
        self._tables[table_name] = metadata
        return metadata

    def get_table(self, table_name: str) -> TableMetadata:
        if table_name not in self._tables:
            raise TableFormatError(f"Table {table_name} not found")
        return self._tables[table_name]

    def update_schema(
        self,
        table_name: str,
        new_schema: Dict[str, str],
        evolution_note: Optional[str] = None,
    ) -> TableMetadata:
        table = self.get_table(table_name)
        handler = self._format_handlers.get(table.format.format_type)
        if handler is None:
            raise TableFormatError(
                f"No handler registered for format type {table.format.format_type.value}"
            )
        handler.validate_schema_evolution(table.schema, new_schema)

        updated = TableMetadata(
            table_name=table_name,
            format=table.format,
            schema=dict(new_schema),
            partition_spec=table.partition_spec,
            created_at=table.created_at,
            updated_at=datetime.now(timezone.utc).isoformat(),
            row_count=table.row_count,
            file_count=table.file_count,
            total_size_bytes=table.total_size_bytes,
            properties=dict(table.properties),
        )
        self._tables[table_name] = updated
        return updated

    def list_tables(self) -> List[str]:
        return sorted(self._tables.keys())

    def get_format_handler(self, format_type: TableFormatType) -> "TableFormatHandler":
        handler = self._format_handlers.get(format_type)
        if handler is None:
            raise TableFormatError(
                f"No handler registered for format type {format_type.value}"
            )
        return handler


class TableFormatHandler:
    def initialize_table(self, metadata: TableMetadata) -> None:
        raise NotImplementedError

    def validate_schema_evolution(
        self, old_schema: Dict[str, str], new_schema: Dict[str, str]
    ) -> None:
        raise NotImplementedError

    def get_table_properties(self, metadata: TableMetadata) -> Dict[str, str]:
        return {}
