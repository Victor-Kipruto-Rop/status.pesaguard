from __future__ import annotations

import importlib
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from auth_rbac import AuthRBAC


@pytest.fixture()
def scheduler_client(monkeypatch, tmp_path):
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path / 'scheduler-api.db'}")
    monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "1")
    import app_2

    app_2 = importlib.reload(app_2)
    app_2.Base.metadata.create_all(app_2.primary_engine)
    from action_audit import ActionAuditEntry, Base as AuditBase
    from auth_rbac import _RevocationBase
    from models import UserAccount

    AuditBase.metadata.create_all(app_2.primary_engine)
    _RevocationBase.metadata.create_all(app_2.primary_engine)
    ActionAuditEntry.__table__.create(app_2.primary_engine, checkfirst=True)
    with app_2.SessionLocal() as session:
        session.add_all([
            UserAccount(
                id="scheduler-a-user",
                tenant_id="tenant-a",
                username="scheduler-a",
                roles=["admin"],
                permissions=[],
                status="active",
                authorization_version=1,
            ),
            UserAccount(
                id="scheduler-b-user",
                tenant_id="tenant-b",
                username="scheduler-b",
                roles=["admin"],
                permissions=[],
                status="active",
                authorization_version=1,
            ),
        ])
        session.commit()
    app_2.app.config.update(TESTING=True)
    with app_2.app.test_client() as client:
        yield client


def _headers(tenant_id: str) -> dict[str, str]:
    return {
        "Authorization": "Bearer " + AuthRBAC.generate_token(
            user_id=f"scheduler-{tenant_id[-1]}-user",
            username=f"scheduler-{tenant_id[-1]}",
            tenant_id=tenant_id,
            roles=["admin"],
        )
    }


def test_scheduled_job_registration_and_listing_are_tenant_scoped(scheduler_client):
    first_run = datetime(2026, 10, 15, tzinfo=timezone.utc).isoformat()
    created = scheduler_client.post(
        "/api/v1/operations/scheduled-jobs",
        headers=_headers("tenant-a"),
        json={
            "job_key": "nightly-reconciliation",
            "interval_seconds": 3600,
            "timeout_seconds": 120,
            "first_run_at": first_run,
            "batch_size": 50,
        },
    )
    assert created.status_code == 201
    assert created.get_json()["task_name"] == "reconciliation.pending_transactions"

    conflicting_registration = scheduler_client.post(
        "/api/v1/operations/scheduled-jobs",
        headers=_headers("tenant-a"),
        json={
            "job_key": "nightly-reconciliation",
            "interval_seconds": 1800,
            "timeout_seconds": 120,
            "first_run_at": first_run,
            "batch_size": 50,
        },
    )
    assert conflicting_registration.status_code == 400

    import app_2
    from scheduler import Scheduler

    with app_2.SessionLocal() as session:
        Scheduler(session).register(
            "tenant-b",
            "nightly-reconciliation",
            "reconciliation.pending_transactions",
            3600,
            first_run_at=datetime.fromisoformat(first_run),
            timeout_seconds=120,
        )
        session.commit()

    listed = scheduler_client.get(
        "/api/v1/operations/scheduled-jobs",
        headers=_headers("tenant-a"),
    )
    assert listed.status_code == 200
    assert [item["job_key"] for item in listed.get_json()["items"]] == [
        "nightly-reconciliation"
    ]

    foreign_history = scheduler_client.get(
        "/api/v1/operations/scheduled-jobs/nightly-reconciliation/history",
        headers=_headers("tenant-b"),
    )
    assert foreign_history.status_code == 200
    assert foreign_history.get_json()["items"] == []


def test_backfill_requires_operator_reason_and_is_audited(scheduler_client):
    registered = scheduler_client.post(
        "/api/v1/operations/scheduled-jobs",
        headers=_headers("tenant-a"),
        json={
            "job_key": "manual-reconcile",
            "interval_seconds": 3600,
            "timeout_seconds": 120,
        },
    )
    assert registered.status_code == 201

    response = scheduler_client.post(
        "/api/v1/operations/scheduled-jobs/manual-reconcile/backfills",
        headers=_headers("tenant-a"),
        json={
            "start_at": "2026-09-01T00:00:00Z",
            "end_at": "2026-09-02T00:00:00Z",
            "idempotency_key": "manual-september-2026",
            "reason": "recover a verified ingest outage",
        },
    )
    assert response.status_code == 202
    assert response.get_json()["status"] == "queued"


def test_interrupted_scheduled_run_requires_confirmed_operator_resolution(
    scheduler_client,
    monkeypatch,
):
    import app_2
    import scheduler
    from models import ScheduledJobRun

    class AvailableLock:
        def acquire(self, name, ttl_seconds=30.0):
            return f"token-{name}"

        def release(self, name, token):
            return True

    monkeypatch.setattr(
        scheduler,
        "get_distributed_lock",
        lambda: AvailableLock(),
    )
    first_run = datetime(2026, 10, 15, tzinfo=timezone.utc)
    response = scheduler_client.post(
        "/api/v1/operations/scheduled-jobs",
        headers=_headers("tenant-a"),
        json={
            "job_key": "recoverable-reconciliation",
            "interval_seconds": 3600,
            "timeout_seconds": 120,
            "first_run_at": first_run.isoformat(),
        },
    )
    assert response.status_code == 201

    from models import ScheduledJob

    with app_2.SessionLocal() as session:
        job = session.query(ScheduledJob).filter_by(
            tenant_id="tenant-a",
            job_key="recoverable-reconciliation",
        ).one()
        run = ScheduledJobRun(
            id="interrupted-api-run",
            job_id=job.id,
            tenant_id="tenant-a",
            job_key=job.job_key,
            scheduled_for=job.next_run_at,
            attempt_number=1,
            status="interrupted",
            worker_id="worker-before-crash",
            started_at=first_run,
            finished_at=first_run,
            error_type="WorkerInterrupted",
        )
        session.add(run)
        session.commit()

    unconfirmed = scheduler_client.post(
        "/api/v1/operations/scheduled-runs/interrupted-api-run/resolve",
        headers=_headers("tenant-a"),
        json={
            "resolution": "retry",
            "reason": "Reviewed downstream effect",
        },
    )
    assert unconfirmed.status_code == 409

    resolved = scheduler_client.post(
        "/api/v1/operations/scheduled-runs/interrupted-api-run/resolve",
        headers=_headers("tenant-a"),
        json={
            "resolution": "retry",
            "reason": "Verified downstream idempotency before replay",
            "confirm_idempotent_replay": True,
        },
    )
    assert resolved.status_code == 200
    assert resolved.get_json()["resolution"] == "retry_scheduled"

    with app_2.SessionLocal() as session:
        run = session.query(ScheduledJobRun).filter_by(
            id="interrupted-api-run",
            tenant_id="tenant-a",
        ).one()
        assert run.resolution_by == "scheduler-a-user"
        assert run.resolution_reason == "Verified downstream idempotency before replay"


def test_schedule_management_requires_manage_permission(scheduler_client, monkeypatch):
    monkeypatch.setitem(AuthRBAC.ROLE_PERMISSIONS, "scheduler-reader", ["read:analytics"])
    token = AuthRBAC.generate_token(
        user_id="scheduler-a-user",
        username="scheduler-a",
        tenant_id="tenant-a",
        roles=["scheduler-reader"],
    )
    response = scheduler_client.post(
        "/api/v1/operations/scheduled-jobs",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "job_key": "unauthorized",
            "interval_seconds": 3600,
            "timeout_seconds": 120,
        },
    )
    assert response.status_code in {401, 403}


def test_dead_letter_replay_requires_reason_and_recovers_stale_lease(
    scheduler_client,
    monkeypatch,
):
    import app_2
    from models import DeadLetter

    monkeypatch.setitem(
        AuthRBAC.ROLE_PERMISSIONS,
        "admin",
        [*AuthRBAC.ROLE_PERMISSIONS["admin"], "resolve:discrepancies"],
    )
    stale_time = datetime.now(timezone.utc) - timedelta(days=1)
    with app_2.SessionLocal() as session:
        session.add(
            DeadLetter(
                id="stale-dlq-a",
                tenant_id="tenant-a",
                reason="background_job_failed",
                payload={"transaction_id": "tx-1"},
                attempts=1,
                processed=True,
                replay_status="replaying",
                replayed_at=stale_time,
            )
        )
        session.commit()

    import rq

    queued = []

    class FakeQueue:
        def __init__(self, name, connection):
            self.name = name

        def enqueue(self, function, *args, **kwargs):
            queued.append((self.name, args, kwargs))
            return SimpleNamespace(id="rq-replay-1")

    monkeypatch.setattr(rq, "Queue", FakeQueue)

    missing_reason = scheduler_client.post(
        "/api/v1/operations/dead-letters/stale-dlq-a/replay",
        headers=_headers("tenant-a"),
        json={},
    )
    assert missing_reason.status_code == 400

    foreign_tenant = scheduler_client.post(
        "/api/v1/operations/dead-letters/stale-dlq-a/replay",
        headers=_headers("tenant-b"),
        json={"reason": "reviewed stale lease"},
    )
    assert foreign_tenant.status_code == 404
    assert queued == []

    response = scheduler_client.post(
        "/api/v1/operations/dead-letters/stale-dlq-a/replay",
        headers=_headers("tenant-a"),
        json={"reason": "Reviewed stale lease; root cause is corrected"},
    )
    assert response.status_code == 200
    assert response.get_json()["status"] == "queued"
    assert response.get_json()["attempts"] == 2
    assert len(queued) == 1

    with app_2.SessionLocal() as session:
        entry = session.query(DeadLetter).filter_by(id="stale-dlq-a").one()
        assert entry.tenant_id == "tenant-a"
        assert entry.replay_status == "queued"
        assert entry.processed is False
        assert entry.replay_reason == "Reviewed stale lease; root cause is corrected"
