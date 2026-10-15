from __future__ import annotations

import asyncio

import pytest

from tenant_context import (
    _set_transaction_local_tenant,
    current_tenant_id,
    tenant_scope,
)


def test_tenant_scope_is_nested_and_restores_on_exit():
    assert current_tenant_id() is None

    with tenant_scope("tenant-a"):
        assert current_tenant_id() == "tenant-a"
        with tenant_scope("tenant-b"):
            assert current_tenant_id() == "tenant-b"
        assert current_tenant_id() == "tenant-a"

    assert current_tenant_id() is None


def test_tenant_scope_restores_after_exception():
    with pytest.raises(RuntimeError, match="request failed"):
        with tenant_scope("tenant-a"):
            raise RuntimeError("request failed")

    assert current_tenant_id() is None


def test_tenant_scope_rejects_malformed_tenant_id():
    with pytest.raises(ValueError, match="invalid format"):
        with tenant_scope("tenant a"):
            pass

    assert current_tenant_id() is None


def test_async_tasks_keep_independent_tenant_contexts():
    async def read_in_scope(tenant_id: str) -> str | None:
        with tenant_scope(tenant_id):
            await asyncio.sleep(0)
            return current_tenant_id()

    async def run():
        return await asyncio.gather(read_in_scope("tenant-a"), read_in_scope("tenant-b"))

    assert asyncio.run(run()) == ["tenant-a", "tenant-b"]
    assert current_tenant_id() is None


def test_postgres_begin_listener_sets_or_clears_the_transaction_local_tenant():
    class Connection:
        dialect = type("Dialect", (), {"name": "postgresql"})()

        def __init__(self):
            self.executions = []

        def execute(self, statement, parameters):
            self.executions.append((str(statement), parameters))

    connection = Connection()
    with tenant_scope("tenant-a"):
        _set_transaction_local_tenant(connection)
    _set_transaction_local_tenant(connection)

    assert [parameters["tenant_id"] for _, parameters in connection.executions] == [
        "tenant-a",
        "",
    ]
    assert all("set_config('app.tenant_id'" in statement for statement, _ in connection.executions)
