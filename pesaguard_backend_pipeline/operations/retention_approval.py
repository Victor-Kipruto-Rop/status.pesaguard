"""Fail-closed client for externally approved retention cleanup runs."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping, Optional
from urllib.parse import quote, urlsplit

import requests


API_URL_ENV = "PESAGUARD_RETENTION_APPROVAL_API_URL"
API_TOKEN_ENV = "PESAGUARD_RETENTION_APPROVAL_API_TOKEN"
REQUEST_TIMEOUT_SECONDS = 5
MAX_RESPONSE_BYTES = 64 * 1024
MAX_APPROVER_ID_LENGTH = 255
MAX_TENANT_ID_LENGTH = 128
_REQUEST_ATTEMPTS = 2
_RETRY_DELAY_SECONDS = 0.1


class RetentionApprovalError(RuntimeError):
    """Raised when approval verification or one-time consumption fails."""


@dataclass(frozen=True)
class VerifiedRetentionApproval:
    approver_id: str
    tenant_id: str
    policy_sha256: str
    expires_at: datetime


def policy_sha256(policy_snapshot: Mapping[str, Any]) -> str:
    """Hash a canonical JSON representation of the effective cleanup policy."""
    try:
        encoded = json.dumps(
            policy_snapshot,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise RetentionApprovalError("effective retention policy is not canonical JSON") from exc
    return hashlib.sha256(encoded).hexdigest()


def _required_config() -> tuple[str, str]:
    base_url = os.getenv(API_URL_ENV, "").strip()
    token = os.getenv(API_TOKEN_ENV, "")
    if not base_url or not token:
        raise RetentionApprovalError("external retention approval API is not configured")
    if token != token.strip() or any(character.isspace() for character in token):
        raise RetentionApprovalError("external retention approval API token is invalid")
    try:
        parsed = urlsplit(base_url)
        _ = parsed.port
    except ValueError as exc:
        raise RetentionApprovalError("external retention approval API URL is invalid") from exc
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise RetentionApprovalError("external retention approval API URL must be an HTTPS URL without credentials or overrides")
    return base_url.rstrip("/"), token


def _required_text(value: Any, field: str, max_length: int) -> str:
    if not isinstance(value, str):
        raise RetentionApprovalError(f"approval response {field} is invalid")
    normalized = value.strip()
    if not normalized or len(normalized) > max_length:
        raise RetentionApprovalError(f"approval response {field} is invalid")
    return normalized


def _read_json_response(response: requests.Response) -> dict[str, Any]:
    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/json" and not content_type.endswith("+json"):
        response.close()
        raise RetentionApprovalError("external retention approval API returned a non-JSON response")

    content_length = response.headers.get("Content-Length")
    if content_length:
        try:
            if int(content_length) > MAX_RESPONSE_BYTES:
                response.close()
                raise RetentionApprovalError("external retention approval response exceeds the size limit")
        except ValueError as exc:
            response.close()
            raise RetentionApprovalError("external retention approval response has invalid content length") from exc

    body = bytearray()
    try:
        for chunk in response.iter_content(chunk_size=8192):
            if not chunk:
                continue
            body.extend(chunk)
            if len(body) > MAX_RESPONSE_BYTES:
                raise RetentionApprovalError("external retention approval response exceeds the size limit")
    except requests.RequestException as exc:
        raise RetentionApprovalError("failed reading external retention approval response") from exc
    finally:
        response.close()

    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RetentionApprovalError("external retention approval API returned invalid JSON") from exc
    if not isinstance(payload, dict):
        raise RetentionApprovalError("external retention approval response must be a JSON object")
    return payload


def _request_json(
    session: requests.Session,
    method: str,
    url: str,
    token: str,
    payload: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    headers = {
        "Accept": "application/json",
        "Cache-Control": "no-store",
        "Authorization": f"Bearer {token}",
    }
    if method == "POST":
        headers["Content-Type"] = "application/json"
        headers["Idempotency-Key"] = str(payload["run_id"])

    request = getattr(session, method.lower())
    for attempt in range(_REQUEST_ATTEMPTS):
        try:
            response = request(
                url,
                headers=headers,
                json=dict(payload) if payload is not None else None,
                timeout=REQUEST_TIMEOUT_SECONDS,
                verify=True,
                allow_redirects=False,
                stream=True,
            )
        except requests.RequestException as exc:
            if attempt + 1 < _REQUEST_ATTEMPTS:
                time.sleep(_RETRY_DELAY_SECONDS)
                continue
            raise RetentionApprovalError("external retention approval API request failed") from exc

        if response.status_code >= 500 and attempt + 1 < _REQUEST_ATTEMPTS:
            response.close()
            time.sleep(_RETRY_DELAY_SECONDS)
            continue
        if response.status_code != 200:
            status = response.status_code
            response.close()
            raise RetentionApprovalError(f"external retention approval API returned HTTP {status}")
        return _read_json_response(response)

    raise RetentionApprovalError("external retention approval API request failed")


def _approval_url(base_url: str, reference: str, suffix: str = "") -> str:
    escaped_reference = quote(reference, safe="")
    return f"{base_url}/v1/retention-approvals/{escaped_reference}{suffix}"


def _parse_expiry(value: Any) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise RetentionApprovalError("approval response expires_at is invalid")
    try:
        expires_at = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError as exc:
        raise RetentionApprovalError("approval response expires_at is invalid") from exc
    if expires_at.utcoffset() is None:
        raise RetentionApprovalError("approval response expires_at must include a timezone")
    if expires_at <= datetime.now(timezone.utc):
        raise RetentionApprovalError("retention approval has expired")
    return expires_at.astimezone(timezone.utc)


def verify_retention_approval(
    reference: str,
    *,
    tenant_id: Optional[str],
    expected_policy_sha256: str,
) -> VerifiedRetentionApproval:
    reference = _required_text(reference, "reference", 255)
    if not isinstance(expected_policy_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_policy_sha256):
        raise RetentionApprovalError("expected retention policy hash is invalid")
    expected_tenant = tenant_id or "all"
    base_url, token = _required_config()

    with requests.Session() as session:
        payload = _request_json(
            session,
            "GET",
            _approval_url(base_url, reference),
            token,
        )

    if payload.get("status") != "approved":
        raise RetentionApprovalError("retention approval is not approved")
    approver_id = _required_text(payload.get("approver_id"), "approver_id", MAX_APPROVER_ID_LENGTH)
    approved_tenant = _required_text(payload.get("tenant_id"), "tenant_id", MAX_TENANT_ID_LENGTH)
    if approved_tenant != expected_tenant:
        raise RetentionApprovalError("retention approval scope does not match cleanup scope")
    supplied_policy_hash = payload.get("policy_sha256")
    if not isinstance(supplied_policy_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", supplied_policy_hash):
        raise RetentionApprovalError("approval response policy hash is invalid")
    if not hmac.compare_digest(supplied_policy_hash, expected_policy_sha256):
        raise RetentionApprovalError("retention approval policy hash does not match")

    return VerifiedRetentionApproval(
        approver_id=approver_id,
        tenant_id=approved_tenant,
        policy_sha256=supplied_policy_hash,
        expires_at=_parse_expiry(payload.get("expires_at")),
    )


def consume_retention_approval(
    reference: str,
    approval: VerifiedRetentionApproval,
    run_id: str,
) -> None:
    reference = _required_text(reference, "reference", 255)
    if not isinstance(approval, VerifiedRetentionApproval):
        raise RetentionApprovalError("verified retention approval is required")
    if not isinstance(approval.policy_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", approval.policy_sha256):
        raise RetentionApprovalError("verified retention approval policy hash is invalid")
    approved_tenant = _required_text(approval.tenant_id, "tenant_id", MAX_TENANT_ID_LENGTH)
    if not isinstance(approval.expires_at, datetime) or approval.expires_at.utcoffset() is None:
        raise RetentionApprovalError("verified retention approval expiry is invalid")
    if approval.expires_at <= datetime.now(timezone.utc):
        raise RetentionApprovalError("retention approval expired before consumption")
    try:
        normalized_run_id = uuid.UUID(run_id).hex
    except (AttributeError, ValueError) as exc:
        raise RetentionApprovalError("retention cleanup run ID is invalid") from exc
    if normalized_run_id != run_id:
        raise RetentionApprovalError("retention cleanup run ID is invalid")

    base_url, token = _required_config()
    payload = {
        "run_id": run_id,
        "tenant_id": approved_tenant,
        "policy_sha256": approval.policy_sha256,
    }
    with requests.Session() as session:
        response = _request_json(
            session,
            "POST",
            _approval_url(base_url, reference, "/consume"),
            token,
            payload,
        )

    if response.get("status") != "consumed" or response.get("run_id") != run_id:
        raise RetentionApprovalError("external retention approval API did not confirm consumption for this run")
