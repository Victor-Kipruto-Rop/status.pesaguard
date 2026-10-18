from __future__ import annotations

from unittest.mock import Mock
from types import SimpleNamespace

import pytest
import requests
import resend_email as legacy_resend_email
from flask import Flask
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from pesaguard_backend_pipeline.communications.application.email_service import EmailService
from pesaguard_backend_pipeline.communications.core.enums import CommunicationChannel
from pesaguard_backend_pipeline.communications.core.exceptions import (
    PermanentCommunicationError,
    TransientCommunicationError,
)
from pesaguard_backend_pipeline.communications.core.interfaces import NotificationRequest
from pesaguard_backend_pipeline.communications.models import CommunicationNotification
from pesaguard_backend_pipeline.communications.providers.email import (
    EmailProviderConfig,
    EmailProviderFactory,
    EmailProviderHealth,
    ResendEmailProvider,
)
from pesaguard_backend_pipeline.communications.routes import create_webhook_blueprint
from pesaguard_backend_pipeline.resend_email import (
    ResendAPIError,
    ResendEmailClient,
)
from pesaguard_backend_pipeline.communications.worker import _default_channel_provider, process_outbox_once
from pesaguard_backend_pipeline.email_addresses import EMAIL_ADDRESSES, addresses_for_purpose
from pesaguard_backend_pipeline.models import Base, EmailNotification
import email_service as legacy_email_service


class FakeResponse:
    def __init__(self, status_code=200, payload=None):
        self.status_code = status_code
        self._payload = payload or {"id": "resend-message-123"}

    def json(self):
        return self._payload


def test_resend_client_sends_contract_payload_with_idempotency_and_tls_defaults():
    http = Mock()
    http.post.return_value = FakeResponse()
    client = ResendEmailClient(api_key="re_test_secret", http_client=http)

    result = client.send_message({
        "to": "person@example.test",
        "from_email": EMAIL_ADDRESSES.no_reply,
        "subject": "Test message",
        "html": "<p>Hello</p>",
        "text": "Hello",
        "preheader": "A short account update",
        "reply_to": EMAIL_ADDRESSES.support,
        "idempotency_key": "email-event-123",
    })

    assert result == {"message_id": "resend-message-123"}
    args, kwargs = http.post.call_args
    assert args == ("https://api.resend.com/emails",)
    assert kwargs["headers"] == {
        "Authorization": "Bearer re_test_secret",
        "Accept": "application/json",
        "Content-Type": "application/json",
        "Idempotency-Key": "email-event-123",
    }
    payload = kwargs["json"]
    assert payload == {
        "from": EMAIL_ADDRESSES.no_reply,
        "to": ["person@example.test"],
        "subject": "Test message",
        "html": payload["html"],
        "text": "Hello",
        "reply_to": EMAIL_ADDRESSES.support,
    }
    assert "PesaGuard" in payload["html"]
    assert "https://pesaguard.co.ke/logo/logo.svg" in payload["html"]
    assert "A short account update" in payload["html"]
    assert "<p>Hello</p>" in payload["html"]
    assert kwargs["timeout"] == 10
    assert kwargs["allow_redirects"] is False


@pytest.mark.parametrize(("status", "retryable"), [(400, False), (401, False), (408, True), (429, True), (500, True)])
def test_resend_http_failures_are_sanitized_and_classified(status, retryable):
    http = Mock()
    http.post.return_value = FakeResponse(status, {"message": "private provider detail"})
    client = ResendEmailClient(api_key="re_test_secret", http_client=http)

    with pytest.raises(ResendAPIError) as caught:
        client.send_message({
            "to": "person@example.test",
            "from_email": EMAIL_ADDRESSES.no_reply,
            "subject": "Test",
            "text": "Message",
        })

    assert caught.value.retryable is retryable
    assert "private provider detail" not in str(caught.value)


def test_resend_client_rejects_missing_id_and_network_errors_without_leaking_details():
    http = Mock()
    http.post.return_value = FakeResponse(payload={"unexpected": "response"})
    client = ResendEmailClient(api_key="re_test_secret", http_client=http)
    with pytest.raises(ResendAPIError, match="invalid response"):
        client.send_message({
            "to": "person@example.test",
            "from_email": EMAIL_ADDRESSES.no_reply,
            "subject": "Test",
            "text": "Message",
        })

    http.post.side_effect = requests.Timeout("sensitive network details")
    with pytest.raises(ResendAPIError) as caught:
        client.send_message({
            "to": "person@example.test",
            "from_email": EMAIL_ADDRESSES.no_reply,
            "subject": "Test",
            "text": "Message",
        })
    assert caught.value.retryable is True
    assert "sensitive network details" not in str(caught.value)


def test_resend_client_validates_sender_domain_key_and_idempotency():
    http = Mock()
    client = ResendEmailClient(api_key="", http_client=http)
    message = {
        "to": "person@example.test",
        "from_email": "attacker@outside.example",
        "subject": "Test",
        "text": "Message",
    }
    with pytest.raises(ResendAPIError):
        client.send_message(message)
    http.post.assert_not_called()

    client = ResendEmailClient(api_key="re_test_secret", http_client=http)
    with pytest.raises(ResendAPIError, match="verified PesaGuard domain"):
        client.send_message(message)
    http.post.assert_not_called()

    with pytest.raises(ResendAPIError):
        client.send_message({
            **message,
            "from_email": EMAIL_ADDRESSES.no_reply,
            "idempotency_key": "x" * 257,
        })
    http.post.assert_not_called()

    with pytest.raises(RuntimeError, match="sender and reply-to"):
        EmailProviderFactory(config=EmailProviderConfig(
            provider="resend",
            from_email="sender@outside.example",
            api_key="re_test_secret",
        )).create()


def test_resend_provider_builds_provider_message_from_notification_contract():
    client = Mock()
    client.send_message.return_value = {"message_id": "resend-456"}
    provider = ResendEmailProvider(client, from_email=EMAIL_ADDRESSES.no_reply)

    result = provider.send(NotificationRequest(
        tenant_id="tenant-a",
        recipient=" Person@Example.test ",
        message="Plain text body",
        channel=CommunicationChannel.EMAIL,
        idempotency_key="notification-1",
        variables={"subject": "Reconciliation ready", "html": "<p>Ready</p>"},
    ))

    assert result.provider == "resend_email"
    assert result.provider_message_id == "resend-456"
    assert result.status == "accepted"
    sent = client.send_message.call_args.args[0]
    assert sent["to"] == "person@example.test"
    assert sent["from_email"] == EMAIL_ADDRESSES.no_reply
    assert sent["reply_to"] == EMAIL_ADDRESSES.support
    assert sent["text"] == "Plain text body"
    assert sent["html"] == "<p>Ready</p>"
    assert sent["idempotency_key"] == "notification-1"


@pytest.mark.parametrize(
    ("failure", "expected_error", "expected_code"),
    [
        (
            legacy_resend_email.ResendAPIError("rejected", code="REJECTED", retryable=False),
            PermanentCommunicationError,
            "REJECTED",
        ),
        (
            legacy_resend_email.ResendAPIError("unavailable", code="UNAVAILABLE", retryable=True),
            TransientCommunicationError,
            "UNAVAILABLE",
        ),
        (TimeoutError("timeout"), TransientCommunicationError, "PROVIDER_TIMEOUT"),
        (ConnectionError("connection"), TransientCommunicationError, "PROVIDER_REQUEST_ERROR"),
        (RuntimeError("unexpected"), TransientCommunicationError, "PROVIDER_REQUEST_ERROR"),
    ],
)
def test_resend_provider_classifies_delivery_failures(failure, expected_error, expected_code):
    client = Mock()
    client.send_message.side_effect = failure
    provider = ResendEmailProvider(client)

    with pytest.raises(expected_error) as caught:
        provider.send(NotificationRequest(
            tenant_id="tenant-a",
            recipient="customer@example.test",
            message="Message",
            channel=CommunicationChannel.EMAIL,
        ))

    assert caught.value.code == expected_code


def test_resend_provider_rejects_missing_provider_message_id():
    client = Mock()
    client.send_message.return_value = {"unexpected": "response"}
    provider = ResendEmailProvider(client)

    with pytest.raises(PermanentCommunicationError) as caught:
        provider.send(NotificationRequest(
            tenant_id="tenant-a",
            recipient="customer@example.test",
            message="Message",
            channel=CommunicationChannel.EMAIL,
        ))

    assert caught.value.code == "PROVIDER_INVALID_RESPONSE"


def test_email_factory_builds_resend_provider_from_deployment_environment(monkeypatch):
    monkeypatch.setenv("RESEND_API_KEY", "re_test_secret")
    provider = EmailProviderFactory(config=EmailProviderConfig(
        provider="resend",
        from_email=EMAIL_ADDRESSES.no_reply,
    )).create()
    assert isinstance(provider, ResendEmailProvider)
    health = EmailProviderHealth()
    health.register_result("resend_email", latency_ms=15)
    assert health.snapshot()["provider"] == "resend_email"


def test_non_email_outbox_provider_keeps_existing_sms_selection(monkeypatch):
    provider = object()
    monkeypatch.setattr(
        "pesaguard_backend_pipeline.communications.worker.build_africas_talking_provider",
        lambda: provider,
    )

    assert _default_channel_provider(CommunicationChannel.SMS) is provider


def test_communications_outbox_delivers_email_through_configured_resend(monkeypatch):
    monkeypatch.setenv("RESEND_API_KEY", "re_test_secret")
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    http = Mock()
    http.post.return_value = FakeResponse()
    monkeypatch.setattr(
        "requests.post",
        http.post,
    )

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, expire_on_commit=False)()
    service = EmailService(
        session,
        config=EmailProviderConfig(provider="resend", from_email=EMAIL_ADDRESSES.no_reply),
    )
    notification = service.enqueue(NotificationRequest(
        tenant_id="tenant-a",
        recipient="customer@example.test",
        message="Your reconciliation report is ready.",
        channel=CommunicationChannel.EMAIL,
        idempotency_key="outbox-email-1",
        variables={"subject": "Report ready"},
    ))
    session.commit()

    assert process_outbox_once(session, worker_id="test-email-worker", publish_events=False) == 1

    session.refresh(notification)
    assert notification.status == "accepted"
    assert notification.provider == "resend_email"
    assert notification.provider_message_id == "resend-message-123"
    http.post.assert_called_once()
    assert http.post.call_args.kwargs["headers"]["Idempotency-Key"] == "outbox-email-1"
    session.close()


def test_email_address_directory_contains_all_pesaguard_roles():
    assert set(EMAIL_ADDRESSES.as_mapping()) == {
        "support", "sales", "billing", "security", "privacy", "compliance", "alerts",
        "no_reply", "onboarding", "partnerships", "operations", "incidents", "legal",
        "abuse", "status", "careers", "press",
    }
    assert all(address.endswith("@pesaguard.co.ke") for address in EMAIL_ADDRESSES.as_mapping().values())
    assert addresses_for_purpose("transactional") == (
        EMAIL_ADDRESSES.no_reply,
        EMAIL_ADDRESSES.support,
    )
    assert addresses_for_purpose("reconciliation") == (
        EMAIL_ADDRESSES.no_reply,
        EMAIL_ADDRESSES.support,
    )
    assert addresses_for_purpose("status") == (
        EMAIL_ADDRESSES.status,
        EMAIL_ADDRESSES.support,
    )
    assert addresses_for_purpose("security") == (EMAIL_ADDRESSES.security, EMAIL_ADDRESSES.security)
    assert addresses_for_purpose("escalation") == (EMAIL_ADDRESSES.alerts, EMAIL_ADDRESSES.incidents)


def test_legacy_email_service_uses_resend_for_auth_and_reports_without_smtp(monkeypatch):
    monkeypatch.setenv("PYTHON_DOTENV_DISABLED", "1")
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.setenv("RESEND_API_KEY", "re_test_secret")
    monkeypatch.setenv("PESAGUARD_EMAIL_FROM", EMAIL_ADDRESSES.no_reply)
    http = Mock()
    http.post.return_value = FakeResponse()
    monkeypatch.setattr(
        "requests.post",
        http.post,
    )
    service = legacy_email_service.EmailService()
    try:
        assert service.is_configured
        success, error = service.send_email(
            "customer@example.test",
            "Password reset",
            "<p>Reset requested</p>",
            "Reset requested",
            purpose="security",
            idempotency_key="reset-event-1",
        )
        assert (success, error) == (True, None)
        request = http.post.call_args.kwargs
        assert request["json"]["from"] == f"PesaGuard <{EMAIL_ADDRESSES.security}>"
        assert request["json"]["reply_to"] == EMAIL_ADDRESSES.security
        assert request["headers"]["Idempotency-Key"] == "reset-event-1"
    finally:
        service._executor.shutdown(wait=True)


def test_legacy_email_service_fails_closed_without_resend_api_key(monkeypatch):
    monkeypatch.setenv("PYTHON_DOTENV_DISABLED", "1")
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.delenv("RESEND_API_KEY", raising=False)
    service = legacy_email_service.EmailService()
    try:
        assert service.configuration_error() == "Resend configuration missing: RESEND_API_KEY is required"
    finally:
        service._executor.shutdown(wait=True)


@pytest.mark.parametrize(
    ("method_name", "success", "status_code", "expected_status"),
    [
        ("send_reconciliation_report", True, 200, "sent"),
        ("send_reconciliation_report", False, 401, "failed"),
        ("send_escalation_notification", True, 200, "sent"),
        ("send_escalation_notification", False, 401, "failed"),
    ],
)
def test_legacy_email_service_persists_direct_delivery_status(
    monkeypatch,
    method_name,
    success,
    status_code,
    expected_status,
):
    monkeypatch.setenv("PYTHON_DOTENV_DISABLED", "1")
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.setenv("RESEND_API_KEY", "re_test_secret")
    monkeypatch.setenv("PESAGUARD_EMAIL_FROM", EMAIL_ADDRESSES.no_reply)
    http = Mock()
    http.post.return_value = FakeResponse(status_code)
    monkeypatch.setattr("requests.post", http.post)

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = sessionmaker(bind=engine, expire_on_commit=False)()
    service = legacy_email_service.EmailService()
    try:
        if method_name == "send_reconciliation_report":
            result = service.send_reconciliation_report(
                session,
                "tenant-email-test",
                "customer@example.test",
                {"total_discrepancies": 1},
            )
        else:
            result = service.send_escalation_notification(
                session,
                "tenant-email-test",
                "customer@example.test",
                {"anomaly_type": "amount_mismatch", "trans_id": "TX-1"},
            )

        assert result["status"] == expected_status
        saved = session.query(EmailNotification).one()
        assert saved.status == expected_status
        assert (saved.error_message is None) is success
        assert http.post.call_count == 1
        assert http.post.call_args.kwargs["headers"]["Idempotency-Key"] == saved.id
    finally:
        session.close()
        service._executor.shutdown(wait=True)


def test_legacy_email_service_sanitizes_unexpected_resend_failure(monkeypatch, caplog):
    monkeypatch.setenv("PYTHON_DOTENV_DISABLED", "1")
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.setenv("RESEND_API_KEY", "re_test_secret")
    service = legacy_email_service.EmailService()
    service.resend_client.send_message = Mock(side_effect=RuntimeError("private provider response"))
    try:
        assert service.send_email(
            "customer@example.test",
            "Test",
            "<p>Test</p>",
            "Test",
        ) == (False, "Email delivery failed")
        assert "private provider response" not in caplog.text
    finally:
        service._executor.shutdown(wait=True)


def test_email_route_ignores_caller_sender_and_provider(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.setenv("RESEND_API_KEY", "re_test_secret")
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    app = Flask(__name__)
    app.register_blueprint(create_webhook_blueprint(
        session_factory,
        require_auth_fn=lambda _permission: lambda function: function,
        current_user_fn=lambda: SimpleNamespace(tenant_id="tenant-route"),
    ))

    response = app.test_client().post("/api/v1/communications/email", json={
        "recipient": "customer@example.test",
        "message": "A transactional message",
        "idempotency_key": "route-email-1",
        "provider": "mock_email",
        "from_email": "attacker@outside.example",
    })

    assert response.status_code == 202
    session = session_factory()
    try:
        saved = session.query(CommunicationNotification).one()
        assert saved.provider == "resend_email"
        assert saved.tenant_id == "tenant-route"
    finally:
        session.close()


def test_email_route_fails_closed_when_resend_is_not_configured(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.delenv("RESEND_API_KEY", raising=False)
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    app = Flask(__name__)
    app.register_blueprint(create_webhook_blueprint(
        session_factory,
        require_auth_fn=lambda _permission: lambda function: function,
        current_user_fn=lambda: SimpleNamespace(tenant_id="tenant-route"),
    ))

    response = app.test_client().post("/api/v1/communications/email", json={
        "recipient": "customer@example.test",
        "message": "A transactional message",
        "idempotency_key": "route-email-no-key",
    })

    assert response.status_code == 503
    session = session_factory()
    try:
        assert session.query(CommunicationNotification).count() == 0
    finally:
        session.close()


def test_report_and_alert_html_escape_untrusted_values(monkeypatch):
    monkeypatch.setenv("PYTHON_DOTENV_DISABLED", "1")
    service = legacy_email_service.EmailService(provider="smtp")
    try:
        report_html = service._build_reconciliation_html(
            {"total_discrepancies": "<script>alert(1)</script>"},
            locale="en",
        )
        alert_html = service._build_escalation_html(
            {"anomaly_type": "<img src=x onerror=alert(1)>", "trans_id": "<script>"},
            locale="en",
        )
        summary_html = service._build_summary_html(
            {"pending": "<script>alert(1)</script>"},
            locale="en",
        )
        assert "<script>" not in report_html + alert_html + summary_html
        assert "&lt;script&gt;" in report_html
        assert "&lt;img" in alert_html
        assert "&lt;script&gt;" in summary_html
    finally:
        service._executor.shutdown(wait=True)
