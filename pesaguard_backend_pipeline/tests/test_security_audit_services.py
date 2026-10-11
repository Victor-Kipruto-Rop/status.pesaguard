import time

from app_4_advanced_features import SessionLocal
from models import AuditEvent, ServiceCredentialRecord, UserSession
from security_audit_services import (
    AccessControlService,
    AuditLogService,
    CredentialRotationService,
    IPDeviceSecurityService,
    SecretManagementService,
    SecurityEventService,
    ThreatDetectionService,
    TokenRevocationService,
)


def _make_session(tenant_id: str = "tenant-a"):
    return SessionLocal()


def test_security_service_records_audit_event_and_masks_secrets():
    session = _make_session()
    try:
        service = SecurityEventService(session)
        event = service.record(
            tenant_id="tenant-a",
            event_type="login.attempt",
            actor="user-42",
            aggregate_type="user",
            aggregate_id="user-42",
            details={"api_key": "super-secret-token", "status": "failed"},
            severity="warning",
        )

        assert event.tenant_id == "tenant-a"
        assert event.event_type == "login.attempt"
        assert event.details["api_key"] == "su********en"

        row = session.query(AuditEvent).filter_by(tenant_id="tenant-a", aggregate_id="user-42").one()
        assert row.payload_hash
        assert row.details["api_key"] == "su********en"
    finally:
        session.close()


def test_access_control_secret_rotation_and_revocation_are_tenant_safe():
    session = _make_session()
    try:
        access = AccessControlService(session)
        decision = access.authorize(
            tenant_id="tenant-a",
            actor_id="user-42",
            resource_tenant_id="tenant-b",
            actor_roles=["analyst"],
            actor_permissions=["read:accounts"],
        )
        assert decision.allowed is False
        assert decision.reason == "cross_tenant_denied"

        secret_store = SecretManagementService()
        created = secret_store.create_secret(tenant_id="tenant-a", name="db-password", value="really-secret")
        rotated = secret_store.rotate_secret(tenant_id="tenant-a", name="db-password", new_value="new-secret")
        assert created["masked"] == "re********et"
        assert rotated["masked"] == "ne********et"
        assert secret_store.get_secret(tenant_id="tenant-a", name="db-password")["masked"] == "ne********et"

        existing = ServiceCredentialRecord(
            id="svc-1",
            tenant_id="tenant-a",
            service_name="ledger-sync",
            credential_type="workload",
            credential_hash="hash-1",
            status="active",
        )
        session.add(existing)
        session.commit()

        rotation = CredentialRotationService(session).rotate(tenant_id="tenant-a", service_name="ledger-sync", actor="ops")
        assert rotation["credential_id"]
        active = session.query(ServiceCredentialRecord).filter_by(tenant_id="tenant-a", service_name="ledger-sync", status="active").one()
        assert active.id == rotation["credential_id"]
        revoked = session.query(ServiceCredentialRecord).filter_by(tenant_id="tenant-a", service_name="ledger-sync", status="revoked").one()
        assert revoked.reason == "credential rotation"

        user_session = UserSession(
            id="session-123",
            tenant_id="tenant-a",
            user_id="user-42",
            device_id="device-1",
            active=True,
            state="ACTIVE",
            issued_at=None,
            last_activity_at=None,
        )
        session.add(user_session)
        session.commit()

        token_service = TokenRevocationService(session)
        assert token_service.revoke_session(session_id="session-123", tenant_id="tenant-a", reason="user_logout", revoked_by="user-42") is True
        session.refresh(user_session)
        assert user_session.active is False
        assert user_session.state == "REVOKED"
        assert token_service.is_revoked(session_id="session-123", tenant_id="tenant-a") is True
    finally:
        session.close()


def test_threat_detection_and_device_security_are_fail_closed():
    threat = ThreatDetectionService()
    result = threat.evaluate(
        tenant_id="tenant-a",
        actor_id="user-42",
        ip_address="10.0.0.12",
        device_id="known-device",
        failure_count=5,
        suspicious_device=True,
        geo_mismatch=True,
    )
    assert result["risk_level"] in {"high", "critical"}
    assert result["decision"] == "block"

    device_service = IPDeviceSecurityService()
    device_service.add_denylist("198.51.100.42")
    device_service.register_device(tenant_id="tenant-a", user_id="user-42", device_id="known-device")

    assert device_service.evaluate_ip(tenant_id="tenant-a", ip_address="198.51.100.42")["allowed"] is False
    assert device_service.evaluate_ip(tenant_id="tenant-a", ip_address="8.8.8.8")["allowed"] is True
    assert device_service.validate_device(tenant_id="tenant-a", user_id="user-42", device_id="known-device")["allowed"] is True
    assert device_service.validate_device(tenant_id="tenant-a", user_id="user-42", device_id="missing-device")["allowed"] is False


def test_audit_log_service_and_security_performance_baseline():
    session = _make_session()
    try:
        audit = AuditLogService(session)
        started = time.perf_counter()
        for index in range(100):
            audit.append(
                tenant_id="tenant-a",
                event_type="data.export",
                actor=f"user-{index}",
                aggregate_type="report",
                aggregate_id=f"report-{index}",
                details={"secret": "payload-secret", "count": index},
            )
        elapsed = time.perf_counter() - started
        assert elapsed < 2.0

        rows = audit.list_events(tenant_id="tenant-a", event_type="data.export", limit=5)
        assert len(rows) == 5
        assert all(row.tenant_id == "tenant-a" for row in rows)
        assert rows[0].details["secret"] == "pa********et"
    finally:
        session.close()
