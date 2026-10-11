"""Security & audit services isolation and fail-closed boundary tests (109-117).

Services 109-117 (security decision, audit log, security events, access control,
secret management, credential rotation, token revocation, threat detection,
IP/device security) are implemented in ``security_audit_services.py`` and share
one security-critical contract: **tenant isolation is fail-closed and every
security decision denies unless a valid, tenant-scoped grant is present.**

``tests/test_security_audit_services.py`` already pins the happy paths (masking,
same-tenant rotation/revocation, high-risk block). This suite closes the
remaining boundary gaps that were not yet pinned:

  - Cross-tenant isolation on secrets, credentials, sessions, and audit reads
    (a tenant-a caller must never read/mutate tenant-b state) -- Rule #11.
  - Access-control fail-closed edges: missing actor, role denied, permission
    denied, tenant-scope mismatch -- Rules #13, #55, #57.
  - Secret revocation and versioned-rotation invalidation of the prior value.
  - Credential rotation isolating per tenant (rotating tenant-a must not revoke
    a tenant-b credential with the same service name).
  - Token revocation tenant scoping: revoke_session under the wrong tenant is a
    no-op; is_revoked honours the tenant filter.
  - Threat detection low/allow path and missing-tenant fail-closed.
  - IP allowlist enforcement, missing-IP denial, and cross-tenant device
    isolation.

All behaviours below were verified against the current implementation first
(Rule #62). No production code is changed here.
"""

from __future__ import annotations

import pytest
from app_4_advanced_features import SessionLocal
from models import AuditEvent, ServiceCredentialRecord, UserSession
from security_audit_services import (
    AccessControlService,
    AuditLogService,
    CredentialRotationService,
    IPDeviceSecurityService,
    SecretManagementService,
    SecurityEventService,
    SecurityService,
    ThreatDetectionService,
    TokenRevocationService,
)


@pytest.fixture()
def session():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def _seed_session(session, *, session_id, tenant_id, user_id, device_id="device-1"):
    record = UserSession(
        id=session_id,
        tenant_id=tenant_id,
        user_id=user_id,
        device_id=device_id,
        active=True,
        state="ACTIVE",
        issued_at=None,
        last_activity_at=None,
    )
    session.add(record)
    session.commit()
    return record


# --- Service 109: Security Service ---------------------------------------------


def test_security_record_requires_tenant(session):
    service = SecurityService(session)
    with pytest.raises(ValueError):
        service.record_event(
            tenant_id="", event_type="login.attempt", actor="user-1",
            aggregate_type="user", aggregate_id="user-1",
        )


def test_security_record_requires_event_type(session):
    service = SecurityService(session)
    with pytest.raises(ValueError):
        service.record_event(
            tenant_id="tenant-a", event_type="", actor="user-1",
            aggregate_type="user", aggregate_id="user-1",
        )


def test_security_sanitize_masks_sensitive_and_keeps_safe():
    cleaned = SecurityService.sanitize_details(
        {"api_key": "abcdef123456", "status": "failed", "password": "hunter2", "count": 3}
    )
    assert cleaned["api_key"] == "ab********56"
    assert cleaned["password"] != "hunter2"
    assert cleaned["status"] == "failed"
    assert cleaned["count"] == 3


# --- Service 110: Audit Log Service --------------------------------------------


def test_audit_list_events_are_tenant_scoped(session):
    audit = AuditLogService(session)
    marker = "audit-scope-probe"
    audit.append(tenant_id="tenant-a", event_type="data.export", actor="user-a",
                 aggregate_type="report", aggregate_id=f"{marker}-a", details={"n": 1})
    audit.append(tenant_id="tenant-b", event_type="data.export", actor="user-b",
                 aggregate_type="report", aggregate_id=f"{marker}-b", details={"n": 2})

    tenant_a_rows = audit.list_events(tenant_id="tenant-a", event_type="data.export", limit=200)
    assert any(row.aggregate_id == f"{marker}-a" for row in tenant_a_rows)
    assert all(row.tenant_id == "tenant-a" for row in tenant_a_rows)
    assert not any(row.aggregate_id == f"{marker}-b" for row in tenant_a_rows)


# --- Service 111: Security Event Service ---------------------------------------


def test_security_event_masks_secret_and_scopes_tenant(session):
    service = SecurityEventService(session)
    event = service.record(
        tenant_id="tenant-a", event_type="login.attempt", actor="user-1",
        aggregate_type="user", aggregate_id="user-1",
        details={"authorization": "Bearer supersecrettoken", "status": "failed"},
        severity="warning",
    )
    assert event.details["authorization"] != "Bearer supersecrettoken"
    assert event.tenant_id == "tenant-a"
    stored = session.query(AuditEvent).filter_by(id=event.id).one()
    assert stored.tenant_id == "tenant-a"


# --- Service 112: Access Control Service ---------------------------------------


def test_access_control_denies_missing_actor(session):
    decision = AccessControlService(session).authorize(
        tenant_id="tenant-a", actor_id="", resource_tenant_id="tenant-a",
        actor_roles=["admin"], actor_permissions=["read:accounts"],
    )
    assert decision.allowed is False
    assert decision.reason == "missing_actor"


def test_access_control_denies_missing_role(session):
    decision = AccessControlService(session).authorize(
        tenant_id="tenant-a", actor_id="user-1", resource_tenant_id="tenant-a",
        required_roles=["admin"], actor_roles=["analyst"], actor_permissions=["read:accounts"],
    )
    assert decision.allowed is False
    assert decision.reason == "role_denied"


def test_access_control_denies_missing_permission(session):
    decision = AccessControlService(session).authorize(
        tenant_id="tenant-a", actor_id="user-1", resource_tenant_id="tenant-a",
        required_permissions=["manage:users"], actor_roles=["admin"], actor_permissions=["read:accounts"],
    )
    assert decision.allowed is False
    assert decision.reason == "permission_denied"


def test_access_control_allows_same_tenant_with_grant(session):
    decision = AccessControlService(session).authorize(
        tenant_id="tenant-a", actor_id="user-1", resource_tenant_id="tenant-a",
        required_roles=["admin"], required_permissions=["read:accounts"],
        actor_roles=["admin"], actor_permissions=["read:accounts"],
    )
    assert decision.allowed is True
    assert decision.reason == "allowed"


def test_access_control_tenant_scope_mismatch(session):
    decision = AccessControlService(session).ensure_tenant_scope(
        tenant_id="tenant-a", actor_tenant_id="tenant-b", actor_id="user-1",
    )
    assert decision.allowed is False
    assert decision.reason == "tenant_scope_mismatch"


# --- Service 113: Secret Management Service ------------------------------------


def test_secret_cross_tenant_read_is_isolated():
    store = SecretManagementService()
    store.create_secret(tenant_id="tenant-a", name="db-password", value="secret-a-value")
    store.create_secret(tenant_id="tenant-b", name="db-password", value="secret-b-value")

    # Same secret name in both tenants; each tenant reads only its own value.
    a = store.get_secret(tenant_id="tenant-a", name="db-password", include_value=True)
    b = store.get_secret(tenant_id="tenant-b", name="db-password", include_value=True)
    assert a["value"] == "secret-a-value"
    assert b["value"] == "secret-b-value"


def test_secret_get_hides_value_by_default():
    store = SecretManagementService()
    store.create_secret(tenant_id="tenant-a", name="api-token", value="plaintext-secret-value")
    payload = store.get_secret(tenant_id="tenant-a", name="api-token")
    assert "value" not in payload
    assert payload["masked"] == "pl********ue"


def test_secret_revoke_invalidates_read():
    store = SecretManagementService()
    store.create_secret(tenant_id="tenant-a", name="rotating", value="first-secret-value")
    assert store.revoke_secret(tenant_id="tenant-a", name="rotating") is True
    # A revoked secret must not be readable.
    assert store.get_secret(tenant_id="tenant-a", name="rotating") is None
    # Revoking again is a safe no-op.
    assert store.revoke_secret(tenant_id="tenant-a", name="rotating") is False


def test_secret_rotate_replaces_previous_value():
    store = SecretManagementService()
    store.create_secret(tenant_id="tenant-a", name="db-password", value="old-secret-value")
    store.rotate_secret(tenant_id="tenant-a", name="db-password", new_value="new-secret-value")
    current = store.get_secret(tenant_id="tenant-a", name="db-password", include_value=True)
    assert current["value"] == "new-secret-value"


def test_secret_rotate_missing_raises():
    store = SecretManagementService()
    with pytest.raises(ValueError):
        store.rotate_secret(tenant_id="tenant-a", name="absent", new_value="whatever-value")


def test_secret_requires_name():
    store = SecretManagementService()
    with pytest.raises(ValueError):
        store.create_secret(tenant_id="tenant-a", name="   ", value="whatever-value")


# --- Service 114: Credential Rotation Service ----------------------------------


def test_credential_rotation_is_tenant_scoped(session):
    # Seed an active credential for tenant-b under a shared service name.
    session.add(ServiceCredentialRecord(
        id="svc-tenant-b-active", tenant_id="tenant-b", service_name="shared-sync",
        credential_type="workload", credential_hash="hash-b", status="active",
    ))
    session.commit()

    rotation = CredentialRotationService(session).rotate(
        tenant_id="tenant-a", service_name="shared-sync", actor="ops",
    )
    assert rotation["credential_id"]

    # tenant-b's active credential must be untouched by a tenant-a rotation.
    b_active = session.query(ServiceCredentialRecord).filter_by(
        tenant_id="tenant-b", service_name="shared-sync", status="active",
    ).one_or_none()
    assert b_active is not None
    assert b_active.id == "svc-tenant-b-active"

    a_active = session.query(ServiceCredentialRecord).filter_by(
        tenant_id="tenant-a", service_name="shared-sync", status="active",
    ).one()
    assert a_active.id == rotation["credential_id"]


def test_credential_rotation_requires_service_name(session):
    with pytest.raises(ValueError):
        CredentialRotationService(session).rotate(tenant_id="tenant-a", service_name="   ", actor="ops")


# --- Service 115: Token Revocation Service -------------------------------------


def test_token_revocation_is_tenant_scoped(session):
    _seed_session(session, session_id="session-iso-1", tenant_id="tenant-b", user_id="user-b")
    service = TokenRevocationService(session)

    # Revoking under the wrong tenant must be a no-op (fail-closed isolation).
    assert service.revoke_session(
        session_id="session-iso-1", tenant_id="tenant-a", reason="admin_logout", revoked_by="admin",
    ) is False
    record = session.query(UserSession).filter_by(id="session-iso-1").one()
    assert record.active is True
    assert record.state == "ACTIVE"


def test_token_revocation_is_revoked_honours_tenant(session):
    _seed_session(session, session_id="session-iso-2", tenant_id="tenant-a", user_id="user-a")
    service = TokenRevocationService(session)
    assert service.revoke_session(
        session_id="session-iso-2", tenant_id="tenant-a", reason="logout", revoked_by="user-a",
    ) is True

    assert service.is_revoked(session_id="session-iso-2", tenant_id="tenant-a") is True
    # A different tenant must not observe the revocation state of tenant-a.
    assert service.is_revoked(session_id="session-iso-2", tenant_id="tenant-b") is False


def test_token_revoke_user_counts_only_that_tenant(session):
    _seed_session(session, session_id="session-iso-a", tenant_id="tenant-a", user_id="user-shared")
    _seed_session(session, session_id="session-iso-b", tenant_id="tenant-b", user_id="user-shared")
    service = TokenRevocationService(session)

    revoked = service.revoke_user(
        user_id="user-shared", tenant_id="tenant-a", reason="account_lock", revoked_by="admin",
    )
    assert revoked == 1
    a = session.query(UserSession).filter_by(id="session-iso-a").one()
    b = session.query(UserSession).filter_by(id="session-iso-b").one()
    assert a.active is False and a.state == "REVOKED"
    assert b.active is True and b.state == "ACTIVE"


# --- Service 116: Threat Detection Service -------------------------------------


def test_threat_detection_low_risk_allows():
    result = ThreatDetectionService().evaluate(
        tenant_id="tenant-a", actor_id="user-1",
        ip_address="203.0.113.10", device_id="known", failure_count=0,
    )
    assert result["decision"] == "allow"
    assert result["risk_level"] == "low"


def test_threat_detection_requires_tenant():
    with pytest.raises(ValueError):
        ThreatDetectionService().evaluate(tenant_id="", actor_id="user-1", failure_count=0)


# --- Service 117: IP / Device Security Service ---------------------------------


def test_ip_allowlist_enforced_when_configured():
    service = IPDeviceSecurityService()
    service.add_allowlist("203.0.113.10")
    assert service.evaluate_ip(tenant_id="tenant-a", ip_address="203.0.113.10")["allowed"] is True
    # Once an allowlist exists, any non-listed IP is denied.
    denied = service.evaluate_ip(tenant_id="tenant-a", ip_address="198.51.100.7")
    assert denied["allowed"] is False
    assert denied["reason"] == "ip_not_allowlisted"


def test_ip_missing_address_denied():
    assert IPDeviceSecurityService().evaluate_ip(tenant_id="tenant-a", ip_address=None)["allowed"] is False


def test_device_validation_is_tenant_scoped():
    service = IPDeviceSecurityService()
    service.register_device(tenant_id="tenant-a", user_id="user-1", device_id="device-x")
    assert service.validate_device(tenant_id="tenant-a", user_id="user-1", device_id="device-x")["allowed"] is True
    # Same device id under a different tenant/user must not validate.
    assert service.validate_device(tenant_id="tenant-b", user_id="user-1", device_id="device-x")["allowed"] is False
    assert service.validate_device(tenant_id="tenant-a", user_id="user-2", device_id="device-x")["allowed"] is False


def test_device_validation_requires_tenant():
    with pytest.raises(ValueError):
        IPDeviceSecurityService().validate_device(tenant_id="", user_id="user-1", device_id="device-x")

