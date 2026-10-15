import json
from datetime import datetime, timedelta, timezone
import uuid

import pytest
import requests

from pesaguard_backend_pipeline.operations import retention_approval


POLICY_SHA256 = "a" * 64
RUN_ID = uuid.uuid4().hex


class FakeResponse:
    def __init__(self, payload=None, status_code=200, headers=None, body=None):
        self.status_code = status_code
        self.body = body if body is not None else json.dumps(payload).encode("utf-8")
        self.headers = headers or {
            "Content-Type": "application/json",
            "Content-Length": str(len(self.body)),
        }
        self.closed = False

    def iter_content(self, chunk_size):
        for start in range(0, len(self.body), chunk_size):
            yield self.body[start:start + chunk_size]

    def close(self):
        self.closed = True


class FakeSession:
    def __init__(self, get_responses=(), post_responses=()):
        self.get_responses = list(get_responses)
        self.post_responses = list(post_responses)
        self.calls = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def close(self):
        self.closed = True

    def get(self, url, **kwargs):
        self.calls.append(("GET", url, kwargs))
        response = self.get_responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response

    def post(self, url, **kwargs):
        self.calls.append(("POST", url, kwargs))
        response = self.post_responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def _configure_client(monkeypatch, session):
    monkeypatch.setenv("PESAGUARD_RETENTION_APPROVAL_API_URL", "https://approvals.example/api")
    monkeypatch.setenv("PESAGUARD_RETENTION_APPROVAL_API_TOKEN", "test-api-token")
    monkeypatch.setattr(retention_approval.requests, "Session", lambda: session)


def _approved_payload(**overrides):
    payload = {
        "status": "approved",
        "approver_id": "finance-approver-42",
        "tenant_id": "tenant-a",
        "policy_sha256": POLICY_SHA256,
        "expires_at": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(),
    }
    payload.update(overrides)
    return payload


def test_policy_digest_is_deterministic_and_binds_the_effective_policy():
    policy = {"retention_days": {"transactions": 2555, "audit": 2555}, "batch_size": 1000}
    reordered = {"batch_size": 1000, "retention_days": {"audit": 2555, "transactions": 2555}}

    assert retention_approval.policy_sha256(policy) == retention_approval.policy_sha256(reordered)
    assert retention_approval.policy_sha256(policy) != retention_approval.policy_sha256(
        {"retention_days": {"transactions": 30, "audit": 2555}, "batch_size": 1000}
    )


def test_verifier_authenticates_and_returns_scoped_approval(monkeypatch):
    session = FakeSession(get_responses=[FakeResponse(_approved_payload())])
    _configure_client(monkeypatch, session)

    approval = retention_approval.verify_retention_approval(
        "RET/2026 004",
        tenant_id="tenant-a",
        expected_policy_sha256=POLICY_SHA256,
    )

    assert approval.approver_id == "finance-approver-42"
    assert approval.tenant_id == "tenant-a"
    assert approval.policy_sha256 == POLICY_SHA256
    method, url, request = session.calls[0]
    assert method == "GET"
    assert url == "https://approvals.example/api/v1/retention-approvals/RET%2F2026%20004"
    assert request["headers"]["Authorization"] == "Bearer test-api-token"
    assert request["timeout"] > 0
    assert request["headers"]["Cache-Control"] == "no-store"
    assert request["verify"] is True
    assert request["allow_redirects"] is False
    assert session.closed


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"status": "pending"}, "not approved"),
        ({"tenant_id": "tenant-b"}, "scope"),
        ({"policy_sha256": "b" * 64}, "policy"),
        ({"approver_id": "  "}, "approver_id"),
        ({"expires_at": (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()}, "expired"),
        ({"expires_at": "2030-01-01T00:00:00"}, "timezone"),
    ],
)
def test_verifier_rejects_unapproved_or_mismatched_responses(monkeypatch, overrides, message):
    session = FakeSession(get_responses=[FakeResponse(_approved_payload(**overrides))])
    _configure_client(monkeypatch, session)

    with pytest.raises(retention_approval.RetentionApprovalError, match=message):
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )


def test_verifier_requires_external_service_configuration(monkeypatch):
    monkeypatch.delenv("PESAGUARD_RETENTION_APPROVAL_API_URL", raising=False)
    monkeypatch.delenv("PESAGUARD_RETENTION_APPROVAL_API_TOKEN", raising=False)

    with pytest.raises(retention_approval.RetentionApprovalError, match="not configured"):
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )


@pytest.mark.parametrize(
    "base_url",
    [
        "http://approvals.example",
        "https://user:password@approvals.example",
        "https://approvals.example?token=private",
        "https://approvals.example/#fragment",
    ],
)
def test_verifier_rejects_unsafe_api_urls(monkeypatch, base_url):
    session = FakeSession()
    _configure_client(monkeypatch, session)
    monkeypatch.setenv("PESAGUARD_RETENTION_APPROVAL_API_URL", base_url)

    with pytest.raises(retention_approval.RetentionApprovalError, match="HTTPS"):
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )
    assert session.calls == []


def test_verifier_fails_closed_on_http_error_without_echoing_response_body(monkeypatch):
    session = FakeSession(get_responses=[FakeResponse(
        {"secret": "must-not-leak"},
        status_code=403,
    )])
    _configure_client(monkeypatch, session)

    with pytest.raises(retention_approval.RetentionApprovalError) as error:
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )
    assert "must-not-leak" not in str(error.value)


def test_verifier_rejects_oversized_and_non_json_responses(monkeypatch):
    oversized = FakeResponse(body=b"x" * (retention_approval.MAX_RESPONSE_BYTES + 1))
    session = FakeSession(get_responses=[oversized])
    _configure_client(monkeypatch, session)

    with pytest.raises(retention_approval.RetentionApprovalError, match="size limit"):
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )

    html = FakeResponse(
        body=b"<html>denied</html>",
        headers={"Content-Type": "text/html", "Content-Length": "19"},
    )
    session = FakeSession(get_responses=[html])
    _configure_client(monkeypatch, session)
    with pytest.raises(retention_approval.RetentionApprovalError, match="JSON"):
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )


def test_verifier_retries_transient_get_failure(monkeypatch):
    session = FakeSession(get_responses=[
        requests.Timeout("transient"),
        FakeResponse(_approved_payload()),
    ])
    _configure_client(monkeypatch, session)

    result = retention_approval.verify_retention_approval(
        "RET-123",
        tenant_id="tenant-a",
        expected_policy_sha256=POLICY_SHA256,
    )

    assert result.approver_id == "finance-approver-42"
    assert len(session.calls) == 2


def test_verifier_retries_http_server_error(monkeypatch):
    session = FakeSession(get_responses=[
        FakeResponse({"error": "temporarily unavailable"}, status_code=503),
        FakeResponse(_approved_payload()),
    ])
    _configure_client(monkeypatch, session)

    result = retention_approval.verify_retention_approval(
        "RET-123",
        tenant_id="tenant-a",
        expected_policy_sha256=POLICY_SHA256,
    )

    assert result.approver_id == "finance-approver-42"
    assert len(session.calls) == 2


def test_verifier_rejects_malformed_content_length(monkeypatch):
    response = FakeResponse(
        _approved_payload(),
        headers={"Content-Type": "application/json", "Content-Length": "unknown"},
    )
    session = FakeSession(get_responses=[response])
    _configure_client(monkeypatch, session)

    with pytest.raises(retention_approval.RetentionApprovalError, match="invalid content length"):
        retention_approval.verify_retention_approval(
            "RET-123",
            tenant_id="tenant-a",
            expected_policy_sha256=POLICY_SHA256,
        )
    assert response.closed


def test_consume_binds_one_time_approval_to_the_cleanup_run(monkeypatch):
    session = FakeSession(post_responses=[FakeResponse({"status": "consumed", "run_id": RUN_ID})])
    _configure_client(monkeypatch, session)
    approval = retention_approval.VerifiedRetentionApproval(
        approver_id="finance-approver-42",
        tenant_id="tenant-a",
        policy_sha256=POLICY_SHA256,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
    )

    retention_approval.consume_retention_approval("RET-123", approval, RUN_ID)

    method, url, request = session.calls[0]
    assert method == "POST"
    assert url == "https://approvals.example/api/v1/retention-approvals/RET-123/consume"
    assert request["json"] == {
        "run_id": RUN_ID,
        "tenant_id": "tenant-a",
        "policy_sha256": POLICY_SHA256,
    }
    assert request["headers"]["Idempotency-Key"] == RUN_ID
    assert session.closed


@pytest.mark.parametrize(
    "payload",
    [
        {"status": "pending", "run_id": RUN_ID},
        {"status": "consumed", "run_id": uuid.uuid4().hex},
    ],
)
def test_consume_rejects_unconsumed_or_wrong_run_response(monkeypatch, payload):
    session = FakeSession(post_responses=[FakeResponse(payload)])
    _configure_client(monkeypatch, session)
    approval = retention_approval.VerifiedRetentionApproval(
        approver_id="finance-approver-42",
        tenant_id="tenant-a",
        policy_sha256=POLICY_SHA256,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
    )

    with pytest.raises(retention_approval.RetentionApprovalError, match="consum"):
        retention_approval.consume_retention_approval("RET-123", approval, RUN_ID)


def test_consume_retries_transient_failure_with_same_idempotency_key(monkeypatch):
    session = FakeSession(post_responses=[
        requests.Timeout("transient"),
        FakeResponse({"status": "consumed", "run_id": RUN_ID}),
    ])
    _configure_client(monkeypatch, session)
    approval = retention_approval.VerifiedRetentionApproval(
        approver_id="finance-approver-42",
        tenant_id="tenant-a",
        policy_sha256=POLICY_SHA256,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
    )

    retention_approval.consume_retention_approval("RET-123", approval, RUN_ID)

    assert len(session.calls) == 2
    assert all(call[2]["headers"]["Idempotency-Key"] == RUN_ID for call in session.calls)


def test_consume_rejects_expired_approval_before_request(monkeypatch):
    session = FakeSession()
    _configure_client(monkeypatch, session)
    approval = retention_approval.VerifiedRetentionApproval(
        approver_id="finance-approver-42",
        tenant_id="tenant-a",
        policy_sha256=POLICY_SHA256,
        expires_at=datetime.now(timezone.utc) - timedelta(seconds=1),
    )

    with pytest.raises(retention_approval.RetentionApprovalError, match="expired before consumption"):
        retention_approval.consume_retention_approval("RET-123", approval, RUN_ID)
    assert session.calls == []


def test_consume_rejects_invalid_run_id_before_request(monkeypatch):
    session = FakeSession()
    _configure_client(monkeypatch, session)
    approval = retention_approval.VerifiedRetentionApproval(
        approver_id="finance-approver-42",
        tenant_id="tenant-a",
        policy_sha256=POLICY_SHA256,
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
    )

    with pytest.raises(retention_approval.RetentionApprovalError, match="run ID is invalid"):
        retention_approval.consume_retention_approval("RET-123", approval, "not-a-uuid")
    assert session.calls == []
