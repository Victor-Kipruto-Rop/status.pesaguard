"""
Data lakehouse readiness tests (Phase 9, item 9).
"""
from __future__ import annotations
import os, sys, tempfile
import pytest
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from lakehouse import (
    BRONZE_LAYER, BronzeLayer, CompactionManager, GoldLayer, GOLD_LAYER, LakehouseConfig,
    LakehouseError, LakehouseOrchestrator, ParquetIO, PartitionSpec,
    SilverLayer, SnapshotManager, SILVER_LAYER, TableFormatManager, TableFormatType,
)
from lakehouse.compaction import CompactionResult
from lakehouse.partition_evolution import PartitionEvolutionPlan
from lakehouse.snapshot import SnapshotResult

@pytest.fixture
def warehouse_root():
    with tempfile.TemporaryDirectory() as tmp: yield tmp

@pytest.fixture
def config(warehouse_root): return LakehouseConfig(warehouse_root=warehouse_root)

@pytest.fixture
def orch(config): return LakehouseOrchestrator(config)

@pytest.fixture
def parquet_io(warehouse_root): return ParquetIO(warehouse_root)

def test_filesystem_object_storage_survives_reopen_and_deletes(parquet_io):
    data = b"test-content"
    parquet_io.store_object("tenant=a/source=b/file.parquet", data)
    reopened = ParquetIO(parquet_io.warehouse_root)
    assert reopened.get_object("tenant=a/source=b/file.parquet") == data
    assert "tenant=a/source=b/file.parquet" in reopened.list_objects("tenant=a/source=b/")
    assert reopened.delete_object("tenant=a/source=b/file.parquet") is True
    assert reopened.delete_object("tenant=a/source=b/file.parquet") is False
    assert reopened.list_objects("tenant=a/source=b/") == []


@pytest.mark.parametrize("unsafe_key", ["../outside", "/absolute/file", "tenant=foo/../../secret"])
def test_object_storage_rejects_path_traversal(parquet_io, unsafe_key):
    with pytest.raises(ValueError, match="relative POSIX path|unsafe path"):
        parquet_io.store_object(unsafe_key, b"must not escape warehouse")

def test_bronze_layer_object_storage(orch):
    result = orch.write_to_bronze(table_name="transactions", tenant_id="tenant-a", source="mpesa", records=[{"transaction_id": "txn-1", "amount": 100.0, "currency": "KES"}], schema_version="1.0")
    assert result.records_written == 1
    assert result.object_key.startswith("tenant=tenant-a/source=mpesa/")
    assert "/layer=bronze/" in result.object_key


def test_bronze_rejects_cross_tenant_record(orch):
    from lakehouse.bronze_layer import BronzeLayerError

    with pytest.raises(BronzeLayerError, match="does not match"):
        orch.write_to_bronze(
            "transactions",
            "tenant-a",
            "mpesa",
            [{"tenant_id": "tenant-b", "transaction_id": "cross-tenant"}],
        )


def test_silver_enforces_partition_tenant_and_enriches_context(orch):
    result = orch.write_to_silver(
        "transactions",
        "tenant-a",
        "mpesa",
        [{"transaction_id": "tx-good"}, {"tenant_id": "tenant-b", "transaction_id": "tx-bad"}],
    )
    assert result.records_written == 1
    assert result.records_rejected == 1
    silver = orch.get_layer(SILVER_LAYER).read("transactions", "tenant-a", "mpesa")
    assert silver.records[0]["tenant_id"] == "tenant-a"


def test_orchestrator_uses_distinct_layers_and_persists_bronze_data(config, orch):
    assert isinstance(orch.get_layer(BRONZE_LAYER), BronzeLayer)
    assert isinstance(orch.get_layer(SILVER_LAYER), SilverLayer)
    assert isinstance(orch.get_layer(GOLD_LAYER), GoldLayer)

    orch.write_to_bronze(
        table_name="transactions",
        tenant_id="tenant-a",
        source="mpesa",
        records=[{"transaction_id": "persisted", "amount": 100.0}],
    )
    reopened = LakehouseOrchestrator(config)
    result = reopened.read_from_bronze("transactions", "tenant-a", "mpesa")
    assert result.record_count == 1
    assert result.records[0]["_layer"] == "bronze"

def test_parquet_write_read_roundtrip(parquet_io):
    records = [{"id": 1, "amount": 100.5, "currency": "KES"}]
    pb = parquet_io.write_records(records, schema_version="1.0", table_name="test")
    rb = parquet_io.read_records(pb)
    assert len(rb) == 1
    assert rb[0]["_schema_version"] == "1.0"

def test_parquet_empty_records_raises(parquet_io):
    with pytest.raises(ValueError): parquet_io.write_records([], schema_version="1.0", table_name="test")

def test_iceberg_format_requires_catalog_backed_handler(config):
    m = TableFormatManager(config)
    from lakehouse.table_format import TableFormatError

    with pytest.raises(TableFormatError, match="catalog-backed handler"):
        m.create_table(
            table_name="iceberg_table",
            format_type=TableFormatType.ICEBERG,
            schema={"id": "string", "amount": "double"},
        )

def test_bronze_layer_write_read(orch):
    r = orch.write_to_bronze(table_name="events", tenant_id="tenant-b", source="webhook", records=[{"event_id": "e1", "type": "callback"}], schema_version="1.0")
    assert r.table_name == "events"
    rr = orch.read_from_bronze(table_name="events", tenant_id="tenant-b", source="webhook")
    assert rr.record_count == 1
    assert rr.records[0]["event_id"] == "e1"

def test_layer_constants():
    assert BRONZE_LAYER == "bronze"
    assert SILVER_LAYER == "silver"
    assert GOLD_LAYER == "gold"

def test_snapshot_create_and_retrieve(parquet_io):
    m = SnapshotManager(parquet_io)
    r = m.create_snapshot(table_name="transactions", object_keys=["k1", "k2"], record_count=100, total_size_bytes=5000, schema_version="1.0")
    from lakehouse.snapshot import SnapshotResult
    assert isinstance(r, SnapshotResult)
    s = m.get_snapshot("transactions", r.snapshot_id)
    assert s is not None
    assert s.record_count == 100

def test_snapshot_list_and_latest(parquet_io):
    m = SnapshotManager(parquet_io)
    for i in range(3):
        m.create_snapshot(table_name="table1", object_keys=[f"k{i}"], record_count=i * 10, total_size_bytes=i * 100, schema_version="1.0")
    assert len(m.list_snapshots("table1")) == 3
    l = m.get_latest_snapshot("table1")
    assert l.record_count == 20

def test_snapshot_max_retention(parquet_io):
    m = SnapshotManager(parquet_io, max_snapshots=2)
    for i in range(5):
        m.create_snapshot(table_name="table1", object_keys=[f"k{i}"], record_count=i, total_size_bytes=i, schema_version="1.0")
    assert len(m.list_snapshots("table1")) == 2

def test_schema_evolution_compatible(config):
    m = TableFormatManager(config)
    m.create_table(table_name="st", format_type=TableFormatType.PARQUET, schema={"id": "string"}, version="1.0")
    u = m.update_schema(table_name="st", new_schema={"id": "string"})
    assert u.schema["id"] == "string"

def test_schema_evolution_parquet_rejects_type_change(config):
    m = TableFormatManager(config)
    m.create_table(table_name="ps", format_type=TableFormatType.PARQUET, schema={"amount": "int"}, version="1.0")
    with pytest.raises(ValueError): m.update_schema(table_name="ps", new_schema={"amount": "double"})

def test_table_not_found_error(config):
    m = TableFormatManager(config)
    with pytest.raises(LakehouseError): m.get_table("nonexistent")

def test_partition_evolution_plan_and_execute(orch):
    from lakehouse.lakehouse_types import PartitionSpec
    orch.partition_manager.register_table_partition("transactions", PartitionSpec(partition_fields=["tenant_id", "year", "month", "day"], transforms={"year": "year", "month": "month", "day": "day"}))
    e = orch.plan_partition_evolution(table_name="transactions", new_spec=PartitionSpec(partition_fields=["tenant_id", "year", "month", "day", "hour"], transforms={"year": "year", "month": "month", "day": "day", "hour": "hour"}))
    from lakehouse.partition_evolution import PartitionEvolutionPlan
    assert isinstance(e, PartitionEvolutionPlan)
    assert e.status == "planned"
    assert len(e.new_spec.partition_fields) == 5
    ex = orch.execute_partition_evolution(e.evolution_id)
    assert ex.status == "completed"
    cur = orch.partition_manager.get_table_partition("transactions")
    assert len(cur.partition_fields) == 5

def test_partition_evolution_rollback(orch):
    from lakehouse.lakehouse_types import PartitionSpec
    orch.partition_manager.register_table_partition("transactions", PartitionSpec(partition_fields=["tenant_id", "year", "month", "day"], transforms={"year": "year", "month": "month", "day": "day"}))
    e = orch.plan_partition_evolution(table_name="transactions", new_spec=PartitionSpec(partition_fields=["tenant_id", "year"], transforms={"year": "year", "month": "month"}))
    rb = orch.rollback_partition_evolution(e.evolution_id)
    assert rb.status == "rolled_back"

def test_partition_evolution_list(orch):
    from lakehouse.lakehouse_types import PartitionSpec
    orch.partition_manager.register_table_partition("table1", PartitionSpec(partition_fields=["tenant_id"], transforms={}))
    orch.partition_manager.register_table_partition("table2", PartitionSpec(partition_fields=["tenant_id"], transforms={}))
    e1 = orch.plan_partition_evolution(table_name="table1", new_spec=PartitionSpec(partition_fields=["tenant_id", "year"], transforms={}))
    e2 = orch.plan_partition_evolution(table_name="table2", new_spec=PartitionSpec(partition_fields=["tenant_id", "month"], transforms={}))
    assert len(orch.partition_manager.list_evolutions()) == 2
    t1 = orch.partition_manager.list_evolutions(table_name="table1")
    assert len(t1) == 1
    assert t1[0].evolution_id == e1.evolution_id

def test_snapshot_create_and_retrieve(parquet_io):
    m = SnapshotManager(parquet_io)
    r = m.create_snapshot(table_name="transactions", object_keys=["k1", "k2"], record_count=100, total_size_bytes=5000, schema_version="1.0")
    from lakehouse.snapshot import SnapshotResult
    assert isinstance(r, SnapshotResult)
    s = m.get_snapshot("transactions", r.snapshot_id)
    assert s is not None
    assert s.record_count == 100

def test_snapshot_list_and_latest(parquet_io):
    m = SnapshotManager(parquet_io)
    for i in range(3):
        m.create_snapshot(table_name="table1", object_keys=[f"k{i}"], record_count=i * 10, total_size_bytes=i * 100, schema_version="1.0")
    assert len(m.list_snapshots("table1")) == 3
    l = m.get_latest_snapshot("table1")
    assert l.record_count == 20

def test_snapshot_max_retention(parquet_io):
    m = SnapshotManager(parquet_io, max_snapshots=2)
    for i in range(5):
        m.create_snapshot(table_name="table1", object_keys=[f"k{i}"], record_count=i, total_size_bytes=i, schema_version="1.0")
    assert len(m.list_snapshots("table1")) == 2

def test_schema_evolution_compatible(config):
    m = TableFormatManager(config)
    m.create_table(table_name="st", format_type=TableFormatType.PARQUET, schema={"id": "string"}, version="1.0")
    u = m.update_schema(table_name="st", new_schema={"id": "string"})
    assert u.schema["id"] == "string"

def test_schema_evolution_parquet_rejects_type_change(config):
    m = TableFormatManager(config)
    m.create_table(table_name="ps", format_type=TableFormatType.PARQUET, schema={"amount": "int"}, version="1.0")
    with pytest.raises(ValueError): m.update_schema(table_name="ps", new_schema={"amount": "double"})

def test_table_not_found_error(config):
    m = TableFormatManager(config)
    with pytest.raises(LakehouseError): m.get_table("nonexistent")

def test_partition_evolution_plan_and_execute(orch):
    from lakehouse.lakehouse_types import PartitionSpec
    orch.partition_manager.register_table_partition("transactions", PartitionSpec(partition_fields=["tenant_id", "year", "month", "day"], transforms={"year": "year", "month": "month", "day": "day"}))
    e = orch.plan_partition_evolution(table_name="transactions", new_spec=PartitionSpec(partition_fields=["tenant_id", "year", "month", "day", "hour"], transforms={"year": "year", "month": "month", "day": "day", "hour": "hour"}))
    from lakehouse.partition_evolution import PartitionEvolutionPlan
    assert isinstance(e, PartitionEvolutionPlan)
    assert e.status == "planned"
    assert len(e.new_spec.partition_fields) == 5
    ex = orch.execute_partition_evolution(e.evolution_id)
    assert ex.status == "completed"
    cur = orch.partition_manager.get_table_partition("transactions")
    assert len(cur.partition_fields) == 5

def test_partition_evolution_rollback(orch):
    from lakehouse.lakehouse_types import PartitionSpec
    orch.partition_manager.register_table_partition("transactions", PartitionSpec(partition_fields=["tenant_id", "year", "month", "day"], transforms={"year": "year", "month": "month", "day": "day"}))
    e = orch.plan_partition_evolution(table_name="transactions", new_spec=PartitionSpec(partition_fields=["tenant_id", "year"], transforms={"year": "year", "month": "month"}))
    rb = orch.rollback_partition_evolution(e.evolution_id)
    assert rb.status == "rolled_back"

def test_partition_evolution_list(orch):
    from lakehouse.lakehouse_types import PartitionSpec
    orch.partition_manager.register_table_partition("table1", PartitionSpec(partition_fields=["tenant_id"], transforms={}))
    orch.partition_manager.register_table_partition("table2", PartitionSpec(partition_fields=["tenant_id"], transforms={}))
    e1 = orch.plan_partition_evolution(table_name="table1", new_spec=PartitionSpec(partition_fields=["tenant_id", "year"], transforms={}))
    e2 = orch.plan_partition_evolution(table_name="table2", new_spec=PartitionSpec(partition_fields=["tenant_id", "month"], transforms={}))
    assert len(orch.partition_manager.list_evolutions()) == 2
    t1 = orch.partition_manager.list_evolutions(table_name="table1")
    assert len(t1) == 1
    assert t1[0].evolution_id == e1.evolution_id

def test_compaction_merges_small_files(parquet_io, warehouse_root):
    m = CompactionManager(parquet_io, threshold_files=2, threshold_size_mb=1)
    b1 = parquet_io.write_records([{"a": 1}], schema_version="1.0", table_name="t")
    b2 = parquet_io.write_records([{"a": 2}], schema_version="1.0", table_name="t")
    k1 = "tenant=a/source=b/day=1/file1.parquet"
    k2 = "tenant=a/source=b/day=1/file2.parquet"
    parquet_io.store_object(k1, b1)
    parquet_io.store_object(k2, b2)
    r = m.compact_partition(table_name="t", partition_path="tenant=a/source=b/day=1", object_keys=[k1, k2])
    from lakehouse.compaction import CompactionResult
    assert isinstance(r, CompactionResult)
    assert r.input_files == 2
    assert r.output_files == 1
    assert r.records_compacted == 2
    assert len(parquet_io.list_objects("tenant=a/source=b/day=1/")) == 1

def test_compaction_should_compact_thresholds(orch):
    m = orch.compaction_manager
    assert m.should_compact("t", "p", file_count=5, total_size_bytes=100) is False
    # 15 files >= 10 threshold, 2000 MB >= 1024 MB threshold
    assert m.should_compact("t", "p", file_count=15, total_size_bytes=2000 * 1024 * 1024) is True
    assert m.should_compact("t", "p", file_count=15, total_size_bytes=10) is False

def test_analytical_query_execution(orch):
    orch.write_to_gold(table_name="transactions", tenant_id="tenant-a", source="mpesa", records=[{"transaction_id": "txn-1", "amount": 100.0, "currency": "KES"}, {"transaction_id": "txn-2", "amount": 200.0, "currency": "USD"}], schema_version="1.0")
    r = orch.execute_analytical_query(query_id="q1", table_name="transactions", tenant_id="tenant-a", filters={"currency": "KES"}, limit=10)
    assert r.source == "lakehouse"
    assert r.row_count == 1
    assert r.rows[0]["currency"] == "KES"


def test_analytical_query_reads_only_requested_layer(orch):
    orch.write_to_bronze(
        "transactions", "tenant-a", "mpesa", [{"transaction_id": "bronze", "amount": 1}]
    )
    orch.write_to_gold(
        "transactions", "tenant-a", "mpesa", [{"transaction_id": "gold", "amount": 2}]
    )
    result = orch.execute_analytical_query(
        "gold-query", "transactions", "tenant-a", layer_name=GOLD_LAYER
    )
    assert [row["transaction_id"] for row in result.rows] == ["gold"]

def test_analytical_query_metrics(orch):
    orch.execute_analytical_query(query_id="q1", table_name="transactions", tenant_id="tenant-a")
    orch.execute_analytical_query(query_id="q2", table_name="transactions", tenant_id="tenant-a")
    met = orch.analytics_engine.get_query_metrics()
    assert met["total_queries"] == 2

def test_orchestrator_get_metrics(orch):
    orch.create_table(table_name="mt", format_type=TableFormatType.PARQUET, schema={"id": "string"}, version="1.0")
    met = orch.get_metrics()
    assert met["table_count"] >= 1
    assert "analytics" in met

def test_schema_evolution_module_importable():
    from schema_evolution import assess_schema_compatibility
    comp, note = assess_schema_compatibility("1.0", "1.0")
    assert comp == "compatible"
    assert note
