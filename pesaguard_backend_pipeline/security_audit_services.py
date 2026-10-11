"""Security and audit service layer for the PesaGuard backend.

This module implements the tenant-scoped security and audit primitives requested
for the security & audit slice: security decision capture, audit event logging,
access control, secret management, credential rotation, token revocation,
threat evaluation, and IP/device security controls.

The implementation intentionally stays close to the existing PesaGuard data model:
- AuditEvent is the append-only audit ledger.
- UserSession is the canonical active-session source of truth.
- ApiKeyRecord / ServiceCredentialRecord hold issued credentials.
- Security-relevant decisions are recorded through metrics.record_security_event()
  when available.

The design is fail-closed: invalid tenant boundaries, missing trust anchors, or
revoked credentials always deny access unless the caller explicitly supplies a
valid tenant-scoped grant.
"""

from __future__ import annotations

import hashlib
import json
import logging
import secrets
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Mapping, MutableMapping, Sequence

from sqlalchemy import select
from sqlalchemy.orm import Session

from models import AuditEvent, ApiKeyRecord, ServiceCredentialRecord, UserAccount, UserSession

logger = logging.getLogger("pesaguard.security_audit_services")


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _hash_value(value: Any) -> str:
    payload = json.dumps(value, sort_keys=True, default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _mask_secret(value: str | None) -> str:
    if value is None:
        return ""
    if len(value) <= 8:
        return "*" * max(len(value), 1)
    return f"{value[:2]}********{value[-2:]}"


def _normalize_tenant(tenant_id: str | None) -> str:
    candidate = str(tenant_id or "").strip()
    if not candidate:
        raise ValueError("tenant_id is required")
    return candidate


@dataclass(frozen=True)
class SecurityDecision:
    allowed: bool
    reason: str
    tenant_id: str
    actor_id: str
    resource_tenant_id: str | None = None
    risk_score: float = 0.0
    details: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "allowed": self.allowed,
            "reason": self.reason,
            "tenant_id": self.tenant_id,
            "actor_id": self.actor_id,
            "resource_tenant_id": self.resource_tenant_id,
            "risk_score": self.risk_score,
            "details": dict(self.details),
        }


class SecurityService:
    """Central security decision and audit event recorder."""

    def __init__(self, session: Session):
        self.session = session

    @staticmethod
    def sanitize_details(details: Mapping[str, Any] | None) -> dict[str, Any]:
        cleaned: dict[str, Any] = {}
        if not details:
            return cleaned
        for key, value in dict(details).items():
            key_name = str(key).lower()
            if any(token in key_name for token in ("secret", "token", "password", "key", "authorization", "cookie")):
                cleaned[key] = _mask_secret(str(value)) if value is not None else None
            else:
                cleaned[key] = value
        return cleaned

    def record_event(
        self,
        *,
        tenant_id: str,
        event_type: str,
        actor: str,
        aggregate_type: str,
        aggregate_id: str,
        details: Mapping[str, Any] | None = None,
        severity: str = "info",
    ) -> AuditEvent:
        _normalize_tenant(tenant_id)
        if not event_type:
            raise ValueError("event_type is required")
        payload = {
            "event_type": event_type,
            "actor": actor,
            "aggregate_type": aggregate_type,
            "aggregate_id": aggregate_id,
            "details": self.sanitize_details(details),
            "severity": severity,
        }
        event_key = f"security:{uuid.uuid4().hex}"
        record = AuditEvent(
            id=f"audit_{uuid.uuid4().hex}",
            tenant_id=tenant_id,
            event_key=event_key,
            event_type=event_type,
            aggregate_type=aggregate_type,
            aggregate_id=aggregate_id,
            actor=actor,
            payload_hash=_hash_value(payload),
            details=self.sanitize_details(details),
            created_at=_utc_now(),
        )
        self.session.add(record)
        self.session.flush()
        try:
            from metrics import record_security_event

            record_security_event()
        except Exception:
            logger.debug("Security metric hook unavailable", exc_info=True)
        return record


class AuditLogService:
    """Append-only audit log with tenant scoping and search helpers."""

    def __init__(self, session: Session):
        self.session = session

    def append(
        self,
        *,
        tenant_id: str,
        event_type: str,
        actor: str,
        aggregate_type: str,
        aggregate_id: str,
        details: Mapping[str, Any] | None = None,
    ) -> AuditEvent:
        return SecurityService(self.session).record_event(
            tenant_id=tenant_id,
            event_type=event_type,
            actor=actor,
            aggregate_type=aggregate_type,
            aggregate_id=aggregate_id,
            details=details,
            severity="info",
        )

    def list_events(
        self,
        *,
        tenant_id: str | None = None,
        event_type: str | None = None,
        aggregate_type: str | None = None,
        aggregate_id: str | None = None,
        actor: str | None = None,
        limit: int = 50,
    ) -> list[AuditEvent]:
        query = self.session.query(AuditEvent)
        if tenant_id is not None:
            query = query.filter(AuditEvent.tenant_id == tenant_id)
        if event_type is not None:
            query = query.filter(AuditEvent.event_type == event_type)
        if aggregate_type is not None:
            query = query.filter(AuditEvent.aggregate_type == aggregate_type)
        if aggregate_id is not None:
            query = query.filter(AuditEvent.aggregate_id == aggregate_id)
        if actor is not None:
            query = query.filter(AuditEvent.actor == actor)
        return query.order_by(AuditEvent.created_at.desc()).limit(max(limit, 1)).all()

    @staticmethod
    def redact_event(event: AuditEvent) -> dict[str, Any]:
        redacted = dict(event.details or {})
        for key in list(redacted):
            lowered = str(key).lower()
            if any(token in lowered for token in ("secret", "token", "password", "authorization", "cookie")):
                redacted[key] = _mask_secret(str(redacted[key])) if redacted[key] is not None else None
        return {
            "id": event.id,
            "tenant_id": event.tenant_id,
            "event_type": event.event_type,
            "aggregate_type": event.aggregate_type,
            "aggregate_id": event.aggregate_id,
            "actor": event.actor,
            "details": redacted,
            "created_at": event.created_at.isoformat() if event.created_at else None,
        }


class SecurityEventService:
    """Thin event façade that records security decisions and attaches observability."""

    def __init__(self, session: Session):
        self.session = session
        self.security_service = SecurityService(session)

    def record(
        self,
        *,
        tenant_id: str,
        event_type: str,
        actor: str,
        aggregate_type: str,
        aggregate_id: str,
        details: Mapping[str, Any] | None = None,
        severity: str = "warning",
    ) -> AuditEvent:
        return self.security_service.record_event(
            tenant_id=tenant_id,
            event_type=event_type,
            actor=actor,
            aggregate_type=aggregate_type,
            aggregate_id=aggregate_id,
            details=details,
            severity=severity,
        )


class AccessControlService:
    """Role and tenant enforcement for protected resources."""

    def __init__(self, session: Session):
        self.session = session

    def authorize(
        self,
        *,
        tenant_id: str,
        actor_id: str,
        resource_tenant_id: str | None = None,
        required_roles: Sequence[str] | None = None,
        required_permissions: Sequence[str] | None = None,
        actor_roles: Sequence[str] | None = None,
        actor_permissions: Sequence[str] | None = None,
    ) -> SecurityDecision:
        tenant_id = _normalize_tenant(tenant_id)
        actor_id = str(actor_id or "").strip()
        if not actor_id:
            return SecurityDecision(False, "missing_actor", tenant_id, actor_id, resource_tenant_id)

        if resource_tenant_id is not None and resource_tenant_id != tenant_id:
            return SecurityDecision(False, "cross_tenant_denied", tenant_id, actor_id, resource_tenant_id)

        roles = set(str(item) for item in (actor_roles or ()))
        perms = set(str(item) for item in (actor_permissions or ()))
        if required_roles:
            if not roles.intersection(str(item) for item in required_roles):
                return SecurityDecision(False, "role_denied", tenant_id, actor_id, resource_tenant_id)
        if required_permissions:
            if not perms.intersection(str(item) for item in required_permissions):
                return SecurityDecision(False, "permission_denied", tenant_id, actor_id, resource_tenant_id)
        return SecurityDecision(True, "allowed", tenant_id, actor_id, resource_tenant_id, details={"roles": sorted(roles), "permissions": sorted(perms)})

    def ensure_tenant_scope(self, *, tenant_id: str, actor_tenant_id: str, actor_id: str) -> SecurityDecision:
        if actor_tenant_id != tenant_id:
            return SecurityDecision(False, "tenant_scope_mismatch", tenant_id, actor_id, tenant_id)
        return SecurityDecision(True, "tenant_scope_ok", tenant_id, actor_id, tenant_id)


class SecretManagementService:
    """Secret store with versioning, rotation, and safe masked reads."""

    def __init__(self, session: Session | None = None):
        self.session = session
        self._secrets: dict[str, dict[str, Any]] = {}
        self._version_index: dict[str, list[str]] = {}

    def create_secret(self, *, tenant_id: str, name: str, value: str, metadata: Mapping[str, Any] | None = None) -> dict[str, Any]:
        tenant_id = _normalize_tenant(tenant_id)
        secret_name = str(name or "").strip()
        if not secret_name:
            raise ValueError("secret name is required")
        secret_id = f"secret_{uuid.uuid4().hex}"
        record = {
            "id": secret_id,
            "tenant_id": tenant_id,
            "name": secret_name,
            "value": value,
            "created_at": _utc_now(),
            "updated_at": _utc_now(),
            "expires_at": None,
            "revoked": False,
            "metadata": dict(metadata or {}),
        }
        self._secrets[secret_id] = record
        self._version_index.setdefault(secret_name, []).append(secret_id)
        return {"id": secret_id, "name": secret_name, "masked": _mask_secret(value), "tenant_id": tenant_id}

    def get_secret(self, *, tenant_id: str, name: str, include_value: bool = False) -> dict[str, Any] | None:
        tenant_id = _normalize_tenant(tenant_id)
        matches = [
            secret
            for secret in self._secrets.values()
            if secret["tenant_id"] == tenant_id and secret["name"] == name and not secret["revoked"]
        ]
        if not matches:
            return None
        newest = max(matches, key=lambda secret: secret["created_at"])
        payload = {
            "id": newest["id"],
            "name": newest["name"],
            "tenant_id": newest["tenant_id"],
            "masked": _mask_secret(str(newest["value"])),
        }
        if include_value:
            payload["value"] = newest["value"]
        return payload

    def rotate_secret(self, *, tenant_id: str, name: str, new_value: str, metadata: Mapping[str, Any] | None = None) -> dict[str, Any]:
        current = self.get_secret(tenant_id=tenant_id, name=name)
        if current is None:
            raise ValueError("secret not found")
        secret_id = f"secret_{uuid.uuid4().hex}"
        record = {
            "id": secret_id,
            "tenant_id": tenant_id,
            "name": name,
            "value": new_value,
            "created_at": _utc_now(),
            "updated_at": _utc_now(),
            "expires_at": None,
            "revoked": False,
            "metadata": dict(metadata or {}),
            "previous_id": current["id"],
        }
        self._secrets[secret_id] = record
        self._version_index.setdefault(name, []).append(secret_id)
        return {"id": secret_id, "name": name, "masked": _mask_secret(new_value), "previous_id": current["id"]}

    def revoke_secret(self, *, tenant_id: str, name: str) -> bool:
        tenant_id = _normalize_tenant(tenant_id)
        for secret in self._secrets.values():
            if secret["tenant_id"] == tenant_id and secret["name"] == name and not secret["revoked"]:
                secret["revoked"] = True
                secret["updated_at"] = _utc_now()
                return True
        return False

    def list_secrets(self, *, tenant_id: str) -> list[dict[str, Any]]:
        tenant_id = _normalize_tenant(tenant_id)
        result = []
        for secret in self._secrets.values():
            if secret["tenant_id"] == tenant_id:
                result.append({"id": secret["id"], "name": secret["name"], "masked": _mask_secret(str(secret["value"])), "revoked": secret["revoked"], "created_at": secret["created_at"].isoformat()})
        result.sort(key=lambda item: item["created_at"])
        return result


class CredentialRotationService:
    """Rotate workload credentials while preserving the last credential as revoked."""

    def __init__(self, session: Session):
        self.session = session

    def rotate(self, *, tenant_id: str, service_name: str, credential_type: str = "workload", actor: str = "system") -> dict[str, Any]:
        tenant_id = _normalize_tenant(tenant_id)
        service_name = str(service_name or "").strip()
        if not service_name:
            raise ValueError("service_name is required")

        existing = self.session.query(ServiceCredentialRecord).filter_by(tenant_id=tenant_id, service_name=service_name, status="active").order_by(ServiceCredentialRecord.created_at.desc()).first()
        new_secret = secrets.token_urlsafe(32)
        new_record = ServiceCredentialRecord(
            id=f"svc_{uuid.uuid4().hex}",
            tenant_id=tenant_id,
            service_name=service_name,
            credential_type=credential_type,
            credential_hash=hashlib.sha256(new_secret.encode("utf-8")).hexdigest(),
            status="active",
            created_at=_utc_now(),
            revoked_by=None,
            reason=None,
            credential_metadata={"rotated_by": actor, "rotated_at": _utc_now().isoformat()},
        )
        self.session.add(new_record)
        if existing is not None:
            existing.status = "revoked"
            existing.revoked_at = _utc_now()
            existing.revoked_by = actor
            existing.reason = "credential rotation"
        self.session.flush()
        return {"tenant_id": tenant_id, "service_name": service_name, "credential": _mask_secret(new_secret), "credential_id": new_record.id}


class TokenRevocationService:
    """Revoke sessions, user tokens, and device activity with fail-closed behavior."""

    def __init__(self, session: Session):
        self.session = session
        self._revoked_tokens: dict[str, dict[str, Any]] = {}

    def revoke_session(self, *, session_id: str, tenant_id: str, reason: str, revoked_by: str) -> bool:
        tenant_id = _normalize_tenant(tenant_id)
        session_record = self.session.query(UserSession).filter_by(id=session_id, tenant_id=tenant_id).one_or_none()
        if session_record is None:
            return False
        session_record.active = False
        session_record.state = "REVOKED"
        session_record.revoked_at = _utc_now()
        self._revoked_tokens[session_id] = {"tenant_id": tenant_id, "reason": reason, "revoked_by": revoked_by, "revoked_at": _utc_now().isoformat()}
        self.session.flush()
        return True

    def revoke_user(self, *, user_id: str, tenant_id: str, reason: str, revoked_by: str) -> int:
        tenant_id = _normalize_tenant(tenant_id)
        records = self.session.query(UserSession).filter_by(user_id=user_id, tenant_id=tenant_id).all()
        for record in records:
            record.active = False
            record.state = "REVOKED"
            record.revoked_at = _utc_now()
            self._revoked_tokens[record.id] = {"tenant_id": tenant_id, "reason": reason, "revoked_by": revoked_by, "revoked_at": _utc_now().isoformat()}
        self.session.flush()
        return len(records)

    def is_revoked(self, *, session_id: str, tenant_id: str | None = None) -> bool:
        record = self._revoked_tokens.get(session_id)
        if record is None:
            return False
        if tenant_id is not None and record.get("tenant_id") != tenant_id:
            return False
        return True


class ThreatDetectionService:
    """Simple rules-based threat engine with bounded risk scoring."""

    def __init__(self, session: Session | None = None):
        self.session = session

    @staticmethod
    def _risk_level(score: float) -> str:
        if score >= 0.85:
            return "critical"
        if score >= 0.6:
            return "high"
        if score >= 0.3:
            return "medium"
        return "low"

    def evaluate(
        self,
        *,
        tenant_id: str,
        actor_id: str,
        ip_address: str | None = None,
        device_id: str | None = None,
        failure_count: int = 0,
        suspicious_device: bool = False,
        geo_mismatch: bool = False,
    ) -> dict[str, Any]:
        _normalize_tenant(tenant_id)
        score = 0.0
        score += min(failure_count * 0.12, 0.45)
        score += 0.2 if suspicious_device else 0.0
        score += 0.25 if geo_mismatch else 0.0
        if ip_address and ("127.0.0.1" in ip_address or ip_address.startswith("10.") or ip_address.startswith("192.168.")):
            score += 0.1
        score = min(score, 0.99)
        return {
            "tenant_id": tenant_id,
            "actor_id": actor_id,
            "risk_score": round(score, 4),
            "risk_level": self._risk_level(score),
            "decision": "block" if score >= 0.6 else "monitor" if score >= 0.3 else "allow",
            "ip_address": ip_address,
            "device_id": device_id,
        }


class IPDeviceSecurityService:
    """Safe, tenant-scoped IP and device trust checks."""

    def __init__(self, session: Session | None = None):
        self.session = session
        self._denylist: set[str] = set()
        self._allowlist: set[str] = set()
        self._known_devices: dict[str, dict[str, Any]] = {}

    def add_denylist(self, value: str) -> None:
        self._denylist.add(str(value).strip())

    def add_allowlist(self, value: str) -> None:
        self._allowlist.add(str(value).strip())

    def register_device(self, *, tenant_id: str, user_id: str, device_id: str, metadata: Mapping[str, Any] | None = None) -> None:
        self._known_devices[f"{tenant_id}:{user_id}:{device_id}"] = {
            "tenant_id": tenant_id,
            "user_id": user_id,
            "device_id": device_id,
            "metadata": dict(metadata or {}),
            "registered_at": _utc_now().isoformat(),
        }

    def evaluate_ip(self, *, tenant_id: str, ip_address: str | None) -> dict[str, Any]:
        tenant_id = _normalize_tenant(tenant_id)
        if not ip_address:
            return {"allowed": False, "reason": "missing_ip", "tenant_id": tenant_id}
        if ip_address in self._denylist:
            return {"allowed": False, "reason": "ip_blacklisted", "tenant_id": tenant_id, "ip_address": ip_address}
        if self._allowlist and ip_address not in self._allowlist:
            return {"allowed": False, "reason": "ip_not_allowlisted", "tenant_id": tenant_id, "ip_address": ip_address}
        return {"allowed": True, "reason": "ip_allowed", "tenant_id": tenant_id, "ip_address": ip_address}

    def validate_device(self, *, tenant_id: str, user_id: str, device_id: str) -> dict[str, Any]:
        tenant_id = _normalize_tenant(tenant_id)
        key = f"{tenant_id}:{user_id}:{device_id}"
        if key not in self._known_devices:
            return {"allowed": False, "reason": "unknown_device", "tenant_id": tenant_id, "device_id": device_id}
        return {"allowed": True, "reason": "known_device", "tenant_id": tenant_id, "device_id": device_id}


__all__ = [
    "SecurityDecision",
    "SecurityService",
    "AuditLogService",
    "SecurityEventService",
    "AccessControlService",
    "SecretManagementService",
    "CredentialRotationService",
    "TokenRevocationService",
    "ThreatDetectionService",
    "IPDeviceSecurityService",
]
