"""Tests for completeness monitoring of scheduled imports."""

from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock

from completeness_monitor import CompletenessMonitor
from event_store import EventStore


class FakeEventStore(EventStore):
    def __init__(self):
        self.dead_letters = []

    def write_dead_letter(self, payload, reason, error_detail, tenant_id):
        self.dead_letters.append(
            {"payload": payload, "reason": reason, "error_detail": error_detail, "tenant_id": tenant_id}
        )
        return True


class FakeSession:
    def __init__(self, jobs=None):
        self.jobs = jobs or []

    def query(self, model):
        return FakeQuery(self.jobs, model)


class FakeQuery:
    def __init__(self, jobs, model):
        self.jobs = jobs
        self.model = model
        self._filters = []

    def filter(self, *criterion):
        self._filters.extend(criterion)
        return self

    def order_by(self, *args):
        return self

    def first(self):
        import operator as _operator

        ops = {"eq": _operator.eq, "le": _operator.le, "lt": _operator.lt}
        for job in self.jobs:
            match = True
            for crit in self._filters:
                try:
                    left = crit.left
                    right = crit.right
                    # SQLAlchemy wraps literal values in BindParameter
                    if hasattr(right, "value"):
                        right = right.value
                    if not (hasattr(left, "name") and hasattr(job, left.name)):
                        match = False
                        break
                    job_val = getattr(job, left.name)
                    op_name = getattr(crit.operator, "__name__", "eq")
                    op = ops.get(op_name, _operator.eq)
                    if not op(job_val, right):
                        match = False
                        break
                except Exception:
                    match = False
                    break
            if match:
                return job
        return None


def make_job(job_id, tenant_id, provider, created_at, status="completed"):
    job = MagicMock()
    job.id = job_id
    job.tenant_id = tenant_id
    job.source = provider
    job.provider = provider
    job.created_at = created_at
    job.status = status
    return job


def test_missing_import_detected():
    now = datetime(2026, 9, 23, 10, 30, tzinfo=timezone.utc)
    store = FakeEventStore()
    session = FakeSession(jobs=[])
    monitor = CompletenessMonitor(session, store)
    monitor.register_expected_import("tenant-a", "equity", "daily", "08:00")
    findings = monitor.check_completeness(now=now)
    missing = [f for f in findings if f.status == "missing"]
    assert len(missing) == 1
    assert missing[0].tenant_id == "tenant-a"
    assert missing[0].provider == "equity"
    assert len(store.dead_letters) == 1
    assert store.dead_letters[0]["reason"] == "import_missing"


def test_ok_import_not_flagged():
    now = datetime(2026, 9, 23, 10, 30, tzinfo=timezone.utc)
    expected_dt = datetime(2026, 9, 23, 8, 0, tzinfo=timezone.utc)
    job = make_job("imp-1", "tenant-a", "equity", expected_dt + timedelta(minutes=10))
    store = FakeEventStore()
    session = FakeSession(jobs=[job])
    monitor = CompletenessMonitor(session, store)
    monitor.register_expected_import("tenant-a", "equity", "daily", "08:00")
    findings = monitor.check_completeness(now=now)
    assert all(f.status == "ok" for f in findings)
    assert len(store.dead_letters) == 0


def test_late_import_detected():
    now = datetime(2026, 9, 23, 10, 30, tzinfo=timezone.utc)
    expected_dt = datetime(2026, 9, 23, 8, 0, tzinfo=timezone.utc)
    # arrived 150 minutes after expected (deadline is 120 min)
    late_arrival = expected_dt + timedelta(minutes=150)
    job = make_job("imp-1", "tenant-a", "equity", late_arrival)
    store = FakeEventStore()
    session = FakeSession(jobs=[job])
    monitor = CompletenessMonitor(session, store)
    monitor.register_expected_import("tenant-a", "equity", "daily", "08:00", deadline_offset_minutes=120)
    findings = monitor.check_completeness(now=now)
    late = [f for f in findings if f.status == "late"]
    assert len(late) == 1
    assert late[0].minutes_late == 30
    assert len(store.dead_letters) == 1
    assert store.dead_letters[0]["reason"] == "import_late"


def test_multiple_providers_independently_tracked():
    now = datetime(2026, 9, 23, 10, 30, tzinfo=timezone.utc)
    expected_dt = datetime(2026, 9, 23, 8, 0, tzinfo=timezone.utc)
    kcb_job = make_job("imp-kcb", "tenant-a", "kcb", expected_dt + timedelta(minutes=5))
    store = FakeEventStore()
    session = FakeSession(jobs=[kcb_job])  # equity job missing
    monitor = CompletenessMonitor(session, store)
    monitor.register_expected_import("tenant-a", "equity", "daily", "08:00")
    monitor.register_expected_import("tenant-a", "kcb", "daily", "08:00")
    findings = monitor.check_completeness(now=now)
    statuses = {f.provider: f.status for f in findings}
    assert statuses["equity"] == "missing"
    assert statuses["kcb"] == "ok"


def test_invalid_expected_time_skipped():
    now = datetime(2026, 9, 23, 10, 30, tzinfo=timezone.utc)
    store = FakeEventStore()
    session = FakeSession(jobs=[])
    monitor = CompletenessMonitor(session, store)
    monitor.register_expected_import("tenant-a", "equity", "daily", "not-a-time")
    findings = monitor.check_completeness(now=now)
    assert findings == []