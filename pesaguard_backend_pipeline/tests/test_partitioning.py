from datetime import datetime, timezone

import pytest

from partitioning import event_partition_key, raw_object_partition, transaction_query_dimensions


def test_raw_objects_use_tenant_source_and_date_partitions():
    assert raw_object_partition("tenant-a", "bank", "2026-09-23T10:00:00Z") == "tenant=tenant-a/source=bank/year=2026/month=09/day=23"


def test_raw_object_partition_encodes_tenant_colons_for_filesystem_safety():
    result = raw_object_partition("tenant:region", "bank", "2026-09-23T10:00:00Z")
    assert result.startswith("tenant=tenant%3Aregion/source=bank/")


@pytest.mark.parametrize("tenant_id", ["../tenant", "tenant/a", "tenant\\a", ""])
def test_raw_object_partition_rejects_invalid_tenant_ids(tenant_id):
    with pytest.raises(ValueError, match="tenant_id"):
        raw_object_partition(tenant_id, "bank", "2026-09-23T10:00:00Z")


@pytest.mark.parametrize(
    ("source", "encoded"),
    [
        ("bank", "bank"),
        ("provider:account", "provider%3Aaccount"),
        ("../private", "..%2Fprivate"),
        (r"provider\account", "provider%5Caccount"),
    ],
)
def test_raw_object_partition_encodes_source_as_one_path_component(source, encoded):
    result = raw_object_partition("tenant-a", source, "2026-09-23T10:00:00Z")
    assert result.startswith(f"tenant=tenant-a/source={encoded}/")
    assert result.count("/") == 4


def test_raw_object_partition_rejects_empty_or_unbounded_source():
    with pytest.raises(ValueError, match="source"):
        raw_object_partition("tenant-a", " ")
    with pytest.raises(ValueError, match="source"):
        raw_object_partition("tenant-a", "s" * 256)


def test_event_key_preserves_tenant_provider_and_transaction_ordering():
    assert event_partition_key({
        "tenant_id": "tenant-a",
        "source": "mpesa",
        "aggregate_id": "txn-1",
    }) == "tenant-a:mpesa:txn-1"


def test_query_dimensions_are_explicit_and_bounded():
    dimensions = transaction_query_dimensions(
        tenant_id="tenant-a",
        start=datetime(2026, 9, 1, tzinfo=timezone.utc),
        end=datetime(2026, 10, 1, tzinfo=timezone.utc),
        provider="MPESA",
    )
    assert dimensions["provider"] == "mpesa"
    assert dimensions["created_at_start"] < dimensions["created_at_end"]