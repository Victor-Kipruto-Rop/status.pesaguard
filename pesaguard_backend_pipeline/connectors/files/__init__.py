"""CSV and spreadsheet source connectors."""

from __future__ import annotations

from typing import Any, Mapping

from connectors.base import AdapterConnector
from ingestion import CsvAdapter


class FileConnector(AdapterConnector):
    def __init__(self, ingestion_service, credentials=None):
        super().__init__(CsvAdapter(), ingestion_service, credentials)


class CsvFileConnector(FileConnector):
    """Explicit CSV connector for file-based import flows."""

    def __init__(self, ingestion_service, credentials=None):
        super().__init__(ingestion_service, credentials)
        self.source = "csv"


class ExcelFileConnector(FileConnector):
    """Spreadsheet connector for .xlsx imports and internal ledger feeds."""

    def __init__(self, ingestion_service, credentials=None):
        super().__init__(ingestion_service, credentials)
        self.source = "excel"

    def fetch(self, payload: Mapping[str, Any]):
        values = payload.get("rows") if isinstance(payload, Mapping) else None
        if values is None:
            return ()
        return values


__all__ = ["FileConnector", "CsvFileConnector", "ExcelFileConnector"]