"""OTP / MFA service tests (service 11).

Previously a placeholder left behind by a truncated editing session. Restored
with real coverage over the OTP/MFA surface:

* Unit: RFC 6238 TOTP vectors, clock-skew windowing, malformed-code rejection,
  peppered one-time recovery codes, tenant policy normalization bounds.
* Unit (store): TOTP replay rejection, attempt-limit lockout, recovery-code
  single-use semantics, cross-tenant factor isolation, append-only MFA events.
* Integration (HTTP): challenge issuance never leaks the code, verification is
  single-use, attempt caps terminate the challenge, tenant scoping, and the
  ``manage:mfa`` authorization boundary.
* Observability: failed verifications are recorded in security telemetry.
"""

from __future__ import annotations

import hashlib
import time
from datetime import datetime, timedelta, timezone

import pytest

from test_config import configure_test_database

configure_test_database()

import app_4_advanced_features as advanced_features
from app_4_advanced_features import SessionLocal, app
from auth_rbac import AuthRBAC
from data_protection import encrypt_value
from metrics import telemetry_snapshot
from models import MFAChallenge, MFAEvent, MFAFactor, MFARecovery, UserAccount

# RFC 6238 Appendix B secret: ASCII "12345678901234567890" in Base32.
RFC6238_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"


# ---------------------------------------------------------------------------
# Fixtures & helpers
# ---------------------------------------------------------------------------


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as test_client:
        yield test_client


def _purge_tenant(tenant_id: str, user_id: str | None = None) -> None:
    with SessionLocal() as session:
        session.query(MFAEvent).filter_by(tenant_id=tenant_id).delete(synchronize_session=False)
        session.query(MFAChallenge).filter_by(tenant_id=tenant_id).delete(synchronize_session=False)
        session.query(MFAFactor).filter_by(tenant_id=tenant_id).delete(synchronize_session=False)
        session.query(MFARecovery).filter_by(tenant_id=tenant_id).delete(synchronize_session=False)
        if user_id:
            session.query(UserAccount).filter_by(id=user_id).delete(synchronize_session=False)
        session.commit()


def _make_user(user_id: str, tenant_id: str, roles: list) -> UserAccount:
    with SessionLocal() as session:
        session.query(UserAccount).filter_by(id=user_id).delete(synchronize_session=False)
        session.add(UserAccount(
            id=user_id,
            username=user_id.replace("_", "-"),
            tenant_id=tenant_id,
            roles=roles,
            permissions=[],
            status="active",
            authorization_version=1,
        ))
        session.commit()
        return session.query(UserAccount).filter_by(id=user_id).one()


def _add_totp_factor(tenant_id: str, user_id: str, secret: str) -> str:
    factor_id = f"mfa_factor_test_{user_id}"
    with SessionLocal() as session:
        session.query(MFAFactor).filter_by(id=factor_id).delete(synchronize_session=False)
        session.add(MFAFactor(
            id=factor_id,
            tenant_id=tenant_id,
            user_id=user_id,
            factor_type="totp",
            factor_kind="totp",
            display_name="Test authenticator",
            secret_encrypted=encrypt_value(secret),
            status="active",
            failed_attempts=0,
            last_used_counter=None,
        ))
        session.commit()
    return factor_id


def _events(tenant_id: str, event_type: str | None = None) -> list:
    with SessionLocal() as session:
        query = session.query(MFAEvent).filter_by(tenant_id=tenant_id)
        if event_type:
            query = query.filter_by(event_type=event_type)
        return query.order_by(MFAEvent.created_at).all()


# ---------------------------------------------------------------------------
# Unit: TOTP primitives (RFC 6238)
# ---------------------------------------------------------------------------


def test_totp_code_matches_rfc6238_vectors():
    """The six-digit truncation must match RFC 6238's published SHA-1 vectors."""
    vectors = {
        59: "287082",            # RFC 8-digit value 94287082
        1111111109: "081804",    # RFC 07081804
        1111111111: "050471",    # RFC 14050471
        1234567890: "005924",    # RFC 89005924
        2000000000: "279037",    # RFC 69279037
    }
    for timestamp, expected in vectors.items():
        assert advanced_features._totp_code(RFC6238_SECRET, timestamp) == expected, timestamp


def test_totp_code_is_deterministic_within_a_step_and_changes_across_steps():
    step_a = 30 * 1_000_000
    step_b = step_a + 30
    assert advanced_features._totp_code(RFC6238_SECRET, step_a) == advanced_features._totp_code(
        RFC6238_SECRET, step_a + 29
    )
    assert advanced_features._totp_code(RFC6238_SECRET, step_a) != advanced_features._totp_code(
        RFC6238_SECRET, step_b
    )


def test_totp_code_normalizes_secret_whitespace_and_case():
    expected = advanced_features._totp_code("gezdgnbvgy3tqojqgezdgnbvgy3tqojq", 59)
    spaced = " GEZDGNBV GY3TQOJQ GEZDGNBV GY3TQOJQ "
    assert advanced_features._totp_code(spaced, 59) == expected == "287082"


def test_matching_counter_accepts_configured_clock_skew_only():
    now = int(time.time() // 30) * 30
    current_code = advanced_features._totp_code(RFC6238_SECRET, now)

    # Same step matches.
    assert (
        advanced_features._matching_totp_counter(
            RFC6238_SECRET, current_code, clock_skew_steps=1, timestamp=now
        )
        == now // 30
    )
    # One step back still matches with the default skew of 1.
    assert (
        advanced_features._matching_totp_counter(
            RFC6238_SECRET, current_code, clock_skew_steps=1, timestamp=now + 30
        )
        == now // 30
    )
    # Two steps away is outside the default window.
    assert (
        advanced_features._matching_totp_counter(
            RFC6238_SECRET, current_code, clock_skew_steps=1, timestamp=now + 60
        )
        is None
    )
    # A wider (still bounded) window accepts it.
    assert (
        advanced_features._matching_totp_counter(
            RFC6238_SECRET, current_code, clock_skew_steps=2, timestamp=now + 60
        )
        == now // 30
    )


@pytest.mark.parametrize("bad_code", ["", "12345", "1234567", "abcdef", "12 345", None, 123456, "00000z"])
def test_matching_counter_rejects_malformed_codes(bad_code):
    assert advanced_features._matching_totp_counter(RFC6238_SECRET, bad_code) is None
    assert advanced_features._verify_totp(RFC6238_SECRET, bad_code) is False


def test_verify_totp_accepts_current_code_and_rejects_wrong_code():
    code = advanced_features._totp_code(RFC6238_SECRET)
    assert advanced_features._verify_totp(RFC6238_SECRET, code) is True
    wrong = "000000" if code != "000000" else "999999"
    assert advanced_features._verify_totp(RFC6238_SECRET, wrong) is (
        wrong == advanced_features._totp_code(RFC6238_SECRET)
    )


# ---------------------------------------------------------------------------
# Unit: recovery codes
# ---------------------------------------------------------------------------


def test_recovery_code_hashes_are_peppered_and_never_plaintext():
    code = "AABBCCDDEEFF0011"
    digest = advanced_features._hash_recovery_code(code)
    assert digest == advanced_features._hash_recovery_code(code)  # deterministic
    assert digest != code
    assert len(digest) == 64
    assert code not in digest


def test_recovery_code_hash_binds_to_the_pepper(monkeypatch):
    code = "AABBCCDDEEFF0011"
    baseline = advanced_features._hash_recovery_code(code)
    monkeypatch.setenv("JWT_SECRET_KEY", "a-completely-different-pepper-value-at-least-32")
    assert advanced_features._hash_recovery_code(code) != baseline


def test_new_recovery_codes_are_unique_and_stored_hashed():
    codes, hashes = advanced_features._new_recovery_codes(10)
    assert len(codes) == len(hashes) == 10
    assert len(set(codes)) == 10
    for code, digest in zip(codes, hashes):
        assert digest == advanced_features._hash_recovery_code(code)
        assert code not in digest


# ---------------------------------------------------------------------------
# Unit: policy normalization
# ---------------------------------------------------------------------------


def test_policy_normalization_restores_totp_when_no_factor_allowed():
    # An explicitly empty factor list is reduced to the fail-safe default.
    policy = advanced_features._normalize_mfa_policy({"allowed_factors": []}, "t")
    assert policy is not None
    assert policy["allowed_factors"] == ["totp"]  # fail-safe: never zero factors
    # Unknown factor names fall back to the documented defaults instead of
    # leaving the tenant with an unusable or attacker-influenced policy.
    fallback = advanced_features._normalize_mfa_policy({"allowed_factors": ["sms", "email"]}, "t")
    assert fallback is not None
    assert fallback["allowed_factors"] == ["totp", "webauthn"]


def test_policy_normalization_clamps_integer_controls_to_safe_bounds():
    policy = advanced_features._normalize_mfa_policy(
        {"attempt_limit": 9999, "totp_clock_skew_steps": 42, "lockout_seconds": 1,
         "challenge_ttl_seconds": 99_999, "recovery_code_count": 0},
        "t",
    )
    assert policy is not None
    assert policy["attempt_limit"] == 10
    assert policy["totp_clock_skew_steps"] == 2      # a wide window enables code sharing
    assert policy["lockout_seconds"] == 60
    assert policy["challenge_ttl_seconds"] == 600
    assert policy["recovery_code_count"] == 5


def test_strict_policy_normalization_rejects_out_of_range_values():
    assert advanced_features._normalize_mfa_policy({"attempt_limit": 9999}, "t", strict=True) is None


def test_email_otp_cannot_satisfy_privileged_mfa():
    policy = advanced_features._normalize_mfa_policy(
        {"email_otp_for_login": True, "required_for_roles": ["admin"]},
        "t",
    )
    assert policy is not None
    assert policy["email_otp_for_login"] is False


def test_mfa_policy_requires_factors_for_admin_but_not_for_read_only(monkeypatch):
    monkeypatch.setenv("PESAGUARD_MFA_ADMIN_ENFORCED", "1")
    admin = UserAccount(id="u_admin", username="u-admin", tenant_id="otp-policy",
                        roles=["admin"], permissions=[], status="active")
    viewer = UserAccount(id="u_viewer", username="u-viewer", tenant_id="otp-policy",
                         roles=["read-only"], permissions=[], status="active")
    assert advanced_features._mfa_is_required(admin) is True
    assert advanced_features._mfa_is_required(viewer) is False

    monkeypatch.setenv("PESAGUARD_MFA_ADMIN_ENFORCED", "0")
    assert advanced_features._mfa_is_required(admin) is False


# ---------------------------------------------------------------------------
# Unit (store): verification semantics
# ---------------------------------------------------------------------------


def test_totp_verification_is_replay_protected_and_audited():
    tenant_id, user_id = "otp-unit-tenant", "otp_unit_user"
    secret = "JBSWY3DPEHPK3PXP"
    _purge_tenant(tenant_id, user_id)
    try:
        user = _make_user(user_id, tenant_id, ["admin"])
        _add_totp_factor(tenant_id, user_id, secret)
        code = advanced_features._totp_code(secret)

        assert advanced_features._verify_mfa_code(user, code) is True
        # The same code inside its validity window must be rejected (replay).
        assert advanced_features._verify_mfa_code(user, code) is False

        assert len(_events(tenant_id, "factor.totp.verified")) == 1
        assert len(_events(tenant_id, "factor.totp.replay_rejected")) == 1
        with SessionLocal() as session:
            factor = session.query(MFAFactor).filter_by(id=f"mfa_factor_test_{user_id}").one()
            assert factor.last_used_counter is not None
            assert factor.last_used_at is not None
            # A replay counts as a failed attempt and never advances the counter.
            assert factor.failed_attempts == 1
    finally:
        _purge_tenant(tenant_id, user_id)


def test_totp_factor_locks_after_attempt_limit_and_stays_locked():
    tenant_id, user_id = "otp-lock-tenant", "otp_lock_user"
    secret = "JBSWY3DPEHPK3PXP"
    _purge_tenant(tenant_id, user_id)
    try:
        user = _make_user(user_id, tenant_id, ["admin"])
        factor_id = _add_totp_factor(tenant_id, user_id, secret)
        valid = advanced_features._totp_code(secret)
        wrong = "000000" if valid != "000000" else "999999"

        for _ in range(5):  # default attempt_limit
            assert advanced_features._verify_mfa_code(user, wrong) is False

        with SessionLocal() as session:
            factor = session.query(MFAFactor).filter_by(id=factor_id).one()
            assert factor.status == "locked"
            assert factor.locked_until is not None
            locked_until = factor.locked_until
            if locked_until.tzinfo is None:  # SQLite drops tzinfo on read-back
                locked_until = locked_until.replace(tzinfo=timezone.utc)
            assert locked_until > datetime.now(timezone.utc)
            assert factor.failed_attempts == 5

        # A locked factor must not accept even the correct code.
        assert advanced_features._verify_mfa_code(user, advanced_features._totp_code(secret)) is False
        assert len(_events(tenant_id, "factor.totp.locked")) == 1
    finally:
        _purge_tenant(tenant_id, user_id)


def test_recovery_code_is_single_use_and_consumed_once():
    tenant_id, user_id = "otp-recovery-tenant", "otp_recovery_user"
    code = "FEEDFACEFEEDFACE"
    _purge_tenant(tenant_id, user_id)
    try:
        user = _make_user(user_id, tenant_id, ["admin"])
        with SessionLocal() as session:
            session.add(MFARecovery(
                id="mfa_recovery_test_1",
                tenant_id=tenant_id,
                user_id=user_id,
                code_hash=advanced_features._hash_recovery_code(code),
            ))
            session.commit()

        assert advanced_features._verify_mfa_code(user, code) is True
        # Second presentation of the same recovery code must fail (single-use).
        assert advanced_features._verify_mfa_code(user, code) is False

        with SessionLocal() as session:
            recovery = session.query(MFARecovery).filter_by(id="mfa_recovery_test_1").one()
            assert recovery.used_at is not None
            assert recovery.code_hash != code
        assert len(_events(tenant_id, "recovery_code.consumed")) == 1
        assert len(_events(tenant_id, "verification.failed")) == 1
    finally:
        _purge_tenant(tenant_id, user_id)


def test_verification_never_crosses_tenant_boundaries():
    """A valid code for tenant B's factor must not authenticate tenant A's user."""
    tenant_a, user_a = "otp-iso-a", "otp_iso_a_user"
    tenant_b, user_b = "otp-iso-b", "otp_iso_b_user"
    secret = "JBSWY3DPEHPK3PXP"
    _purge_tenant(tenant_a, user_a)
    _purge_tenant(tenant_b, user_b)
    try:
        user_record_a = _make_user(user_a, tenant_a, ["admin"])
        _make_user(user_b, tenant_b, ["admin"])
        factor_b_id = _add_totp_factor(tenant_b, user_b, secret)
        code = advanced_features._totp_code(secret)

        assert advanced_features._verify_mfa_code(user_record_a, code) is False

        # Tenant B's factor must be untouched by tenant A's failed attempt.
        with SessionLocal() as session:
            factor_b = session.query(MFAFactor).filter_by(id=factor_b_id).one()
            assert factor_b.failed_attempts == 0
            assert factor_b.last_used_counter is None
        # Tenant A only records its own denial; no factor events ever cross tenants.
        assert _events(tenant_a, "verification.failed")
        assert _events(tenant_a, "factor.totp.verified") == []
        assert _events(tenant_b) == []
    finally:
        _purge_tenant(tenant_a, user_a)
        _purge_tenant(tenant_b, user_b)


# ---------------------------------------------------------------------------
# Integration (HTTP): challenge issuance and verification
# ---------------------------------------------------------------------------


@pytest.fixture
def mfa_admin_token():
    tenant_id, user_id = "otp-http-tenant", "otp_http_admin"
    _purge_tenant(tenant_id, user_id)
    _make_user(user_id, tenant_id, ["admin"])
    token = AuthRBAC.generate_token(user_id, "otp-http-admin", tenant_id, ["admin"])
    try:
        yield token, tenant_id, user_id
    finally:
        _purge_tenant(tenant_id, user_id)


def _create_challenge(client, token, user_id, *, code, monkeypatch):
    """Issue a challenge with a deterministic server-side code."""
    monkeypatch.setattr(advanced_features.secrets, "randbelow", lambda _bound: code)
    return client.post(
        "/auth/mfa/challenge",
        headers={"Authorization": f"Bearer {token}"},
        json={"user_id": user_id},
    )


def test_challenge_response_never_leaks_the_code(client, mfa_admin_token, monkeypatch):
    token, tenant_id, user_id = mfa_admin_token
    response = _create_challenge(client, token, user_id, code=123_456, monkeypatch=monkeypatch)
    assert response.status_code == 201, response.get_data(as_text=True)

    body = response.get_json()
    challenge_id = body["data"]["challenge_id"]
    assert "code" not in body
    assert "code" not in body["data"]
    assert set(body["data"]) >= {"challenge_id", "status", "user_id"}
    assert body["data"]["status"] == "pending"

    with SessionLocal() as session:
        record = session.query(MFAChallenge).filter_by(id=challenge_id, tenant_id=tenant_id).one()
        # Only the SHA-256 digest of the code is persisted, never the code itself.
        assert record.code_hash == hashlib.sha256(b"123456").hexdigest()
        assert record.code_hash != "123456"
        assert record.status == "pending"
        expires_at = record.expires_at
        if expires_at.tzinfo is None:  # SQLite drops tzinfo on read-back
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        assert expires_at > datetime.now(timezone.utc)


def test_challenge_verification_is_single_use(client, mfa_admin_token, monkeypatch):
    token, _tenant_id, user_id = mfa_admin_token
    created = _create_challenge(client, token, user_id, code=654_321, monkeypatch=monkeypatch)
    challenge_id = created.get_json()["data"]["challenge_id"]
    headers = {"Authorization": f"Bearer {token}"}

    first = client.post("/auth/mfa/verify", headers=headers,
                        json={"user_id": user_id, "challenge_id": challenge_id, "code": "654321"})
    assert first.status_code == 200, first.get_data(as_text=True)
    assert first.get_json()["data"]["verified"] is True
    assert first.get_json()["data"]["status"] == "verified"

    # Replaying the verified challenge must not verify again.
    replay = client.post("/auth/mfa/verify", headers=headers,
                         json={"user_id": user_id, "challenge_id": challenge_id, "code": "654321"})
    assert replay.status_code == 200
    assert replay.get_json()["data"]["verified"] is False
    assert replay.get_json()["data"]["status"] == "verified"


def test_challenge_fails_closed_after_max_attempts(client, mfa_admin_token, monkeypatch):
    token, _tenant_id, user_id = mfa_admin_token
    created = _create_challenge(client, token, user_id, code=111_222, monkeypatch=monkeypatch)
    challenge_id = created.get_json()["data"]["challenge_id"]
    headers = {"Authorization": f"Bearer {token}"}

    for attempt in range(1, 6):
        response = client.post(
            "/auth/mfa/verify",
            headers=headers,
            json={"user_id": user_id, "challenge_id": challenge_id, "code": f"{attempt:06d}"},
        )
        assert response.status_code == 200
        assert response.get_json()["data"]["verified"] is False
        if attempt < 5:
            assert response.get_json()["data"]["status"] == "pending"
    assert response.get_json()["data"]["status"] == "failed"

    # Once failed, even the correct code is refused.
    exhausted = client.post(
        "/auth/mfa/verify",
        headers=headers,
        json={"user_id": user_id, "challenge_id": challenge_id, "code": "111222"},
    )
    assert exhausted.get_json()["data"]["verified"] is False
    assert exhausted.get_json()["data"]["status"] == "failed"


def test_challenge_is_tenant_scoped(client, mfa_admin_token, monkeypatch):
    token, tenant_id, user_id = mfa_admin_token
    created = _create_challenge(client, token, user_id, code=246_810, monkeypatch=monkeypatch)
    challenge_id = created.get_json()["data"]["challenge_id"]

    other_tenant, other_user = "otp-http-other", "otp_http_other"
    _purge_tenant(other_tenant, other_user)
    try:
        _make_user(other_user, other_tenant, ["admin"])
        other_token = AuthRBAC.generate_token(other_user, "otp-http-other", other_tenant, ["admin"])
        response = client.post(
            "/auth/mfa/verify",
            headers={"Authorization": f"Bearer {other_token}"},
            json={"user_id": user_id, "challenge_id": challenge_id, "code": "246810"},
        )
        # A tenant can neither see nor verify another tenant's challenge.
        assert response.status_code == 404
        assert response.get_json()["error"]["code"] == "resource_not_found"

        with SessionLocal() as session:
            record = session.query(MFAChallenge).filter_by(id=challenge_id).one()
            assert record.status == "pending"
            assert record.attempts == 0
            assert record.tenant_id == tenant_id
    finally:
        _purge_tenant(other_tenant, other_user)


def test_challenge_endpoints_enforce_authentication_and_mfa_permission(client, mfa_admin_token):
    token, _tenant_id, user_id = mfa_admin_token

    unauthenticated = client.post("/auth/mfa/challenge", json={"user_id": user_id})
    assert unauthenticated.status_code == 401

    reader_tenant, reader_user = "otp-http-reader", "otp_http_reader"
    _purge_tenant(reader_tenant, reader_user)
    try:
        _make_user(reader_user, reader_tenant, ["read-only"])
        reader_token = AuthRBAC.generate_token(reader_user, "otp-http-reader", reader_tenant, ["read-only"])
        denied = client.post(
            "/auth/mfa/challenge",
            headers={"Authorization": f"Bearer {reader_token}"},
            json={"user_id": reader_user},
        )
        assert denied.status_code == 403
    finally:
        _purge_tenant(reader_tenant, reader_user)

    # Malformed verification requests are rejected before any state change.
    headers = {"Authorization": f"Bearer {token}"}
    assert client.post("/auth/mfa/verify", headers=headers, json={}).status_code == 400


def test_failed_challenge_verification_records_security_telemetry(
    client, mfa_admin_token, monkeypatch
):
    token, _tenant_id, user_id = mfa_admin_token
    created = _create_challenge(client, token, user_id, code=999_001, monkeypatch=monkeypatch)
    challenge_id = created.get_json()["data"]["challenge_id"]
    headers = {"Authorization": f"Bearer {token}"}

    before = telemetry_snapshot()["business"].get("security_events", 0)
    response = client.post(
        "/auth/mfa/verify",
        headers=headers,
        json={"user_id": user_id, "challenge_id": challenge_id, "code": "000000"},
    )
    assert response.status_code == 200
    assert response.get_json()["data"]["verified"] is False
    after = telemetry_snapshot()["business"].get("security_events", 0)
    assert after > before, "a failed MFA verification must be observable in security telemetry"


def test_expired_challenge_is_refused_not_errored(client, mfa_admin_token, monkeypatch):
    """Regression: an already-expired challenge must return verified=False (200),
    not raise a 500 from comparing a naive SQLite timestamp to an aware now.

    Guards the fix in ``/auth/mfa/verify`` that normalizes ``expires_at`` tzinfo
    before comparison, so expiry is enforced identically on SQLite and Postgres.
    """
    token, tenant_id, user_id = mfa_admin_token
    created = _create_challenge(client, token, user_id, code=111_222, monkeypatch=monkeypatch)
    challenge_id = created.get_json()["data"]["challenge_id"]

    # Force the stored challenge into the past (drop tzinfo like SQLite does).
    with SessionLocal() as session:
        record = session.query(MFAChallenge).filter_by(id=challenge_id, tenant_id=tenant_id).one()
        record.expires_at = (datetime.now(timezone.utc) - timedelta(minutes=5)).replace(tzinfo=None)
        session.commit()

    headers = {"Authorization": f"Bearer {token}"}
    response = client.post(
        "/auth/mfa/verify",
        headers=headers,
        json={"user_id": user_id, "challenge_id": challenge_id, "code": "111222"},
    )
    # A correct code on an expired challenge must still be refused (never verified),
    # and must be a clean 200 rather than a 500 from the tz-mismatch TypeError.
    assert response.status_code == 200, response.get_data(as_text=True)
    body = response.get_json()["data"]
    assert body["verified"] is False
    # Status stays non-verified; an expired challenge is not auto-marked "verified".
    assert body["status"] in {"pending", "expired"}

