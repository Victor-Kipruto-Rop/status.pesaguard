"""Tests for the version-controlled transformation resource registry."""

import os
import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base
from transformation_registry import (
    DependencyCycleError,
    RegistryError,
    ResourceNotFound,
    TransformationRegistry,
)


@pytest.fixture()
def registry():
    url = os.environ["DATABASE_URL"]
    engine = create_engine(
        url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {}
    )
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    yield TransformationRegistry(factory)
    engine.dispose()


def _tenant() -> str:
    return f"tenant-{uuid.uuid4().hex[:10]}"


def test_create_and_get_roundtrip_with_version_one(registry):
    tenant = _tenant()
    created = registry.create(
        tenant_id=tenant,
        resource_type="dataset",
        name="daily_statement",
        definition={"inputs": ["statement"], "steps": [], "output": "statement"},
        description="daily bank statement prep",
        tags=["daily", "bank"],
    )
    assert created.version == 1
    assert len(created.definition_hash) == 64
    fetched = registry.get(tenant_id=tenant, name="daily_statement")
    assert fetched.definition["output"] == "statement"
    assert fetched.description == "daily bank statement prep"
    assert fetched.tags == ["daily", "bank"]


def test_duplicate_create_is_rejected(registry):
    tenant = _tenant()
    payload = {"resource_type": "component", "name": "dedupe", "definition": {"steps": []}}
    registry.create(tenant_id=tenant, **payload)
    with pytest.raises(RegistryError, match="already exists"):
        registry.create(tenant_id=tenant, **payload)


def test_versions_are_immutable_and_retrievable(registry):
    tenant = _tenant()
    registry.create(tenant_id=tenant, resource_type="dataset", name="vds", definition={"v": 1})
    second = registry.save_version(tenant_id=tenant, name="vds", definition={"v": 2})
    third = registry.save_version(tenant_id=tenant, name="vds", definition={"v": 3})
    assert (second.version, third.version) == (2, 3)
    assert registry.get(tenant_id=tenant, name="vds").definition == {"v": 3}
    assert registry.get(tenant_id=tenant, name="vds", version=1).definition == {"v": 1}
    history = registry.history(tenant_id=tenant, name="vds")
    assert [entry.version for entry in history] == [3, 2, 1]
    with pytest.raises(ResourceNotFound, match="version 99"):
        registry.get(tenant_id=tenant, name="vds", version=99)


def test_validator_failure_prevents_persistence(registry):
    tenant = _tenant()

    def reject(definition):
        raise RegistryError("definition rejected by validator")

    with pytest.raises(RegistryError, match="rejected by validator"):
        registry.create(
            tenant_id=tenant,
            resource_type="dataset",
            name="bad",
            definition={"steps": []},
            validator=reject,
        )
    with pytest.raises(ResourceNotFound):
        registry.get(tenant_id=tenant, name="bad")


def test_search_filters_type_query_tags_and_tenant(registry):
    tenant = _tenant()
    other = _tenant()
    registry.create(
        tenant_id=tenant,
        resource_type="dataset",
        name="equity_statement",
        definition={"steps": []},
        description="equity daily import",
        tags=["daily", "equity"],
    )
    registry.create(
        tenant_id=tenant,
        resource_type="component",
        name="equity_statement",
        definition={"steps": []},
    )
    registry.create(
        tenant_id=tenant,
        resource_type="dataset",
        name="kcb_statement",
        definition={"steps": []},
        tags=["weekly"],
    )
    registry.create(
        tenant_id=other,
        resource_type="dataset",
        name="equity_statement",
        definition={"steps": []},
    )

    by_type = registry.search(tenant_id=tenant, resource_type="component")
    assert [item.name for item in by_type] == ["equity_statement"]

    by_query = registry.search(tenant_id=tenant, query="equity daily")
    assert [item.name for item in by_query] == ["equity_statement"]

    by_tag = registry.search(tenant_id=tenant, tags=["daily"])
    assert [item.name for item in by_tag] == ["equity_statement"]

    # Tenant isolation: the other tenant's identically named resource is invisible.
    isolated = registry.search(tenant_id=other)
    assert len(isolated) == 1
    assert isolated[0].name == "equity_statement"
    with pytest.raises(RegistryError, match="multiple types"):
        registry.get(tenant_id=tenant, name="equity_statement")
    assert (
        registry.get(
            tenant_id=tenant, name="equity_statement", resource_type="dataset"
        ).version
        == 1
    )


def test_dependency_resolution_orders_dependencies_first(registry):
    tenant = _tenant()
    for name, deps in (
        ("base", []),
        ("enriched", ["base"]),
        ("fx", []),
        ("final", ["enriched", "fx"]),
    ):
        registry.create(
            tenant_id=tenant,
            resource_type="dataset",
            name=name,
            definition={"depends_on": deps, "steps": []},
        )
    order = registry.resolve_execution_order(tenant_id=tenant, root="final")
    assert set(order) == {"base", "enriched", "fx", "final"}
    assert order.index("base") < order.index("enriched") < order.index("final")
    assert order.index("fx") < order.index("final")


def test_dependency_cycles_and_missing_dependencies_are_rejected(registry):
    tenant = _tenant()
    registry.create(
        tenant_id=tenant, resource_type="dataset", name="a", definition={"depends_on": ["b"]}
    )
    registry.create(
        tenant_id=tenant, resource_type="dataset", name="b", definition={"depends_on": ["a"]}
    )
    with pytest.raises(DependencyCycleError, match="cycle detected"):
        registry.resolve_execution_order(tenant_id=tenant, root="a")

    registry.create(
        tenant_id=tenant,
        resource_type="dataset",
        name="lonely",
        definition={"depends_on": ["ghost"]},
    )
    with pytest.raises(ResourceNotFound):
        registry.resolve_execution_order(tenant_id=tenant, root="lonely")


def test_input_validation_is_explicit(registry):
    tenant = _tenant()
    with pytest.raises(RegistryError, match="tenant_id is required"):
        registry.create(tenant_id="", resource_type="dataset", name="x", definition={})
    with pytest.raises(RegistryError, match="unsupported resource type"):
        registry.create(tenant_id=tenant, resource_type="dashboard", name="x", definition={})
    with pytest.raises(RegistryError, match="resource name is required"):
        registry.create(tenant_id=tenant, resource_type="dataset", name="", definition={})
    with pytest.raises(RegistryError, match="definition must be an object"):
        registry.create(tenant_id=tenant, resource_type="dataset", name="x", definition=[])
    with pytest.raises(RegistryError, match="depends_on must be a list"):
        registry.create(
            tenant_id=tenant,
            resource_type="dataset",
            name="x",
            definition={"depends_on": "y"},
        )