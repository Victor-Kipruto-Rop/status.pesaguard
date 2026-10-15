"""Opt-in tenant isolation tests against a migrated, non-bypass PostgreSQL role."""

from __future__ import annotations

import os
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

POSTGRES_TEST_URL = os.getenv("PESAGUARD_POSTGRES_TEST_URL", "")
pytestmark = pytest.mark.skipif(
    os.getenv("PESAGUARD_RUN_POSTGRES_INTEGRATION") != "1"
    or not os.getenv("PESAGUARD_POSTGRES_TEST_URL", "").startswith(("postgresql://", "postgres://")),
    reason="set PESAGUARD_RUN_POSTGRES_INTEGRATION=1 and PESAGUARD_POSTGRES_TEST_URL",
)


def test_every_tenant_bearing_public_table_has_forced_row_level_security():
    engine = create_engine(os.environ["PESAGUARD_POSTGRES_TEST_URL"])
    try:
        with engine.connect() as connection:
            rows = connection.execute(
                text(
                    """
                    SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                    FROM pg_class AS c
                    JOIN pg_namespace AS n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relkind IN ('r', 'p')
                      AND EXISTS (
                          SELECT 1 FROM pg_attribute AS a
                          WHERE a.attrelid = c.oid
                            AND a.attname = 'tenant_id'
                            AND a.attnum > 0
                            AND NOT a.attisdropped
                      )
                    ORDER BY c.relname
                    """
                )
            ).all()

        assert rows, "expected at least one tenant-bearing table"
        unprotected = [
            table_name
            for table_name, enabled, forced in rows
            if not enabled or not forced
        ]
        assert not unprotected, (
            "tenant-bearing tables must enable and force RLS: "
            + ", ".join(unprotected)
        )
    finally:
        engine.dispose()


def test_runtime_role_can_ingest_idempotent_transactions_without_delete_access():
    from event_store import EventStore, ProcessResult
    from models import AuditEvent, IdempotencyRecord, ProcessedTransaction, Transaction
    from tenant_context import tenant_scope
    from sqlalchemy.orm import sessionmaker

    engine = create_engine(POSTGRES_TEST_URL)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    tenant_id = f"ingest-role-{uuid.uuid4().hex}"
    transaction_id = f"ingest-role-{uuid.uuid4().hex}"
    payload = {
        "TransID": transaction_id,
        "TransAmount": "10.50",
        "Currency": "KES",
        "MSISDN": "254700000000",
        "BusinessShortCode": "123456",
        "TransTime": "20261015000000",
    }
    store = EventStore(database_url=POSTGRES_TEST_URL)

    try:
        with tenant_scope(tenant_id):
            assert store.mark_processed(payload, tenant_id=tenant_id) is ProcessResult.STORED
            assert store.mark_processed(payload, tenant_id=tenant_id) is ProcessResult.DUPLICATE

        with tenant_scope(tenant_id), session_factory() as session:
            assert session.query(Transaction).filter_by(
                tenant_id=tenant_id, trans_id=transaction_id
            ).count() == 1
            assert session.query(ProcessedTransaction).filter_by(
                tenant_id=tenant_id, daraja_trans_id=transaction_id
            ).one().webhook_attempt_number == 2
            assert session.query(IdempotencyRecord).filter_by(
                tenant_id=tenant_id, provider_transaction_id=transaction_id
            ).count() == 1
            assert session.query(AuditEvent).filter_by(
                tenant_id=tenant_id, event_type="transaction.accepted"
            ).count() == 1
            session.rollback()

        with engine.connect() as connection:
            operation_grants = connection.execute(
                text(
                    """
                    SELECT c.relname,
                           has_table_privilege(current_user, c.oid, 'SELECT'),
                           has_table_privilege(current_user, c.oid, 'INSERT'),
                           has_table_privilege(current_user, c.oid, 'UPDATE'),
                           has_table_privilege(current_user, c.oid, 'DELETE')
                    FROM pg_class AS c
                    WHERE c.oid = ANY(ARRAY[
                        'public.transactions'::regclass,
                        'public.processed_transactions'::regclass,
                        'public.transaction_events'::regclass,
                        'public.transaction_outbox'::regclass,
                        'public.idempotency_records'::regclass,
                        'public.audit_events'::regclass,
                        'public.transformation_records'::regclass,
                        'public.lineage_records'::regclass
                    ])
                    ORDER BY c.relname
                    """
                )
            ).all()
            assert operation_grants == [
                ("audit_events", True, True, False, False),
                ("idempotency_records", True, True, False, False),
                ("lineage_records", True, True, False, False),
                ("processed_transactions", True, True, True, False),
                ("transaction_events", True, True, False, False),
                ("transaction_outbox", True, True, True, False),
                ("transactions", True, True, True, False),
                ("transformation_records", True, True, False, False),
            ]
            assert not connection.execute(
                text(
                    "SELECT has_table_privilege(current_user, "
                    "'public.transactions', 'DELETE')"
                )
            ).scalar_one()
    finally:
        if store.engine is not None:
            store.engine.dispose()
        engine.dispose()


def test_postgres_rls_scopes_reads_writes_and_resets_pool_context():
    from models import WebhookConfig, WebhookDelivery
    from tenant_context import tenant_scope

    engine = create_engine(
        os.environ["PESAGUARD_POSTGRES_TEST_URL"],
        pool_size=1,
        max_overflow=0,
        pool_pre_ping=True,
    )
    sessions = sessionmaker(bind=engine, expire_on_commit=False)
    tenant_a = f"rls-a-{uuid.uuid4().hex[:12]}"
    tenant_b = f"rls-b-{uuid.uuid4().hex[:12]}"
    event_a = f"rls-event-a-{uuid.uuid4().hex}"
    event_b = f"rls-event-b-{uuid.uuid4().hex}"
    delivery_id = f"rls-delivery-{uuid.uuid4().hex}"

    try:
        with engine.connect() as connection:
            role = connection.execute(
                text(
                    "SELECT r.rolsuper, r.rolbypassrls "
                    "FROM pg_roles AS r WHERE r.rolname = current_user"
                )
            ).one()
            assert role == (False, False), "test role must not bypass row-level security"
            relation = connection.execute(
                text(
                    """
                    SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                    FROM pg_class AS c
                    WHERE c.oid = ANY(ARRAY[
                        'public.webhook_configs'::regclass,
                        'public.webhook_deliveries'::regclass
                    ])
                    ORDER BY c.relname
                    """
                )
            ).all()
            assert relation == [
                ("webhook_configs", True, True),
                ("webhook_deliveries", True, True),
            ], "apply the tenant RLS migration before this test"
            privileges = connection.execute(
                text(
                    """
                    SELECT
                        has_table_privilege(current_user, 'public.webhook_configs', 'SELECT'),
                        has_table_privilege(current_user, 'public.webhook_configs', 'INSERT'),
                        has_table_privilege(current_user, 'public.webhook_configs', 'UPDATE'),
                        has_table_privilege(current_user, 'public.webhook_configs', 'DELETE'),
                        has_table_privilege(current_user, 'public.webhook_deliveries', 'SELECT'),
                        has_table_privilege(current_user, 'public.webhook_deliveries', 'INSERT'),
                        has_table_privilege(current_user, 'public.webhook_deliveries', 'UPDATE'),
                        has_table_privilege(current_user, 'public.webhook_deliveries', 'DELETE')
                    """
                )
            ).one()
            assert privileges == (True, True, True, True, True, True, True, False)

        with tenant_scope(tenant_a), sessions() as session:
            session.add(WebhookConfig(id=event_a, tenant_id=tenant_a, url="https://example.test/a", event_types=[]))
            session.commit()
            webhook = session.query(WebhookConfig).filter_by(id=event_a).one()
            webhook.url = "https://example.test/a-updated"
            session.commit()
            delivery = WebhookDelivery(
                id=delivery_id,
                tenant_id=tenant_a,
                webhook_id=event_a,
                event_type="test",
                payload={"event": "test"},
                status="pending",
                attempt_count=0,
            )
            session.add(delivery)
            session.commit()
            delivery.status = "success"
            delivery.attempt_count = 1
            session.commit()

        with tenant_scope(tenant_b), sessions() as session:
            session.add(WebhookConfig(id=event_b, tenant_id=tenant_b, url="https://example.test/b", event_types=[]))
            session.commit()

        with tenant_scope(tenant_a), sessions() as session:
            assert session.query(WebhookConfig).filter_by(id=event_a).count() == 1
            assert session.query(WebhookConfig).filter_by(
                id=event_a, url="https://example.test/a-updated"
            ).count() == 1
            assert session.query(WebhookConfig).filter_by(id=event_b).count() == 0
            assert session.query(WebhookDelivery).filter_by(
                id=delivery_id, status="success", attempt_count=1
            ).count() == 1
            session.commit()
            with pytest.raises(DBAPIError):
                session.add(WebhookConfig(
                    id=f"rls-cross-write-{uuid.uuid4().hex}",
                    tenant_id=tenant_b,
                    url="https://example.test/cross",
                    event_types=[],
                ))
                session.commit()
            session.rollback()

        with sessions() as session:
            assert session.query(WebhookConfig).filter_by(id=event_a).count() == 0
            assert session.query(WebhookDelivery).filter_by(id=delivery_id).count() == 0
            with pytest.raises(DBAPIError):
                session.add(WebhookConfig(
                    id=f"rls-no-context-{uuid.uuid4().hex}",
                    tenant_id=tenant_a,
                    url="https://example.test/no-context",
                    event_types=[],
                ))
                session.commit()
            session.rollback()
            session.commit()

        with tenant_scope(tenant_b), sessions() as session:
            assert session.query(WebhookConfig).filter_by(id=event_b).count() == 1
            assert session.query(WebhookDelivery).filter_by(id=delivery_id).count() == 0
    finally:
        for tenant_id, event_id in ((tenant_a, event_a), (tenant_b, event_b)):
            with tenant_scope(tenant_id), sessions() as session:
                session.query(WebhookConfig).filter_by(id=event_id).delete()
                session.commit()
        engine.dispose()


def test_postgres_api_key_bootstrap_uses_restricted_lookup_function():
    engine = create_engine(os.environ["PESAGUARD_POSTGRES_TEST_URL"])
    try:
        with engine.connect() as connection:
            with pytest.raises(DBAPIError):
                connection.execute(text("SELECT id FROM api_key_records LIMIT 1")).all()
            connection.rollback()
            assert connection.execute(
                text("SELECT * FROM public.pesaguard_authenticate_api_key(:key_hash)"),
                {"key_hash": f"nonexistent-{uuid.uuid4().hex}"},
            ).all() == []
    finally:
        engine.dispose()


@pytest.mark.skipif(
    not os.getenv("PESAGUARD_TENANT_REGISTRY_DATABASE_URL", "").startswith(("postgresql://", "postgres://")),
    reason="set PESAGUARD_TENANT_REGISTRY_DATABASE_URL to test the registry-only role",
)
def test_postgres_registry_role_can_only_enumerate_tenant_ids():
    from tenant_registry import list_active_tenant_ids

    tenant_ids = list_active_tenant_ids()

    assert all(isinstance(tenant_id, str) and tenant_id for tenant_id in tenant_ids)
