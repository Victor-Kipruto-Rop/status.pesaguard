from __future__ import annotations

from types import SimpleNamespace

from communications import worker
from tenant_context import current_tenant_id


class _PostgresSession:
    def get_bind(self):
        return SimpleNamespace(dialect=SimpleNamespace(name="postgresql"))


class _TenantSession:
    def __init__(self, bind):
        self.bind = bind
        self.closed = False

    def close(self):
        self.closed = True


def test_postgres_worker_scopes_tenants_and_obeys_one_global_batch_limit(monkeypatch):
    monkeypatch.setattr(worker, "Session", _TenantSession)
    monkeypatch.setattr(
        "tenant_registry.list_active_tenant_ids",
        lambda: ["tenant-a", "tenant-b", "tenant-c"],
    )
    invocations = []

    def process(session, **kwargs):
        invocations.append(
            (
                current_tenant_id(),
                kwargs["tenant_id"],
                kwargs["limit"],
                session.closed,
            )
        )
        return min(2, kwargs["limit"])

    monkeypatch.setattr(worker, "_process_outbox_in_scope", process)

    processed = worker.process_outbox_once(_PostgresSession(), limit=3)

    assert processed == 3
    assert invocations == [
        ("tenant-a", "tenant-a", 3, False),
        ("tenant-b", "tenant-b", 1, False),
    ]
    assert current_tenant_id() is None


def test_postgres_worker_does_not_claim_for_nonpositive_batch_limit(monkeypatch):
    monkeypatch.setattr(
        "tenant_registry.list_active_tenant_ids",
        lambda: (_ for _ in ()).throw(AssertionError("must not enumerate tenants")),
    )

    assert worker.process_outbox_once(_PostgresSession(), limit=0) == 0
    assert worker.process_outbox_once(_PostgresSession(), limit=-1) == 0
