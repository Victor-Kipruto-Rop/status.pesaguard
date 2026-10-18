"""Durable alert creation and email-outbox behavior."""

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from alert_center import queue_alert_email_deliveries, record_alert
from communications.application.email_service import EmailService
from communications.models import CommunicationNotification, CommunicationOutboxEntry
from models import AlertDelivery, AlertEvent, Base


def test_alert_email_is_queued_once_and_linked_to_delivery_status(monkeypatch):
    """An alert and its configured email delivery share a durable database transaction."""
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "mock")
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "ops@example.com")
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, expire_on_commit=False)()

    alert, created, replay = record_alert(
        session,
        tenant_id="tenant-alert",
        payload={
            "id": "reconciliation-exception-1",
            "event_id": "delivery-event-1",
            "category": "amount_mismatch",
            "severity": "critical",
            "title": "Amount mismatch detected",
            "message": "Provider and ledger amounts differ.",
        },
    )
    assert created is True
    assert replay is False
    rows = queue_alert_email_deliveries(session, alert=alert)
    session.commit()

    assert len(rows) == 1
    delivery = rows[0]
    assert delivery.channel == "email"
    assert delivery.status == "queued"
    assert delivery.destination_hash
    assert delivery.communication_notification_id

    notification = session.query(CommunicationNotification).filter_by(
        id=delivery.communication_notification_id,
        tenant_id="tenant-alert",
    ).one()
    outbox = session.query(CommunicationOutboxEntry).filter_by(notification_id=notification.id).one()
    assert notification.status == "queued"
    assert outbox.status == "pending"
    assert notification.metadata_json["alert_delivery_id"] == delivery.id

    duplicate, created_again, is_replay = record_alert(
        session,
        tenant_id="tenant-alert",
        payload={
            "id": "reconciliation-exception-1",
            "event_id": "delivery-event-2",
            "category": "amount_mismatch",
            "severity": "critical",
            "title": "Amount mismatch detected",
            "message": "Provider and ledger amounts differ.",
        },
    )
    session.commit()
    assert duplicate.id == alert.id
    assert created_again is False
    assert is_replay is False
    assert session.query(AlertEvent).filter_by(alert_id=alert.id).count() == 3
    assert session.query(AlertDelivery).filter_by(alert_id=alert.id, channel="email").count() == 1

    session.close()
    engine.dispose()


def test_failed_email_enqueue_is_repaired_on_same_source_event_replay(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "mock")
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "ops@example.com")
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, expire_on_commit=False)()
    payload = {
        "id": "email-retry-alert",
        "event_id": "email-retry-event",
        "category": "amount_mismatch",
        "severity": "critical",
        "title": "Amount mismatch detected",
    }
    alert, created, replay = record_alert(session, tenant_id="tenant-alert", payload=payload)
    assert created and not replay

    original_enqueue = EmailService.enqueue
    monkeypatch.setattr(
        EmailService,
        "enqueue",
        lambda self, request: (_ for _ in ()).throw(RuntimeError("temporary enqueue failure")),
    )
    failed_row = queue_alert_email_deliveries(session, alert=alert)[0]
    session.commit()
    assert failed_row.status == "failed"
    assert failed_row.communication_notification_id is None

    monkeypatch.setattr(EmailService, "enqueue", original_enqueue)
    replayed_alert, created_again, is_replay = record_alert(
        session,
        tenant_id="tenant-alert",
        payload=payload,
    )
    assert replayed_alert.id == alert.id
    assert not created_again and is_replay
    repaired_row = queue_alert_email_deliveries(session, alert=replayed_alert)[0]
    session.commit()

    assert repaired_row.status == "queued"
    assert repaired_row.communication_notification_id
    assert session.query(CommunicationOutboxEntry).filter_by(
        notification_id=repaired_row.communication_notification_id,
    ).one().status == "pending"
    session.close()
    engine.dispose()
