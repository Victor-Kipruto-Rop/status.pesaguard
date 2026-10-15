import pytest
from datetime import datetime, timedelta, timezone

from test_config import configure_test_database

configure_test_database()

from action_audit import ActionAuditEntry
import app_4_advanced_features as advanced_features
from app_4_advanced_features import SessionLocal, app
from models import UserAccount, UserIdentity, UserSession


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setitem(app.config, "TESTING", True)
    monkeypatch.setitem(app.config, "PUBLIC_REGISTRATION_TENANT_ID", "login-tenant")
    with app.test_client() as test_client:
        yield test_client


def _clean(email: str, tenant_id: str = "login-tenant"):
    with SessionLocal() as session:
        account = session.query(UserAccount).filter_by(email=email, tenant_id=tenant_id).first()
        if account is not None:
            session.query(UserSession).filter_by(user_id=account.id, tenant_id=tenant_id).delete(synchronize_session=False)
            session.query(UserIdentity).filter_by(user_id=account.id, tenant_id=tenant_id).delete(synchronize_session=False)
            session.query(UserAccount).filter_by(id=account.id).delete(synchronize_session=False)
        session.commit()


def test_login_uses_generic_failure_and_audits_authentication_events(client, monkeypatch):
    email = "login-user@example.com"
    tenant_id = "login-tenant"
    _clean(email, tenant_id)
    registration = client.post(
        "/auth/register",
        json={"username": "login-user", "email": email, "password": "StrongPass123!", "tenant_id": tenant_id},
    ).get_json()
    assert client.post(
        "/auth/verify-email",
        json={"email": email, "token": registration["verification_token"], "tenant_id": tenant_id},
    ).status_code == 200
    clock = [datetime.now(timezone.utc)]
    monkeypatch.setattr(advanced_features, "_now_utc", lambda: clock[0])

    unknown = client.post(
        "/auth/login",
        json={"username": "unknown@example.com", "password": "WrongPass123!", "tenant_id": tenant_id},
    )
    wrong_password = client.post(
        "/auth/login",
        json={"username": email, "password": "WrongPass123!", "tenant_id": tenant_id},
    )
    assert unknown.status_code == wrong_password.status_code == 401
    unknown_payload = unknown.get_json()
    wrong_password_payload = wrong_password.get_json()
    unknown_request_id = unknown_payload.pop("request_id")
    wrong_password_request_id = wrong_password_payload.pop("request_id")
    assert unknown_payload["error"] == wrong_password_payload["error"] == "invalid_credentials"
    assert unknown_payload["message"] == wrong_password_payload["message"] == "Invalid username or password."
    assert unknown_request_id != wrong_password_request_id
    early_success = client.post(
        "/auth/login",
        json={"username": email, "password": "StrongPass123!", "tenant_id": tenant_id},
    )
    assert early_success.status_code == 429
    assert early_success.headers["Retry-After"] == str(early_success.get_json()["retry_after"])
    clock[0] += timedelta(seconds=int(early_success.get_json()["retry_after"]))

    success = client.post(
        "/auth/login",
        json={"username": email, "password": "StrongPass123!", "tenant_id": tenant_id, "device_id": "login-device"},
    )
    assert success.status_code == 200
    user_id = success.get_json()["user"]["id"]

    with SessionLocal() as session:
        assert session.query(UserSession).filter_by(user_id=user_id, tenant_id=tenant_id, active=True).first() is not None
        assert session.query(ActionAuditEntry).filter_by(action="login.failed").count() >= 2
        assert session.query(ActionAuditEntry).filter_by(action="login.succeeded", resource_id=user_id).count() >= 1

    missing = client.post("/auth/login", json={"tenant_id": tenant_id})
    assert missing.status_code == 401
    missing_payload = missing.get_json()
    assert missing_payload["error"] == "invalid_credentials"
    assert missing_payload["message"] == "Invalid email or password."
    _clean(email, tenant_id)
