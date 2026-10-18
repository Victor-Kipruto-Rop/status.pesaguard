"""
Change Data Capture (CDC) for PesaGuard.

Captures inserts, updates, and deletes from PostgreSQL operational tables and
propagates them to downstream systems (Kafka, data lakehouse, analytics marts).
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, Iterable, List, Optional, Set

from sqlalchemy import (
    Column,
    DateTime,
    Integer,
    MetaData,
    String,
    Table,
    create_engine,
    inspect,
    select,
    text,
)
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

try:
    import psycopg2
    import psycopg2.extras
    HAS_PSYCOPG2 = True
except ImportError:
    psycopg2 = None
    HAS_PSYCOPG2 = False

logger = logging.getLogger("pesaguard.cdc")


class ChangeOperation(Enum):
    INSERT = "insert"
    UPDATE = "update"
    DELETE = "delete"
    TRUNCATE = "truncate"
    SCHEMA_CHANGE = "schema_change"


@dataclass(frozen=True)
class ChangeEvent:
    event_id: str
    table_name: str
    operation: ChangeOperation
    tenant_id: str
    record_key: str
    old_values: Optional[Dict[str, Any]]
    new_values: Optional[Dict[str, Any]]
    changed_at: str
    source_connector: str
    schema_version: Optional[str] = None
    checksum: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "table_name": self.table_name,
            "operation": self.operation.value,
            "tenant_id": self.tenant_id,
            "record_key": self.record_key,
            "old_values": self.old_values,
            "new_values": self.new_values,
            "changed_at": self.changed_at,
            "source_connector": self.source_connector,
            "schema_version": self.schema_version,
            "checksum": self.checksum,
        }

    def compute_checksum(self) -> str:
        operation = self.operation
        if hasattr(operation, "value"):
            operation = operation.value
        payload = {
            "table_name": self.table_name,
            "operation": operation,
            "tenant_id": self.tenant_id,
            "record_key": self.record_key,
            "changed_at": self.changed_at,
        }
        return hashlib.sha256(
            json.dumps(payload, sort_keys=True).encode("utf-8")
        ).hexdigest()[:16]


class CDCError(Exception):
    pass


class TableNotTrackedError(CDCError):
    pass


class SchemaChangeError(CDCError):
    pass


class ConnectorOutageError(CDCError):
    pass


@dataclass(frozen=True)
class TableSchema:
    table_name: str
    columns: Dict[str, str]
    primary_keys: List[str]
    captured_at: str


class TombstoneDetector:
    """
    Detects hard deletes by tracking previously seen primary keys per table.

    When records disappear between sync cycles, DELETE tombstone events are
    emitted with old_values populated (from the last known state) and
    new_values set to None. This ensures hard deletes are never silently
    dropped in CDC pipelines.
    """

    def __init__(self) -> None:
        # table_name -> set of record keys seen in the last capture
        self._seen_keys: Dict[str, Set[str]] = {}
        # table_name -> record_key -> last known row values (for tombstone old_values)
        self._last_values: Dict[str, Dict[str, Dict[str, Any]]] = {}

    def record_seen(
        self,
        table_name: str,
        record_key: str,
        values: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Record that a key was seen during a capture cycle."""
        self._seen_keys.setdefault(table_name, set()).add(record_key)
        if values is not None:
            self._last_values.setdefault(table_name, {})[record_key] = dict(values)

    def detect_deletes(self, table_name: str, current_keys: Set[str]) -> List[str]:
        """
        Return record keys that were seen before but are now missing.

        These represent hard-deleted records that require DELETE tombstone
        events.
        """
        previous = self._seen_keys.get(table_name, set())
        deleted = previous - current_keys
        return sorted(deleted)

    def get_last_values(
        self, table_name: str, record_key: str
    ) -> Optional[Dict[str, Any]]:
        """Return the last known values for a record (for tombstone old_values)."""
        return self._last_values.get(table_name, {}).get(record_key)

    def update_seen(
        self, table_name: str, current_keys: Set[str]
    ) -> None:
        """Replace the seen set with the current cycle's keys."""
        self._seen_keys[table_name] = set(current_keys)

    def reset(self, table_name: Optional[str] = None) -> None:
        """Clear tracking state for a table or all tables."""
        if table_name is None:
            self._seen_keys.clear()
            self._last_values.clear()
        else:
            self._seen_keys.pop(table_name, None)
            self._last_values.pop(table_name, None)


class SchemaChangeHandler:
    def __init__(self, engine: Engine):
        self.engine = engine
        self._schemas: Dict[str, TableSchema] = {}
        self._schema_change_callbacks: List[Callable[[str, TableSchema, TableSchema], None]] = []

    def capture_schema(self, table_name: str) -> TableSchema:
        inspector = inspect(self.engine)
        columns = {}
        for col in inspector.get_columns(table_name):
            columns[col["name"]] = str(col["type"])
        pk_constraint = inspector.get_pk_constraint(table_name)
        primary_keys = list(pk_constraint.get("constrained_columns", []))
        schema = TableSchema(
            table_name=table_name,
            columns=columns,
            primary_keys=primary_keys,
            captured_at=datetime.now(timezone.utc).isoformat(),
        )
        self._schemas[table_name] = schema
        return schema

    def get_schema(self, table_name: str) -> Optional[TableSchema]:
        return self._schemas.get(table_name)

    def detect_changes(self, table_name: str) -> Optional[tuple[TableSchema, TableSchema]]:
        old_schema = self._schemas.get(table_name)
        new_schema = self.capture_schema(table_name)
        if old_schema is None:
            return None
        if old_schema.columns != new_schema.columns or old_schema.primary_keys != new_schema.primary_keys:
            return old_schema, new_schema
        return None

    def register_change_callback(
        self, callback: Callable[[str, TableSchema, TableSchema], None]
    ) -> None:
        self._schema_change_callbacks.append(callback)

    def handle_change(self, table_name: str, action: str = "alert") -> None:
        change = self.detect_changes(table_name)
        if change is None:
            return
        old_schema, new_schema = change
        for callback in self._schema_change_callbacks:
            callback(table_name, old_schema, new_schema)
        if action == "fail":
            raise SchemaChangeError(
                f"Schema change detected for {table_name}: "
                f"columns {old_schema.columns} -> {new_schema.columns}"
            )
        elif action == "alert":
            logger.warning(
                "Schema change detected for table %s: columns changed from %s to %s",
                table_name, old_schema.columns, new_schema.columns,
            )
        self._schemas[table_name] = new_schema


class OutageRecoveryManager:
    def __init__(self, engine: Engine, checkpoint_table: str = "cdc_checkpoints"):
        self.engine = engine
        self.checkpoint_table = checkpoint_table
        self._checkpoints: Dict[str, Dict[str, Any]] = {}
        self._ensure_checkpoint_table()

    def _ensure_checkpoint_table(self) -> None:
        metadata = MetaData()
        Table(
            self.checkpoint_table, metadata,
            Column("table_name", String(255), primary_key=True),
            Column("last_processed_at", DateTime(timezone=True)),
            Column("last_record_key", String(255)),
            Column("events_processed", Integer, default=0),
            Column("updated_at", DateTime(timezone=True)),
        )
        metadata.create_all(self.engine, tables=[Table(self.checkpoint_table, metadata)])

    def get_checkpoint(self, table_name: str) -> Optional[Dict[str, Any]]:
        if table_name in self._checkpoints:
            return self._checkpoints[table_name]
        with self.engine.begin() as conn:
            result = conn.execute(
                text(
                    f"SELECT last_processed_at, last_record_key, events_processed "
                    f"FROM {self.checkpoint_table} WHERE table_name = :tn"
                ),
                {"tn": table_name},
            ).fetchone()
        if result is None:
            return None
        checkpoint = {
            "last_processed_at": result[0].isoformat() if result[0] else None,
            "last_record_key": result[1],
            "events_processed": result[2],
        }
        self._checkpoints[table_name] = checkpoint
        return checkpoint

    def update_checkpoint(
        self,
        table_name: str,
        last_processed_at: Optional[datetime] = None,
        last_record_key: Optional[str] = None,
        events_processed: Optional[int] = None,
    ) -> None:
        checkpoint = self.get_checkpoint(table_name) or {}
        if last_processed_at is not None:
            checkpoint["last_processed_at"] = last_processed_at.isoformat()
        if last_record_key is not None:
            checkpoint["last_record_key"] = last_record_key
        if events_processed is not None:
            checkpoint["events_processed"] = events_processed
        self._checkpoints[table_name] = checkpoint
        # Database-agnostic upsert: try insert, fall back to update
        with self.engine.begin() as conn:
            try:
                conn.execute(
                    text(
                        f"INSERT INTO {self.checkpoint_table} "
                        f"(table_name, last_processed_at, last_record_key, events_processed, updated_at) "
                        f"VALUES (:tn, :lpa, :lrk, :ep, :now)"
                    ),
                    {
                        "tn": table_name,
                        "lpa": checkpoint.get("last_processed_at"),
                        "lrk": checkpoint.get("last_record_key"),
                        "ep": checkpoint.get("events_processed", 0),
                        "now": datetime.now(timezone.utc),
                    },
                )
            except Exception:
                # Row exists; update instead
                conn.execute(
                    text(
                        f"UPDATE {self.checkpoint_table} SET "
                        f"last_processed_at = :lpa, "
                        f"last_record_key = :lrk, "
                        f"events_processed = :ep, "
                        f"updated_at = :now "
                        f"WHERE table_name = :tn"
                    ),
                    {
                        "tn": table_name,
                        "lpa": checkpoint.get("last_processed_at"),
                        "lrk": checkpoint.get("last_record_key"),
                        "ep": checkpoint.get("events_processed", 0),
                        "now": datetime.now(timezone.utc),
                    },
                )

    def is_recovering(self, table_name: str) -> bool:
        return table_name in self._checkpoints

    def get_lag_seconds(self, table_name: str) -> Optional[float]:
        checkpoint = self.get_checkpoint(table_name)
        if checkpoint is None or not checkpoint.get("last_processed_at"):
            return None
        last = datetime.fromisoformat(checkpoint["last_processed_at"])
        if last.tzinfo is None:
            last = last.replace(tzinfo=timezone.utc)
        return (datetime.now(timezone.utc) - last).total_seconds()


class CDCConnector(ABC):
    def __init__(
        self,
        engine: Engine,
        schema_handler: SchemaChangeHandler,
        recovery_manager: OutageRecoveryManager,
        source_name: str = "postgresql",
    ):
        self.engine = engine
        self.schema_handler = schema_handler
        self.recovery_manager = recovery_manager
        self.source_name = source_name
        self._tracked_tables: Set[str] = set()
        self._is_running = False

    @abstractmethod
    def capture_changes(
        self,
        table_name: str,
        since: Optional[datetime] = None,
        limit: int = 1000,
    ) -> List[ChangeEvent]:
        raise NotImplementedError

    @abstractmethod
    def get_last_change_at(self, table_name: str) -> Optional[datetime]:
        raise NotImplementedError

    def register_table(self, table_name: str) -> None:
        self._tracked_tables.add(table_name)
        self.schema_handler.capture_schema(table_name)
        self.recovery_manager.get_checkpoint(table_name)

    def is_tracked(self, table_name: str) -> bool:
        return table_name in self._tracked_tables

    def get_tracked_tables(self) -> Set[str]:
        return set(self._tracked_tables)


class PostgreSQLCDCConnector(CDCConnector):
    def __init__(
        self,
        engine: Engine,
        schema_handler: SchemaChangeHandler,
        recovery_manager: OutageRecoveryManager,
        source_name: str = "postgresql",
        timestamp_column: str = "updated_at",
    ):
        super().__init__(engine, schema_handler, recovery_manager, source_name)
        self.timestamp_column = timestamp_column
        self._table_columns: Dict[str, List[str]] = {}
        self._table_pks: Dict[str, List[str]] = {}
        self.tombstone_detector = TombstoneDetector()

    def _get_table_info(self, table_name: str) -> tuple[List[str], List[str]]:
        if table_name in self._table_columns:
            return self._table_columns[table_name], self._table_pks[table_name]
        inspector = inspect(self.engine)
        columns = [col["name"] for col in inspector.get_columns(table_name)]
        pk_constraint = inspector.get_pk_constraint(table_name)
        primary_keys = list(pk_constraint.get("constrained_columns", []))
        self._table_columns[table_name] = columns
        self._table_pks[table_name] = primary_keys
        return columns, primary_keys

    def _record_key_from_row(self, row: Any, primary_keys: List[str]) -> str:
        values = [str(getattr(row, pk, "")) for pk in primary_keys]
        return "|".join(values) if values else str(hash(tuple(values)))

    def capture_changes(
        self,
        table_name: str,
        since: Optional[datetime] = None,
        limit: int = 1000,
    ) -> List[ChangeEvent]:
        if not self.is_tracked(table_name):
            raise TableNotTrackedError(f"Table {table_name} is not registered for CDC")

        columns, primary_keys = self._get_table_info(table_name)

        if self.timestamp_column not in columns:
            raise CDCError(
                f"Table {table_name} does not have '{self.timestamp_column}' column "
                f"required for polling-based CDC"
            )

        checkpoint = self.recovery_manager.get_checkpoint(table_name)
        since_dt = since
        if since_dt is None and checkpoint and checkpoint.get("last_processed_at"):
            since_dt = datetime.fromisoformat(checkpoint["last_processed_at"])
            if since_dt.tzinfo is None:
                since_dt = since_dt.replace(tzinfo=timezone.utc)

        with self.engine.begin() as conn:
            pk_list = ", ".join([f'"{pk}"' for pk in primary_keys])
            col_list = ", ".join([f'"{c}"' for c in columns])

            if since_dt is not None:
                query = text(
                    f"SELECT {col_list} FROM {table_name} "
                    f'WHERE "{self.timestamp_column}" > :since '
                    f'ORDER BY "{self.timestamp_column}" ASC LIMIT :limit'
                )
                params = {"since": since_dt, "limit": limit}
            else:
                query = text(
                    f"SELECT {col_list} FROM {table_name} "
                    f'ORDER BY "{self.timestamp_column}" ASC LIMIT :limit'
                )
                params = {"limit": limit}

            result = conn.execute(query, params)
            rows = result.fetchall()

        events: List[ChangeEvent] = []
        for row in rows:
            new_values = {col: getattr(row, col) for col in columns}
            record_key = self._record_key_from_row(row, primary_keys)
            tenant_id = str(new_values.get("tenant_id", "unknown"))
            changed_at = str(new_values.get(self.timestamp_column, ""))
            event_id = f"cdc_{table_name}_{record_key}_{changed_at}"

            event = ChangeEvent(
                event_id=event_id,
                table_name=table_name,
                operation=ChangeOperation.UPDATE,
                tenant_id=tenant_id,
                record_key=record_key,
                old_values=None,
                new_values=new_values,
                changed_at=changed_at,
                source_connector=self.source_name,
            )
            event = ChangeEvent(
                **{**event.to_dict(), "checksum": event.compute_checksum()}
            )
            events.append(event)

        if events:
            last_change = max(
                self._parse_aware(e.changed_at) for e in events
            )
            self.recovery_manager.update_checkpoint(
                table_name,
                last_processed_at=last_change,
                last_record_key=events[-1].record_key,
                events_processed=len(events),
            )

        # --- Tombstone delete detection ---
        current_keys = {
            self._record_key_from_row(row, primary_keys) for row in rows
        }
        self.tombstone_detector.update_seen(table_name, current_keys)
        deleted_keys = self.tombstone_detector.detect_deletes(
            table_name, current_keys
        )
        for record_key in deleted_keys:
            old_values = self.tombstone_detector.get_last_values(
                table_name, record_key
            )
            changed_at = datetime.now(timezone.utc).isoformat()
            event_id = f"cdc_delete_{table_name}_{record_key}_{changed_at}"
            tenant_id = "unknown"
            if old_values:
                tenant_id = str(old_values.get("tenant_id", "unknown"))
            tombstone_event = ChangeEvent(
                event_id=event_id,
                table_name=table_name,
                operation=ChangeOperation.DELETE,
                tenant_id=tenant_id,
                record_key=record_key,
                old_values=old_values,
                new_values=None,
                changed_at=changed_at,
                source_connector=self.source_name,
            )
            tombstone_event = ChangeEvent(
                **{
                    **tombstone_event.to_dict(),
                    "checksum": tombstone_event.compute_checksum(),
                }
            )
            events.append(tombstone_event)
            self.tombstone_detector.record_seen(
                table_name, record_key, old_values
            )

        return events

    def _parse_aware(self, value: str) -> datetime:
        """Parse a datetime string and ensure it is timezone-aware."""
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt

    def get_last_change_at(self, table_name: str) -> Optional[datetime]:
        if not self.is_tracked(table_name):
            return None
        columns, primary_keys = self._get_table_info(table_name)
        if self.timestamp_column not in columns:
            return None
        with self.engine.begin() as conn:
            query = text(
                f'SELECT MAX("{self.timestamp_column}") FROM {table_name}'
            )
            result = conn.execute(query).fetchone()
        if result and result[0]:
            val = result[0]
            if isinstance(val, str):
                return datetime.fromisoformat(val)
            return val
        return None


class CDCOrchestrator:
    def __init__(
        self,
        connector: CDCConnector,
        publish_callback: Optional[Callable[[ChangeEvent], None]] = None,
    ):
        self.connector = connector
        self.publish_callback = publish_callback
        self._captured_events: List[ChangeEvent] = []
        self._failed_events: List[ChangeEvent] = []

    def capture_and_publish(
        self,
        table_name: str,
        since: Optional[datetime] = None,
        limit: int = 1000,
    ) -> Dict[str, Any]:
        events = self.connector.capture_changes(table_name, since=since, limit=limit)
        published = 0
        failed = 0

        for event in events:
            try:
                if self.publish_callback:
                    self.publish_callback(event)
                self._captured_events.append(event)
                published += 1
            except Exception as exc:
                logger.exception("Failed to publish CDC event %s", event.event_id)
                self._failed_events.append(event)
                failed += 1

        return {
            "table_name": table_name,
            "events_captured": len(events),
            "events_published": published,
            "events_failed": failed,
        }

    def capture_all_tracked(
        self, since: Optional[datetime] = None, limit: int = 1000
    ) -> Dict[str, Dict[str, Any]]:
        results: Dict[str, Dict[str, Any]] = {}
        for table_name in self.connector.get_tracked_tables():
            results[table_name] = self.capture_and_publish(
                table_name, since=since, limit=limit
            )
        return results

    def get_captured_events(self) -> List[ChangeEvent]:
        return list(self._captured_events)

    def get_failed_events(self) -> List[ChangeEvent]:
        return list(self._failed_events)

    def retry_failed(self) -> Dict[str, int]:
        retried = 0
        succeeded = 0
        still_failed: List[ChangeEvent] = []

        for event in self._failed_events:
            try:
                if self.publish_callback:
                    self.publish_callback(event)
                self._captured_events.append(event)
                succeeded += 1
            except Exception:
                still_failed.append(event)
            retried += 1

        self._failed_events = still_failed
        return {"retried": retried, "succeeded": succeeded, "still_failed": len(still_failed)}


class ConsistencyVerifier:
    def __init__(
        self,
        source_engine: Engine,
        target_row_count_fn: Callable[[str], int],
    ):
        self.source_engine = source_engine
        self.target_row_count_fn = target_row_count_fn

    def verify_table_consistency(
        self, table_name: str, target_name: Optional[str] = None
    ) -> Dict[str, Any]:
        target = target_name or table_name
        inspector = inspect(self.source_engine)
        pk_constraint = inspector.get_pk_constraint(table_name)
        primary_keys = list(pk_constraint.get("constrained_columns", []))

        if not primary_keys:
            return {
                "table_name": table_name,
                "target_table": target,
                "consistent": False,
                "reason": "No primary key defined; cannot verify consistency",
            }

        with self.source_engine.begin() as conn:
            result = conn.execute(
                text(f'SELECT COUNT(*) FROM "{table_name}"')
            ).fetchone()
        source_count = result[0] if result else 0

        target_count = self.target_row_count_fn(target)

        consistent = source_count == target_count
        return {
            "table_name": table_name,
            "target_table": target,
            "source_count": source_count,
            "target_count": target_count,
            "consistent": consistent,
            "difference": abs(source_count - target_count),
            "reason": None if consistent else (
                f"Source has {source_count} rows, target has {target_count} rows"
            ),
        }

    def verify_all_tables(
        self, table_names: List[str], target_mapping: Optional[Dict[str, str]] = None
    ) -> Dict[str, Dict[str, Any]]:
        results: Dict[str, Dict[str, Any]] = {}
        for table_name in table_names:
            target = target_mapping.get(table_name) if target_mapping else None
            results[table_name] = self.verify_table_consistency(table_name, target)
        return results

    def get_consistency_report(self, results: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
        total = len(results)
        consistent = sum(1 for r in results.values() if r.get("consistent"))
        inconsistent = total - consistent
        total_diff = sum(r.get("difference", 0) for r in results.values())
        return {
            "total_tables": total,
            "consistent_tables": consistent,
            "inconsistent_tables": inconsistent,
            "total_row_difference": total_diff,
            "all_consistent": inconsistent == 0,
        }


def build_cdc_orchestrator(
    database_url: str,
    publish_callback: Optional[Callable[[ChangeEvent], None]] = None,
    tracked_tables: Optional[List[str]] = None,
    engine: Optional[Engine] = None,
) -> CDCOrchestrator:
    if engine is None:
        engine = create_engine(database_url)
    schema_handler = SchemaChangeHandler(engine)
    recovery_manager = OutageRecoveryManager(engine)
    connector = PostgreSQLCDCConnector(engine, schema_handler, recovery_manager)

    if tracked_tables:
        for table_name in tracked_tables:
            connector.register_table(table_name)

    orchestrator = CDCOrchestrator(connector, publish_callback)
    return orchestrator


class PostgreSQLCDCConnector(CDCConnector):
    def __init__(
        self,
        engine: Engine,
        schema_handler: SchemaChangeHandler,
        recovery_manager: OutageRecoveryManager,
        source_name: str = "postgresql",
        timestamp_column: str = "updated_at",
    ):
        super().__init__(engine, schema_handler, recovery_manager, source_name)
        self.timestamp_column = timestamp_column
        self._table_columns: Dict[str, List[str]] = {}
        self._table_pks: Dict[str, List[str]] = {}
        self.tombstone_detector = TombstoneDetector()

    def _get_table_info(self, table_name: str) -> tuple[List[str], List[str]]:
        if table_name in self._table_columns:
            return self._table_columns[table_name], self._table_pks[table_name]
        inspector = inspect(self.engine)
        columns = [col["name"] for col in inspector.get_columns(table_name)]
        pk_constraint = inspector.get_pk_constraint(table_name)
        primary_keys = list(pk_constraint.get("constrained_columns", []))
        self._table_columns[table_name] = columns
        self._table_pks[table_name] = primary_keys
        return columns, primary_keys

    def _record_key_from_row(self, row: Any, primary_keys: List[str]) -> str:
        values = [str(getattr(row, pk, "")) for pk in primary_keys]
        return "|".join(values) if values else str(hash(tuple(values)))

    def capture_changes(
        self,
        table_name: str,
        since: Optional[datetime] = None,
        limit: int = 1000,
    ) -> List[ChangeEvent]:
        if not self.is_tracked(table_name):
            raise TableNotTrackedError(f"Table {table_name} is not registered for CDC")

        columns, primary_keys = self._get_table_info(table_name)

        if self.timestamp_column not in columns:
            raise CDCError(
                f"Table {table_name} does not have '{self.timestamp_column}' column "
                f"required for polling-based CDC"
            )

        checkpoint = self.recovery_manager.get_checkpoint(table_name)
        since_dt = since
        if since_dt is None and checkpoint and checkpoint.get("last_processed_at"):
            since_dt = datetime.fromisoformat(checkpoint["last_processed_at"])
            if since_dt.tzinfo is None:
                since_dt = since_dt.replace(tzinfo=timezone.utc)

        with self.engine.begin() as conn:
            pk_list = ", ".join([f'"{pk}"' for pk in primary_keys])
            col_list = ", ".join([f'"{c}"' for c in columns])

            if since_dt is not None:
                query = text(
                    f"SELECT {col_list} FROM {table_name} "
                    f'WHERE "{self.timestamp_column}" > :since '
                    f'ORDER BY "{self.timestamp_column}" ASC LIMIT :limit'
                )
                params = {"since": since_dt, "limit": limit}
            else:
                query = text(
                    f"SELECT {col_list} FROM {table_name} "
                    f'ORDER BY "{self.timestamp_column}" ASC LIMIT :limit'
                )
                params = {"limit": limit}

            result = conn.execute(query, params)
            rows = result.fetchall()

        events: List[ChangeEvent] = []
        for row in rows:
            new_values = {col: getattr(row, col) for col in columns}
            record_key = self._record_key_from_row(row, primary_keys)
            tenant_id = str(new_values.get("tenant_id", "unknown"))
            changed_at = str(new_values.get(self.timestamp_column, ""))
            event_id = f"cdc_{table_name}_{record_key}_{changed_at}"

            event = ChangeEvent(
                event_id=event_id,
                table_name=table_name,
                operation=ChangeOperation.UPDATE,
                tenant_id=tenant_id,
                record_key=record_key,
                old_values=None,
                new_values=new_values,
                changed_at=changed_at,
                source_connector=self.source_name,
            )
            event = ChangeEvent(
                **{**event.to_dict(), "checksum": event.compute_checksum()}
            )
            events.append(event)

        if events:
            last_change = max(
                self._parse_aware(e.changed_at) for e in events
            )
            self.recovery_manager.update_checkpoint(
                table_name,
                last_processed_at=last_change,
                last_record_key=events[-1].record_key,
                events_processed=len(events),
            )

        # --- Tombstone delete detection ---
        current_keys = {
            self._record_key_from_row(row, primary_keys) for row in rows
        }
        self.tombstone_detector.update_seen(table_name, current_keys)
        deleted_keys = self.tombstone_detector.detect_deletes(
            table_name, current_keys
        )
        for record_key in deleted_keys:
            old_values = self.tombstone_detector.get_last_values(
                table_name, record_key
            )
            changed_at = datetime.now(timezone.utc).isoformat()
            event_id = f"cdc_delete_{table_name}_{record_key}_{changed_at}"
            tenant_id = "unknown"
            if old_values:
                tenant_id = str(old_values.get("tenant_id", "unknown"))
            tombstone_event = ChangeEvent(
                event_id=event_id,
                table_name=table_name,
                operation=ChangeOperation.DELETE,
                tenant_id=tenant_id,
                record_key=record_key,
                old_values=old_values,
                new_values=None,
                changed_at=changed_at,
                source_connector=self.source_name,
            )
            tombstone_event = ChangeEvent(
                **{
                    **tombstone_event.to_dict(),
                    "checksum": tombstone_event.compute_checksum(),
                }
            )
            events.append(tombstone_event)
            self.tombstone_detector.record_seen(
                table_name, record_key, old_values
            )

        return events

    def _parse_aware(self, value: str) -> datetime:
        """Parse a datetime string and ensure it is timezone-aware."""
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt

    def get_last_change_at(self, table_name: str) -> Optional[datetime]:
        if not self.is_tracked(table_name):
            return None
        columns, primary_keys = self._get_table_info(table_name)
        if self.timestamp_column not in columns:
            return None
        with self.engine.begin() as conn:
            query = text(
                f'SELECT MAX("{self.timestamp_column}") FROM {table_name}'
            )
            result = conn.execute(query).fetchone()
        if result and result[0]:
            val = result[0]
            if isinstance(val, str):
                return datetime.fromisoformat(val)
            return val
        return None


class PostgreSQLLogicalDecodingConnector(CDCConnector):
    """WAL-based CDC connector using PostgreSQL logical replication.

    Uses psycopg2 logical replication connections with the pg_output
    plugin to stream WAL changes in real time. Falls back gracefully
    when replication privileges are unavailable.
    """

    def __init__(
        self,
        source_name: str,
        connection_string: str,
        slot_name: str = "pesaguard_cdc_slot",
        plugin: str = "pg_output",
        poll_interval: float = 5.0,
        timestamp_column: str = "updated_at",
    ):
        if not HAS_PSYCOPG2:
            raise CDCError(
                "psycopg2 is required for PostgreSQLLogicalDecodingConnector"
            )

        self.source_name = source_name
        self.connection_string = connection_string
        self.slot_name = slot_name
        self.plugin = plugin
        self.poll_interval = poll_interval
        self.timestamp_column = timestamp_column
        self._tracked_tables: Set[str] = set()
        self._table_schemas: Dict[str, TableSchema] = {}
        self._replication_available: Optional[bool] = None
        self._initialized = False

    def _ensure_replication_available(self) -> bool:
        """Check whether the connected user has replication privileges."""
        if self._replication_available is not None:
            return self._replication_available

        try:
            conn = psycopg2.connect(self.connection_string)
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT rolreplication FROM pg_roles WHERE rolname = current_user"
                )
                row = cur.fetchone()
                self._replication_available = bool(row and row[0])
            conn.close()
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "Could not determine replication privileges: %s", exc
            )
            self._replication_available = False
        return self._replication_available

    def _create_replication_slot(self) -> None:
        """Create the logical replication slot if it does not exist."""
        try:
            conn = psycopg2.connect(self.connection_string)
            conn.autocommit = True
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT slot_name FROM pg_replication_slots WHERE slot_name = %s",
                    (self.slot_name,),
                )
                if cur.fetchone() is None:
                    cur.execute(
                        "SELECT pg_create_logical_replication_slot(%s, %s)",
                        (self.slot_name, self.plugin),
                    )
                    logger.info(
                        "Created logical replication slot %s with plugin %s",
                        self.slot_name,
                        self.plugin,
                    )
            conn.close()
        except psycopg2.errors.InsufficientPrivilege as exc:
            logger.warning(
                "Insufficient privileges to create replication slot: %s", exc
            )
            self._replication_available = False
        except Exception as exc:  # noqa: BLE001
            logger.warning("Could not create replication slot: %s", exc)
            self._replication_available = False

    def track_table(self, table_name: str) -> None:
        """Register a table for CDC tracking."""
        if table_name in self._tracked_tables:
            return
        self._tracked_tables.add(table_name)

        try:
            engine = create_engine(self.connection_string)
            inspector = inspect(engine)
            columns = {}
            for col in inspector.get_columns(table_name):
                columns[col["name"]] = str(col["type"])
            pks = inspector.get_pk_constraint(table_name).get(
                "constrained_columns", []
            )
            self._table_schemas[table_name] = TableSchema(
                table_name=table_name,
                columns=columns,
                primary_keys=pks,
                captured_at=datetime.now(timezone.utc).isoformat(),
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("Could not introspect table %s: %s", table_name, exc)

    def is_tracked(self, table_name: str) -> bool:
        return table_name in self._tracked_tables

    def _get_table_info(self, table_name: str) -> tuple:
        if table_name not in self._table_schemas:
            self.track_table(table_name)
        schema = self._table_schemas[table_name]
        return list(schema.columns.keys()), schema.primary_keys

    def _record_key_from_row(
        self, row: Dict[str, Any], primary_keys: List[str]
    ) -> str:
        key_parts = []
        for pk in primary_keys:
            val = row.get(pk)
            key_parts.append(str(val) if val is not None else "null")
        return "|".join(key_parts)

    def capture_changes(
        self,
        table_name: str,
        since: Optional[datetime] = None,
        limit: Optional[int] = None,
    ) -> List[ChangeEvent]:
        """Stream WAL changes via logical replication.

        Returns ChangeEvents for INSERT/UPDATE/DELETE operations observed
        since the last call. When replication privileges are unavailable,
        logs a warning and returns an empty list (graceful degradation).
        """
        if not self._ensure_replication_available():
            logger.warning(
                "Replication privileges unavailable for %s; returning empty change set",
                self.source_name,
            )
            return []

        if not self.is_tracked(table_name):
            self.track_table(table_name)

        self._create_replication_slot()

        events: List[ChangeEvent] = []
        repl_conn = None
        try:
            repl_conn = psycopg2.extras.LogicalReplicationConnection(
                dsn=self.connection_string
            )
            cur = repl_conn.cursor()
            cur.start_replication(
                slot_name=self.slot_name,
                decode=True,
            )

            msg = cur.read_message(timeout=self.poll_interval)
            while msg is not None and (limit is None or len(events) < limit):
                payload = msg.payload if hasattr(msg, "payload") else msg
                if isinstance(payload, str):
                    try:
                        payload = json.loads(payload)
                    except json.JSONDecodeError:
                        payload = {}
                if isinstance(payload, dict):
                    self._parse_replication_message(
                        payload, table_name, events
                    )
                msg = cur.read_message(timeout=self.poll_interval)

            cur.stop_replication()
            repl_conn.close()
        except psycopg2.errors.InsufficientPrivilege as exc:
            logger.warning(
                "Insufficient replication privileges: %s", exc
            )
            self._replication_available = False
        except Exception as exc:  # noqa: BLE001
            logger.warning("Logical decoding error: %s", exc)
        finally:
            if repl_conn is not None:
                try:
                    repl_conn.close()
                except Exception:  # noqa: BLE001
                    pass

        return events

    def _parse_replication_message(
        self,
        payload: Dict[str, Any],
        table_name: str,
        events: List[ChangeEvent],
    ) -> None:
        """Parse a pg_output JSON message into ChangeEvents."""
        try:
            kind = payload.get("kind")
            msg_table = payload.get("table") or payload.get("relation")
            if msg_table != table_name:
                return

            changed_at = datetime.now(timezone.utc).isoformat()
            columns = payload.get("columnnames", [])
            values = payload.get("columnvalues", [])
            row = dict(zip(columns, values)) if columns and values else {}

            if kind == "insert":
                new_values = row
                old_values = None
                operation = ChangeOperation.INSERT
            elif kind == "update":
                new_values = row
                old_values = None
                operation = ChangeOperation.UPDATE
            elif kind == "delete":
                new_values = None
                old_values = row
                operation = ChangeOperation.DELETE
            else:
                return

            schema = self._table_schemas.get(
                table_name,
                TableSchema(
                    table_name=table_name,
                    columns={},
                    primary_keys=[],
                    captured_at="",
                ),
            )
            record_key = self._record_key_from_row(
                row or old_values or {}, schema.primary_keys
            )

            tenant_id = "unknown"
            if old_values:
                tenant_id = str(old_values.get("tenant_id", "unknown"))
            elif new_values:
                tenant_id = str(new_values.get("tenant_id", "unknown"))

            event_id = (
                f"cdc_wal_{table_name}_{operation.value}_{record_key}_{changed_at}"
            )
            event = ChangeEvent(
                event_id=event_id,
                table_name=table_name,
                operation=operation,
                tenant_id=tenant_id,
                record_key=record_key,
                old_values=old_values,
                new_values=new_values,
                changed_at=changed_at,
                source_connector=self.source_name,
            )
            event = ChangeEvent(
                **{**event.to_dict(), "checksum": event.compute_checksum()}
            )
            events.append(event)
        except Exception as exc:  # noqa: BLE001
            logger.warning("Failed to parse replication message: %s", exc)

    def get_last_change_at(self, table_name: str) -> Optional[datetime]:
        """Return the last known change timestamp for a table.

        Logical decoding does not track timestamps natively, so we return
        None (callers should treat this as "no watermark available").
        """
        return None
