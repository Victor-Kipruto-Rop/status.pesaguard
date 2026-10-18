"""Atomic Parquet I/O backed by a durable local filesystem directory."""

from __future__ import annotations

import io
import os
import uuid
from pathlib import Path
from typing import List

import pyarrow as pa
import pyarrow.parquet as pq


class ParquetIO:
    """Parquet and object-key storage for a mounted warehouse directory.

    This adapter persists data across process restarts when ``warehouse_root``
    points to durable storage. It is not an S3 or catalog implementation.
    """

    def __init__(self, warehouse_root: str):
        if not isinstance(warehouse_root, str) or not warehouse_root.strip():
            raise ValueError("warehouse_root must be a non-empty path")
        self.warehouse_root = str(Path(warehouse_root).expanduser().resolve())
        self._root = Path(self.warehouse_root)
        self._root.mkdir(parents=True, exist_ok=True)

    def write_records(
        self,
        records: List[dict],
        *,
        schema_version: str = "1.0",
        table_name: str = "unknown",
    ) -> bytes:
        if not records:
            raise ValueError("Cannot write empty record set to Parquet")

        enriched_records = []
        for record in records:
            if not isinstance(record, dict):
                raise TypeError("Parquet records must be dictionaries")
            enriched = dict(record)
            enriched["_schema_version"] = schema_version
            enriched["_table_name"] = table_name
            enriched_records.append(enriched)

        first_record = enriched_records[0]
        fields = []
        for key, value in first_record.items():
            if isinstance(value, bool):
                pa_type = pa.bool_()
            elif isinstance(value, int):
                pa_type = pa.int64()
            elif isinstance(value, float):
                pa_type = pa.float64()
            elif isinstance(value, str):
                pa_type = pa.string()
            else:
                pa_type = pa.string()
            fields.append(pa.field(key, pa_type))

        schema = pa.schema(fields)
        columns = {
            field.name: [record.get(field.name) for record in enriched_records]
            for field in schema
        }
        table = pa.table(columns, schema=schema)
        buffer = io.BytesIO()
        pq.write_table(table, buffer, compression="snappy")
        return buffer.getvalue()

    def read_records(self, parquet_bytes: bytes) -> List[dict]:
        if not isinstance(parquet_bytes, (bytes, bytearray, memoryview)):
            raise TypeError("parquet_bytes must be bytes-like")
        return pq.read_table(io.BytesIO(bytes(parquet_bytes))).to_pylist()

    @staticmethod
    def _validate_key(key: str, *, allow_empty: bool = False) -> str:
        if not isinstance(key, str):
            raise TypeError("object key must be a string")
        if allow_empty and key == "":
            return key
        if not key or key.startswith("/") or "\\" in key or "\x00" in key:
            raise ValueError("object key must be a relative POSIX path")
        parts = key.rstrip("/").split("/")
        if not parts or any(part in {"", ".", ".."} for part in parts):
            raise ValueError("object key contains an unsafe path segment")
        return key

    def _path_for_key(self, object_key: str) -> Path:
        key = self._validate_key(object_key)
        path = self._root.joinpath(*key.split("/")).resolve()
        try:
            path.relative_to(self._root)
        except ValueError as exc:
            raise ValueError("object key escapes warehouse_root") from exc
        return path

    def store_object(self, object_key: str, data: bytes) -> None:
        if not isinstance(data, (bytes, bytearray, memoryview)):
            raise TypeError("object data must be bytes-like")
        target = self._path_for_key(object_key)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
        try:
            descriptor = os.open(
                temporary,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
            )
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)

    def get_object(self, object_key: str) -> bytes:
        return self._path_for_key(object_key).read_bytes()

    def delete_object(self, object_key: str) -> bool:
        path = self._path_for_key(object_key)
        try:
            path.unlink()
            return True
        except FileNotFoundError:
            return False

    def list_objects(self, prefix: str = "") -> List[str]:
        prefix = self._validate_key(prefix, allow_empty=True)
        if not self._root.exists():
            return []
        return sorted(
            path.relative_to(self._root).as_posix()
            for path in self._root.rglob("*")
            if path.is_file()
            and not path.name.endswith(".tmp")
            and path.relative_to(self._root).as_posix().startswith(prefix)
        )
