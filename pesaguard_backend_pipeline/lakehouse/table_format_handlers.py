"""
Table format handlers for Parquet and Iceberg.
"""

from __future__ import annotations

from typing import Dict

from .lakehouse_types import TableFormatType
from .table_format import TableFormatHandler, TableMetadata


class ParquetFormatHandler(TableFormatHandler):
    def initialize_table(self, metadata: TableMetadata) -> None:
        if metadata.format.format_type != TableFormatType.PARQUET:
            raise ValueError(
                f"ParquetFormatHandler cannot handle format {metadata.format.format_type}"
            )

    def validate_schema_evolution(
        self, old_schema: Dict[str, str], new_schema: Dict[str, str]
    ) -> None:
        old_cols = set(old_schema.keys())
        new_cols = set(new_schema.keys())
        common = old_cols & new_cols
        for col in common:
            if old_schema[col] != new_schema[col]:
                raise ValueError(
                    f"Parquet schema evolution does not support type change for column "
                    f"{col}: {old_schema[col]} -> {new_schema[col]}"
                )

    def get_table_properties(self, metadata: TableMetadata) -> Dict[str, str]:
        return {
            "format": "parquet",
            "version": metadata.format.version,
            "compression": "snappy",
            **metadata.format.properties,
        }


class IcebergFormatHandler(TableFormatHandler):
    def initialize_table(self, metadata: TableMetadata) -> None:
        raise ValueError(
            "IcebergFormatHandler is schema-only; configure a catalog-backed handler"
        )

    def validate_schema_evolution(
        self, old_schema: Dict[str, str], new_schema: Dict[str, str]
    ) -> None:
        old_cols = set(old_schema.keys())
        new_cols = set(new_schema.keys())
        common = old_cols & new_cols
        for col in common:
            old_type = old_schema[col]
            new_type = new_schema[col]
            if old_type != new_type:
                if not self._is_type_widening(old_type, new_type):
                    raise ValueError(
                        f"Iceberg schema evolution does not support type change "
                        f"for column {col}: {old_type} -> {new_type}"
                    )

    def _is_type_widening(self, old_type: str, new_type: str) -> bool:
        widening_pairs = {
            ("int", "long"), ("int", "float"), ("int", "double"),
            ("long", "double"), ("float", "double"),
            ("date", "timestamp"), ("string", "varchar"), ("varchar", "string"),
        }
        return (old_type, new_type) in widening_pairs

    def get_table_properties(self, metadata: TableMetadata) -> Dict[str, str]:
        return {
            "format": "iceberg",
            "version": metadata.format.version,
            "table_format": "iceberg",
            **metadata.format.properties,
        }
