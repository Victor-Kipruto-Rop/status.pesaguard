from __future__ import annotations

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from flask import Flask
from types import SimpleNamespace

import pesaguard_backend_pipeline.communications.models  # noqa: F401
from data_protection import decrypt_value
from pesaguard_backend_pipeline.communications.application.channel_services import (
    NotificationPreferenceService,
    NotificationTemplateService,
    PushNotificationService,
    SmsService,
)
from pesaguard_backend_pipeline.communications.application.notification_service import NotificationService
from pesaguard_backend_pipeline.communications.core.enums import CommunicationChannel
from pesaguard_backend_pipeline.communications.core.interfaces import NotificationRequest, ProviderMessage
from pesaguard_backend_pipeline.communications.domain import set_consent
from pesaguard_backend_pipeline.communications.models import (
    CommunicationNotification,
    CommunicationOutboxEntry,
    CommunicationPreference,
)
from pesaguard_backend_pipeline.communications.worker import process_outbox_once
from pesaguard_backend_pipeline.communications.routes import create_webhook_blueprint
from pesaguard_backend_pipeline.models import Base


class Provider:
    def __init__(self, channel: CommunicationChannel, name: str):
        self.channel = channel
        self.name = name
        self.supported_channels = frozenset({channel})
        self.calls: list[NotificationRequest] = []

    def validate_recipient(self, channel, recipient):
        if channel != self.channel:
            raise ValueError("unsupported channel")
        return recipient.strip()

    def send(self, request):
        self.calls.append(request)
        return ProviderMessage(self.name, f"{self.name}-message", "accepted")


def _session():
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)()


def test_notification_idempotency_conflicts_on_payload_change():
    session = _session()
    service = NotificationService(session, Provider(CommunicationChannel.SMS, "sms"))
    first = NotificationRequest(
        tenant_id="tenant-a",
        recipient="+254700000001",
        message="Payment received",
        channel=CommunicationChannel.SMS,
        idempotency_key="same-key",
    )
    service.enqueue(first)
    session.commit()

    try:
        service.enqueue(
            NotificationRequest(
                tenant_id="tenant-a",
                recipient="+254700000001",
                message="Different payment",
                channel=CommunicationChannel.SMS,
                idempotency_key="same-key",
            )
        )
    except ValueError as exc:
        assert "different notification" in str(exc)
    else:
        raise AssertionError("same idempotency key accepted a different payload")

    other_tenant = service.enqueue(
        NotificationRequest(
            tenant_id="tenant-b",
            recipient="+254700000002",
            message="A separate tenant message",
            channel=CommunicationChannel.SMS,
            idempotency_key="same-key",
        )
    )
    assert other_tenant.tenant_id == "tenant-b"
    assert session.query(CommunicationNotification).count() == 2
    assert session.query(CommunicationOutboxEntry).count() == 2


def test_preferences_templates_and_tenant_scoping():
    session = _session()
    preferences = NotificationPreferenceService(session)
    updated = preferences.update(
        tenant_id="tenant-a",
        recipient="user-1",
        channels=["email", "push"],
        quiet_hours_start="22:00",
        quiet_hours_end="06:00",
        timezone_name="Africa/Nairobi",
        marketing_opt_in=True,
    )
    assert updated.channels == ["email", "push"]
    assert preferences.is_allowed(
        tenant_id="tenant-a",
        recipient="user-1",
        channel=CommunicationChannel.SMS,
    ) is False
    assert preferences.get(tenant_id="tenant-b", recipient="user-1") is None

    try:
        preferences.update(tenant_id="tenant-a", recipient="user-1", timezone_name="not/a-timezone")
    except ValueError as exc:
        assert "IANA timezone" in str(exc)
    else:
        raise AssertionError("invalid timezone was accepted")

    templates = NotificationTemplateService(session)
    template = templates.create(
        tenant_id="tenant-a",
        slug="payment-receipt",
        channel=CommunicationChannel.PUSH,
        body="Payment received: {amount} {currency}",
        variables=["amount", "currency"],
    )
    try:
        templates.render(
            tenant_id="tenant-a",
            template_id=template.id,
            channel=CommunicationChannel.PUSH,
            values={"amount": "25.00", "currency": "KES"},
        )
    except ValueError as exc:
        assert "not approved" in str(exc)
    else:
        raise AssertionError("draft template was rendered")

    templates.approve(tenant_id="tenant-a", template_id=template.id, approver="operator")
    assert templates.render(
        tenant_id="tenant-a",
        template_id=template.id,
        channel=CommunicationChannel.PUSH,
        values={"amount": "25.00", "currency": "KES"},
    ) == "Payment received: 25.00 KES"

    try:
        templates.render(
            tenant_id="tenant-b",
            template_id=template.id,
            channel=CommunicationChannel.PUSH,
            values={"amount": "25.00", "currency": "KES"},
        )
    except ValueError as exc:
        assert str(exc) == "template not found"
    else:
        raise AssertionError("cross-tenant template access was allowed")


def test_channel_facades_and_worker_route_push_through_injected_provider():
    session = _session()
    sms_provider = Provider(CommunicationChannel.SMS, "sms-provider")
    push_provider = Provider(CommunicationChannel.PUSH, "push-provider")

    sms_notification = SmsService(session, sms_provider).enqueue(
        NotificationRequest(
            tenant_id="tenant-a",
            recipient="+254700000001",
            message="SMS notice",
            channel=CommunicationChannel.SMS,
            idempotency_key="sms-1",
        )
    )
    push_notification = PushNotificationService(session, push_provider).enqueue(
        NotificationRequest(
            tenant_id="tenant-a",
            recipient="device-token-1",
            message="Push notice",
            channel=CommunicationChannel.PUSH,
            idempotency_key="push-1",
        )
    )
    session.commit()

    def resolve_provider(channel):
        return {CommunicationChannel.SMS: sms_provider, CommunicationChannel.PUSH: push_provider}[channel]

    processed = process_outbox_once(
        session,
        channel_provider_factory=resolve_provider,
        worker_id="channel-worker",
        limit=10,
        publish_events=False,
    )
    session.commit()

    assert processed == 2
    assert session.get(CommunicationNotification, sms_notification.id).status == "accepted"
    assert session.get(CommunicationNotification, push_notification.id).status == "accepted"
    assert len(sms_provider.calls) == len(push_provider.calls) == 1
    assert push_provider.calls[0].channel is CommunicationChannel.PUSH
    assert push_provider.calls[0].recipient == "device-token-1"
    stored_push = session.get(CommunicationNotification, push_notification.id)
    assert stored_push.recipient != "device-token-1"
    assert decrypt_value(stored_push.recipient) == "device-token-1"
    assert session.query(CommunicationOutboxEntry).filter_by(status="completed").count() == 2


def test_preference_update_rejects_partial_quiet_hours_and_unknown_channels():
    session = _session()
    preferences = NotificationPreferenceService(session)
    for update in (
        {"channels": ["sms", "unknown"]},
        {"quiet_hours_start": "22:00"},
    ):
        try:
            preferences.update(tenant_id="tenant-a", recipient="user-1", **update)
        except ValueError:
            pass
        else:
            raise AssertionError(f"invalid preference update was accepted: {update}")

    assert session.query(CommunicationPreference).count() == 0


def test_preference_quiet_hours_can_be_cleared_and_marketing_flag_is_typed():
    session = _session()
    preferences = NotificationPreferenceService(session)
    preferences.update(
        tenant_id="tenant-a",
        recipient="user-1",
        quiet_hours_start="22:00",
        quiet_hours_end="06:00",
    )
    cleared = preferences.update(
        tenant_id="tenant-a",
        recipient="user-1",
        quiet_hours_start="",
        quiet_hours_end="",
    )
    assert cleared.quiet_hours_start is None
    assert cleared.quiet_hours_end is None

    try:
        preferences.update(tenant_id="tenant-a", recipient="user-1", marketing_opt_in="false")
    except ValueError as exc:
        assert "boolean" in str(exc)
    else:
        raise AssertionError("string marketing preference was accepted")


def test_consent_requires_explicit_boolean_and_matching_marketing_purpose():
    session = _session()
    try:
        set_consent(
            session,
            tenant_id="tenant-a",
            recipient="user-1",
            channel=CommunicationChannel.EMAIL,
            purpose="marketing",
            granted="false",
        )
    except ValueError as exc:
        assert "boolean" in str(exc)
    else:
        raise AssertionError("string consent value was accepted")


def test_marketing_notifications_require_opt_in_and_channel_consent():
    session = _session()
    preferences = NotificationPreferenceService(session)
    preferences.update(
        tenant_id="tenant-a",
        recipient="user@example.test",
        channels=["email"],
        marketing_opt_in=True,
    )
    request = NotificationRequest(
        tenant_id="tenant-a",
        recipient="user@example.test",
        message="A promotion",
        channel=CommunicationChannel.EMAIL,
        idempotency_key="marketing-1",
        variables={"purpose": "marketing"},
    )
    service = NotificationService(session, Provider(CommunicationChannel.EMAIL, "email"))
    try:
        service.enqueue(request)
    except ValueError as exc:
        assert "consent" in str(exc)
    else:
        raise AssertionError("marketing notification without channel consent was queued")

    set_consent(
        session,
        tenant_id="tenant-a",
        recipient="user@example.test",
        channel=CommunicationChannel.EMAIL,
        purpose="marketing",
        granted=True,
    )
    queued = service.enqueue(request)
    assert queued.tenant_id == "tenant-a"


def test_push_api_enqueues_only_with_injected_gateway():
    session = _session()
    provider = Provider(CommunicationChannel.PUSH, "push-provider")
    auth = lambda _permission: lambda function: function
    user = lambda: SimpleNamespace(tenant_id="tenant-a")

    unconfigured_app = Flask("unconfigured_push")
    unconfigured_app.register_blueprint(
        create_webhook_blueprint(
            lambda: session,
            require_auth_fn=auth,
            current_user_fn=user,
        )
    )
    unavailable = unconfigured_app.test_client().post(
        "/api/v1/communications/push",
        json={"recipient": "device-token-1", "message": "Notice", "idempotency_key": "push-off"},
    )
    assert unavailable.status_code == 503

    app = Flask(__name__)
    app.register_blueprint(
        create_webhook_blueprint(
            lambda: session,
            require_auth_fn=auth,
            current_user_fn=user,
            push_provider_factory=lambda: provider,
        )
    )

    response = app.test_client().post(
        "/api/v1/communications/push",
        json={
            "recipient": "device-token-1",
            "message": "Your payment arrived",
            "idempotency_key": "push-api-1",
        },
    )

    assert response.status_code == 202
    assert response.get_json()["tenant_id"] == "tenant-a"
    assert session.query(CommunicationNotification).filter_by(channel="push").count() == 1
    assert session.query(CommunicationOutboxEntry).filter_by(status="pending").count() == 1
    assert provider.calls == []
