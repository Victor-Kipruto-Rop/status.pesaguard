"""Opt-in PostgreSQL tests for scheduler RLS and registry-role boundaries."""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker


POSTGRES_TEST_URL = os.getenv("PESAGUARD_POSTGRES_TEST_URL", "")
REGISTRY_DATABASE_URL = os.getenv("PESAGUARD_TENANT_REGISTRY_DATABASE_URL", "")
pytestmark = pytest.mark.skipif(
    os.getenv("PESAGUARD_RUN_POSTGRES_INTEGRATION") != "1"
    or not POSTGRES_TEST_URL.startswith(("postgresql://", "postgres://")),
    reason=(
        "set PESAGUARD_RUN_POSTGRES_INTEGRATION=1 and "
        "PESAGUARD_POSTGRES_TEST_URL to a migrated disposable PostgreSQL database"
    ),
)


def test_scheduler_workflow_tables_enforce_forced_rls_and_tenant_local_fks():
    from models import (
        ScheduledJob,
        ScheduledJobBackfill,
        ScheduledJobDependency,
        ScheduledJobRun,
    )
    from scheduler import Scheduler
    from tenant_context import tenant_scope

    engine = create_engine(
        POSTGRES_TEST_URL,
        pool_size=1,
        max_overflow=0,
        pool_pre_ping=True,
    )
    sessions = sessionmaker(bind=engine, expire_on_commit=False)
    suffix = uuid.uuid4().hex
    tenant_a = f"orchestration-a-{suffix[:12]}"
    tenant_b = f"orchestration-b-{suffix[:12]}"
    parent_key = f"parent-{suffix}"
    child_key = f"child-{suffix}"
    run_id = f"workflow-run-{suffix}"
    backfill_key = f"backfill-{suffix}"
    scheduled_for = datetime.now(timezone.utc) + timedelta(days=1)

    try:
        with engine.connect() as connection:
            role = connection.execute(
                text(
                    "SELECT r.rolsuper, r.rolbypassrls "
                    "FROM pg_roles AS r WHERE r.rolname = current_user"
                )
            ).one()
            assert role == (False, False), "test URL must use a non-bypass runtime role"
            table_security = connection.execute(
                text(
                    """
                    SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                    FROM pg_class AS c
                    WHERE c.oid = ANY(ARRAY[
                        'public.scheduled_job_runs'::regclass,
                        'public.scheduled_job_dependencies'::regclass,
                        'public.scheduled_job_backfills'::regclass
                    ])
                    ORDER BY c.relname
                    """
                )
            ).all()
            assert table_security == [
                ("scheduled_job_backfills", True, True),
                ("scheduled_job_dependencies", True, True),
                ("scheduled_job_runs", True, True),
            ]
            scheduler_table_grants = connection.execute(
                text(
                    """
                    SELECT table_name, has_table_privilege(
                        current_user,
                        format('public.%I', table_name),
                        'SELECT,INSERT,UPDATE'
                    )
                    FROM unnest(ARRAY[
                        'scheduled_jobs',
                        'scheduled_job_runs',
                        'scheduled_job_dependencies',
                        'scheduled_job_backfills'
                    ]) AS table_name
                    ORDER BY table_name
                    """
                )
            ).all()
            assert scheduler_table_grants == [
                ("scheduled_job_backfills", True),
                ("scheduled_job_dependencies", True),
                ("scheduled_job_runs", True),
                ("scheduled_jobs", True),
            ]

        with tenant_scope(tenant_a), sessions() as session:
            scheduler = Scheduler(session)
            parent = scheduler.register(
                tenant_a,
                parent_key,
                "test.task",
                3600,
                first_run_at=scheduled_for,
            )
            child = scheduler.register(
                tenant_a,
                child_key,
                "test.task",
                3600,
                first_run_at=scheduled_for,
            )
            session.flush()
            scheduler.add_dependency(tenant_a, child_key, parent_key)
            backfill = scheduler.enqueue_backfill(
                tenant_a,
                child_key,
                scheduled_for,
                scheduled_for + timedelta(hours=1),
                idempotency_key=backfill_key,
                requested_by="postgres-integration-test",
                reason="Verify tenant-scoped scheduler RLS",
            )
            session.add(
                ScheduledJobRun(
                    id=run_id,
                    job_id=child.id,
                    tenant_id=tenant_a,
                    job_key=child_key,
                    scheduled_for=scheduled_for,
                    attempt_number=1,
                    status="succeeded",
                    worker_id="postgres-integration-test",
                    started_at=scheduled_for,
                    finished_at=scheduled_for,
                    trigger_type="backfill",
                    backfill_id=backfill.id,
                )
            )
            session.commit()
            parent_id, child_id, backfill_id = parent.id, child.id, backfill.id

        with tenant_scope(tenant_a), sessions() as session:
            assert session.get(ScheduledJob, child_id) is not None
            assert session.get(ScheduledJobDependency, (tenant_a, child_id, parent_id))
            assert session.get(ScheduledJobBackfill, backfill_id) is not None
            assert session.get(ScheduledJobRun, run_id) is not None

        # Reuse the single pooled connection under another tenant and with no
        # context. Transaction-local settings must not leak across checkouts.
        with tenant_scope(tenant_b), sessions() as session:
            assert session.get(ScheduledJob, child_id) is None
            assert session.get(ScheduledJobDependency, (tenant_a, child_id, parent_id)) is None
            assert session.get(ScheduledJobBackfill, backfill_id) is None
            assert session.get(ScheduledJobRun, run_id) is None
            session.rollback()

        with sessions() as session:
            assert session.get(ScheduledJob, child_id) is None
            session.rollback()
            session.add(
                ScheduledJobBackfill(
                    id=f"unscoped-backfill-{suffix}",
                    tenant_id=tenant_a,
                    job_id=child_id,
                    job_key=child_key,
                    start_at=scheduled_for,
                    end_at=scheduled_for + timedelta(hours=1),
                    idempotency_key=f"unscoped-{suffix}",
                    requested_by="postgres-integration-test",
                    reason="Must be rejected without tenant context",
                )
            )
            with pytest.raises(DBAPIError):
                session.commit()
            session.rollback()

        with tenant_scope(tenant_b), sessions() as session:
            session.add(
                ScheduledJobBackfill(
                    id=f"cross-tenant-backfill-{suffix}",
                    tenant_id=tenant_a,
                    job_id=child_id,
                    job_key=child_key,
                    start_at=scheduled_for,
                    end_at=scheduled_for + timedelta(hours=1),
                    idempotency_key=f"cross-tenant-{suffix}",
                    requested_by="postgres-integration-test",
                    reason="Must be rejected for a different tenant",
                )
            )
            with pytest.raises(DBAPIError):
                session.commit()
            session.rollback()

    finally:
        engine.dispose()


@pytest.mark.skipif(
    not REGISTRY_DATABASE_URL.startswith(("postgresql://", "postgres://")),
    reason="set PESAGUARD_TENANT_REGISTRY_DATABASE_URL to the isolated registry-role URL",
)
def test_scheduler_registry_role_can_enumerate_tenants_but_not_business_rows():
    engine = create_engine(REGISTRY_DATABASE_URL, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            assert connection.execute(
                text("SELECT tenant_id, status FROM public.iam_tenants LIMIT 0")
            ).all() == []
            with pytest.raises(DBAPIError):
                connection.execute(
                    text("SELECT tenant_id FROM public.scheduled_jobs LIMIT 1")
                ).all()
            connection.rollback()
    finally:
        engine.dispose()
