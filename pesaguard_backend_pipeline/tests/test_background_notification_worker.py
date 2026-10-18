"""Durable notification-event worker: context normalization and retry semantics.

These tests cover ``_notification_alert_context`` and
``_process_notification_event`` from ``background_tasks`` directly and
hermetically (no Redis, no RQ, no network, no provider calls).
"""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import background_tasks
import event_store as event_store_module
import tenant_settings as tenant_settings_module
import alerting_service as alerting_service_module
from models import Base


def _valid_event(**overrides):
    event = {
        "event": "fraud.confirmed",
        "tenant_id": "tenant-a",
        "event_id": "evt-1",
        "correlation_id": "corr-1",
        "context": {},
    }
    event.update(overrides)
    return event


class _SessionSpy:
    """Wrap a real SQLAlchemy session while recording lifecycle calls."""

    def __init__(self, real):
        self._real = real
        self.commits = 0
        self.rollbacks = 0
        self.closes = 0

    def __getattr__(self, name):
        return getattr(self._real, name)

    def commit(self):
        self.commits += 1
        return self._real.commit()

    def rollback(self):
        self.rollbacks += 1
        return self._real.rollback()

    def close(self):
        self.closes += 1
        return self._real.close()


def _stub_event_store(monkeypatch):
    """Replace EventStore with one backed by a throwaway SQLite database."""
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    spies = []

    def Session():
        spy = _SessionSpy(factory())
        spies.append(spy)
        return spy

    store = SimpleNamespace(_ensure_ready=lambda: None, Session=Session)
    monkeypatch.setattr(
        event_store_module, "EventStore", lambda *args, **kwargs: store
    )
    return spies


def _stub_tenant_settings(monkeypatch):
    monkeypatch.setattr(
        tenant_settings_module,
        "TenantSettingsStore",
        lambda: SimpleNamespace(get=lambda tenant_id: {}),
    )


def _stub_alerting_service(monkeypatch, *, result=None, error=None):
    calls = []

    class StubAlertingService:
        def __init__(self, *args, **kwargs):
            calls.append({"args": args, "kwargs": kwargs})

        def handle_discrepancy(self, context):
            calls.append({"context": context})
            if error is not None:
                raise error
            return dict(result if result is not None else {"status": "queued"})

    monkeypatch.setattr(
        alerting_service_module, "AlertingService", StubAlertingService
    )
    return calls


# ---------------------------------------------------------------------------
# _notification_alert_context
# ---------------------------------------------------------------------------


def test_notification_alert_context_derives_critical_defaults():
    context = background_tasks._notification_alert_context(_valid_event())
    assert context["tenant_id"] == "tenant-a"
    assert context["event_id"] == "evt-1"
    assert context["event"] == "fraud.confirmed"
    assert context["alert_type"] == "fraud_confirmed"
    assert context["source"] == "fraud"
    assert context["severity"] == "critical"
    assert context["title"]
    assert context["message"]
    # The source event id becomes the fallback deduplication key.
    assert context["deduplication_key"] == "evt-1"
    assert context["request_id"] == "corr-1"


def test_notification_alert_context_preserves_explicit_fields():
    context = background_tasks._notification_alert_context(
        _valid_event(
            event="transaction.failed",
            context={
                "alert_type": "custom_type",
                "severity": "info",
                "title": "Custom title",
                "message": "Custom message",
                "transaction_id": "tx-9",
            },
        )
    )
    assert context["alert_type"] == "custom_type"
    assert context["severity"] == "info"
    assert context["title"] == "Custom title"
    assert context["message"] == "Custom message"


def test_notification_alert_context_defaults_warning_for_failed_transaction():
    context = background_tasks._notification_alert_context(
        _valid_event(event="transaction.failed")
    )
    assert context["severity"] == "warning"


def test_notification_alert_context_rejects_unsupported_event():
    with pytest.raises(ValueError, match="unsupported notification event"):
        background_tasks._notification_alert_context(_valid_event(event="billing.overdue"))




# ---------------------------------------------------------------------------
# _process_notification_event
# ---------------------------------------------------------------------------


def test_process_notification_event_invokes_service_with_tenant_scoped_context(monkeypatch):
    spies = _stub_event_store(monkeypatch)
    _stub_tenant_settings(monkeypatch)
    calls = _stub_alerting_service(monkeypatch, result={"status": "queued", "deliveries": []})

    background_tasks._process_notification_event(
        _valid_event(tenant_id="tenant-z", event="fraud.suspected")
    )

    contexts = [call["context"] for call in calls if "context" in call]
    assert len(contexts) == 1
    assert contexts[0]["tenant_id"] == "tenant-z"
    assert contexts[0]["alert_type"] == "fraud_suspected"
    assert contexts[0]["severity"] == "warning"
    assert spies[0].rollbacks == 0
    assert spies[0].closes >= 1


def test_process_notification_event_raises_on_all_failed_channels_triggering_retry(monkeypatch):
    spies = _stub_event_store(monkeypatch)
    _stub_tenant_settings(monkeypatch)
    _stub_alerting_service(monkeypatch, result={"status": "failed", "deliveries": []})

    with pytest.raises(RuntimeError, match="alert channels failed"):
        background_tasks._process_notification_event(_valid_event())
    assert spies[0].rollbacks == 1
    assert spies[0].closes >= 1


def test_process_notification_event_raises_when_delivery_still_processing(monkeypatch):
    spies = _stub_event_store(monkeypatch)
    _stub_tenant_settings(monkeypatch)
    _stub_alerting_service(
        monkeypatch,
        result={"status": "queued", "deliveries": [{"status": "processing"}]},
    )

    with pytest.raises(RuntimeError, match="still processing"):
        background_tasks._process_notification_event(_valid_event())
    assert spies[0].rollbacks == 1
    assert spies[0].closes >= 1


def test_process_notification_event_rolls_back_and_releases_session_on_service_error(monkeypatch):
    spies = _stub_event_store(monkeypatch)
    _stub_tenant_settings(monkeypatch)
    _stub_alerting_service(
        monkeypatch, error=RuntimeError("slack provider unavailable")
    )

    with pytest.raises(RuntimeError, match="slack provider unavailable"):
        background_tasks._process_notification_event(_valid_event())
    assert spies[0].rollbacks == 1
    assert spies[0].closes >= 1


def test_process_notification_event_propagates_context_validation_errors(monkeypatch):
    spies = _stub_event_store(monkeypatch)
    _stub_tenant_settings(monkeypatch)
    calls = _stub_alerting_service(monkeypatch)

    with pytest.raises(ValueError, match="unsupported notification event"):
        background_tasks._process_notification_event(_valid_event(event="billing.overdue"))
    assert not [call for call in calls if "context" in call]
    assert not spies


def test_notification_alert_context_requires_tenant_id():
    event = _valid_event()
    del event["tenant_id"]
    with pytest.raises(KeyError):
        background_tasks._notification_alert_context(event)
