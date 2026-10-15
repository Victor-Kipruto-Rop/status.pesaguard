"""Regression guard for the Daraja webhook source gate (services 40/41).

Pins the security contract of ``security_helpers.is_allowed_source``:

- It FAILS CLOSED (denies) when neither ``DARAJA_SHARED_SECRET`` nor
  ``DARAJA_ALLOWED_IPS`` is configured (Rules #55/#57).
- There is intentionally NO environment override that relaxes the gate. A
  documented-but-unimplemented "unrestricted webhook source" switch would be a
  fail-open backdoor; these tests ensure that setting that override env var (or
  any forged header) never relaxes the fail-closed contract (Rule #65).
- The shared-secret and IP-allowlist positive/negative paths are pinned so a
  future refactor cannot silently weaken them.

The function only reads ``request.headers`` (parsed from the WSGI environ), so a
bare ``flask.Request.from_values`` is sufficient and needs no app context.
"""

from __future__ import annotations

import pytest
from flask import Request

import security_helpers


def _request(secret_header: str | None = None) -> Request:
    """Build a minimal Flask request carrying the shared-secret header if given."""
    headers = {"X-Daraja-Shared-Secret": secret_header} if secret_header is not None else {}
    return Request.from_values(headers=headers)


@pytest.fixture(autouse=True)
def _clean_webhook_env(monkeypatch):
    """Keep every test hermetic against ambient/CI webhook configuration."""
    for var in (
        "DARAJA_SHARED_SECRET",
        "DARAJA_ALLOWED_IPS",
        "PESAGUARD_ALLOW_UNRESTRICTED_WEBHOOK_SOURCE",
    ):
        monkeypatch.delenv(var, raising=False)


def test_unconfigured_source_gate_fails_closed():
    # Nothing configured -> deny, even for a forged header value.
    assert security_helpers.is_allowed_source("203.0.113.10", _request()) is False
    assert security_helpers.is_allowed_source("203.0.113.10", _request("guess")) is False


def test_documented_unrestricted_override_is_not_implemented(monkeypatch):
    """The override once named in docs must NOT relax the gate (no fail-open)."""
    monkeypatch.setenv("PESAGUARD_ALLOW_UNRESTRICTED_WEBHOOK_SOURCE", "1")
    # No real secret/IP configured -> still denied despite the override.
    assert security_helpers.is_allowed_source("203.0.113.10", _request()) is False
    # A forged header must not be accepted while the gate is unconfigured.
    assert security_helpers.is_allowed_source("203.0.113.10", _request("anything")) is False


def test_shared_secret_match_and_mismatch(monkeypatch):
    monkeypatch.setenv("DARAJA_SHARED_SECRET", "top-secret-value")
    assert security_helpers.is_allowed_source("203.0.113.10", _request("top-secret-value")) is True
    assert security_helpers.is_allowed_source("203.0.113.10", _request("wrong")) is False
    # Missing header must not match a configured secret.
    assert security_helpers.is_allowed_source("203.0.113.10", _request()) is False


def test_ip_allowlist_exact_and_cidr(monkeypatch):
    monkeypatch.setenv("DARAJA_ALLOWED_IPS", "203.0.113.10,198.51.100.0/24")
    # Exact-IP match.
    assert security_helpers.is_allowed_source("203.0.113.10", _request()) is True
    # CIDR match.
    assert security_helpers.is_allowed_source("198.51.100.55", _request()) is True
    # Outside the allowlist.
    assert security_helpers.is_allowed_source("198.51.101.55", _request()) is False
    # A non-IP string fails closed.
    assert security_helpers.is_allowed_source("not-an-ip", _request()) is False
