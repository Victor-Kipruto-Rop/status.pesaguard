import uuid

import pytest

import app_4_advanced_features as advanced_features
from test_config import configure_test_database

configure_test_database()

from action_audit import ActionAuditEntry
from app_4_advanced_features import SessionLocal, app
from models import EmailNotification, PasswordlessChallenge, UserAccount, UserIdentity
from tenant_context import current_tenant_id


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setitem(app.config, "TESTING", True)
    monkeypatch.setitem(app.config, "PUBLIC_REGISTRATION_TENANT_ID", "registration-tenant")
    with app.test_client() as test_client:
        yield test_client


def _clean(email: str, tenant_id: str = "registration-tenant"):
    with SessionLocal() as session:
        accounts = session.query(UserAccount).filter_by(tenant_id=tenant_id).all()
        user_ids = [account.id for account in accounts if account.email == email]
        if user_ids:
            session.query(PasswordlessChallenge).filter(PasswordlessChallenge.user_id.in_(user_ids)).delete(synchronize_session=False)
        session.query(EmailNotification).filter_by(recipient_email=email, tenant_id=tenant_id).delete(synchronize_session=False)
        session.query(UserIdentity).filter_by(email=email, tenant_id=tenant_id).delete(synchronize_session=False)
        session.query(UserAccount).filter_by(email=email, tenant_id=tenant_id).delete(synchronize_session=False)
        session.commit()


def test_registration_normalization_duplicate_generic_response_and_audit(client):
    email = "register@example.com"
    tenant_id = "registration-tenant"
    _clean(email)

    first = client.post(
        "/auth/register",
        json={"username": "register-user", "email": "  REGISTER@EXAMPLE.COM ", "password": "StrongPass123!", "tenant_id": tenant_id},
    )
    assert first.status_code == 201, first.get_data(as_text=True)
    token = first.get_json()["verification_token"]

    duplicate_pending = client.post(
        "/auth/register",
        json={"username": "register-user", "email": email, "password": "StrongPass123!", "tenant_id": tenant_id},
    )
    assert duplicate_pending.status_code == 202

    resend = client.post("/auth/verify-email/resend", json={"email": email, "tenant_id": tenant_id})
    assert resend.status_code == 202
    assert client.post("/auth/verify-email", json={"email": email, "token": resend.get_json()["verification_token"], "tenant_id": tenant_id}).status_code == 200

    duplicate_active = client.post(
        "/auth/register",
        json={"username": "another-name", "email": email, "password": "StrongPass123!", "tenant_id": tenant_id},
    )
    assert duplicate_active.status_code == 202
    assert duplicate_active.get_json() == {"status": "accepted"}

    with SessionLocal() as session:
        identity = session.query(UserIdentity).filter_by(email=email, tenant_id=tenant_id).one()
        assert identity.email == "register@example.com"
        assert session.query(ActionAuditEntry).filter_by(action="registration.created", resource_id=identity.user_id).count() == 1
        assert session.query(ActionAuditEntry).filter_by(action="registration.duplicate", resource_id=identity.user_id).count() == 1

    _clean(email)


def test_registration_rejects_caller_selected_tenant(client):
    email = f"tenant-spoof-{uuid.uuid4().hex[:8]}@example.com"
    configured_tenant = "registration-tenant"
    requested_tenant = "another-tenant"

    response = client.post(
        "/auth/register",
        json={
            "username": "tenant-spoof",
            "email": email,
            "password": "StrongPass123!",
            "tenant_id": requested_tenant,
        },
    )

    assert response.status_code == 400
    assert response.get_json()["error"] == "tenant_selection_not_allowed"
    with SessionLocal() as session:
        assert session.query(UserAccount).filter_by(email=email, tenant_id=configured_tenant).count() == 0
        assert session.query(UserAccount).filter_by(email=email, tenant_id=requested_tenant).count() == 0


def test_production_registration_requires_server_configured_tenant(client, monkeypatch):
    monkeypatch.delitem(app.config, "PUBLIC_REGISTRATION_TENANT_ID")
    monkeypatch.delenv("PESAGUARD_PUBLIC_REGISTRATION_TENANT_ID", raising=False)
    monkeypatch.delenv("TENANT_ID", raising=False)
    monkeypatch.setattr(advanced_features, "_is_production_environment", lambda: True)

    response = client.post(
        "/auth/register",
        json={
            "username": "unconfigured-registration",
            "email": f"unconfigured-{uuid.uuid4().hex[:8]}@example.com",
            "password": "StrongPass123!",
        },
    )

    assert response.status_code == 503
    assert response.get_json()["error"] == "registration_unavailable"


def test_invalid_server_registration_tenant_fails_closed(client, monkeypatch):
    monkeypatch.setitem(app.config, "PUBLIC_REGISTRATION_TENANT_ID", "invalid tenant")

    response = client.post(
        "/auth/register",
        json={
            "username": "invalid-tenant-registration",
            "email": f"invalid-tenant-{uuid.uuid4().hex[:8]}@example.com",
            "password": "StrongPass123!",
        },
    )

    assert response.status_code == 503
    assert response.get_json()["error"] == "registration_unavailable"


@pytest.mark.parametrize("payload", [[], "not-json"])
def test_registration_rejects_non_object_json_body(client, payload):
    if payload == "not-json":
        response = client.post(
            "/auth/register",
            data="{",
            content_type="application/json",
        )
    else:
        response = client.post("/auth/register", json=payload)

    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_request"


def test_public_auth_lookup_scopes_database_work_to_requested_tenant():
    scoped_lookup = advanced_features._tenant_scoped_request(current_tenant_id)

    with app.test_request_context("/auth/login", json={"tenant_id": "auth-lookup-tenant"}):
        assert scoped_lookup() == "auth-lookup-tenant"

    assert current_tenant_id() is None


def test_honeypot_and_resend_single_use_attempt_limit(client):
    email = "resend@example.com"
    tenant_id = "registration-tenant"
    _clean(email)

    bot = client.post(
        "/auth/register",
        json={"username": "bot", "email": email, "password": "StrongPass123!", "tenant_id": tenant_id, "website": "https://bot.invalid"},
    )
    assert bot.status_code == 202
    with SessionLocal() as session:
        assert session.query(UserAccount).filter_by(email=email, tenant_id=tenant_id).count() == 0

    created = client.post(
        "/auth/register",
        json={"username": "resend-user", "email": email, "password": "StrongPass123!", "tenant_id": tenant_id},
    )
    assert created.status_code == 201
    old_token = created.get_json()["verification_token"]

    resent = client.post("/auth/verify-email/resend", json={"email": email, "tenant_id": tenant_id})
    assert resent.status_code == 202
    new_token = resent.get_json()["verification_token"]
    assert new_token != old_token
    assert client.post("/auth/verify-email", json={"email": email, "token": old_token, "tenant_id": tenant_id}).status_code == 400

    for _ in range(5):
        assert client.post("/auth/verify-email", json={"email": email, "token": "wrong-token", "tenant_id": tenant_id}).status_code == 400
    assert client.post("/auth/verify-email", json={"email": email, "token": new_token, "tenant_id": tenant_id}).status_code == 400

    _clean(email)


def test_verification_delivery_failure_is_persisted_and_revokes_challenge(client, monkeypatch):
    email = f"delivery-failure-{uuid.uuid4().hex[:8]}@example.com"
    tenant_id = "registration-tenant"
    _clean(email)
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "testing")
    monkeypatch.setattr(advanced_features, "_is_production_environment", lambda: True)
    monkeypatch.setattr("app_4_advanced_features.email_service.smtp_server", "smtp.example.test")
    monkeypatch.setattr(
        "app_4_advanced_features.email_service._send_email",
        lambda *args, **kwargs: (False, "smtp unavailable"),
    )

    response = client.post(
        "/auth/register",
        json={"username": "delivery-failure", "email": email, "password": "StrongPass123!", "tenant_id": tenant_id},
    )
    assert response.status_code == 201
    assert "verification_token" not in response.get_json()

    with SessionLocal() as session:
        account = session.query(UserAccount).filter_by(email=email, tenant_id=tenant_id).one()
        challenge = session.query(PasswordlessChallenge).filter_by(user_id=account.id, tenant_id=tenant_id).one()
        delivery = session.query(EmailNotification).filter_by(recipient_email=email, report_type="email_verification").one()
        assert challenge.status == "delivery_failed"
        assert delivery.status == "failed"
        assert delivery.error_message == "smtp unavailable"

    _clean(email)
