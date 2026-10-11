"""Security regression tests for M-Pesa / Daraja callback signature validation
(service 40 — M-Pesa Callback/Webhook Service; in scope: verifying inbound
callbacks, NOT originating payments).

PesaGuard only *ingests* Daraja callbacks and reconciles them (PRODUCT_SCOPE.md);
it never originates STK Push, B2C, B2B, or transaction-status queries. The security
boundary that matters here is: an inbound callback is accepted ONLY when its HMAC
signature matches, using a constant-time comparison, and every tamper/missing/
malformed case is rejected.

These tests pin that boundary (Rule #19: webhooks are untrusted input; Rule #57:
fail safely). All behaviour was verified against the current implementation first.
"""

from __future__ import annotations

import base64

import pytest

from shared.daraja.validator import compute_hmac, validate_daraja_callback, validate_signature

SECRET = "consumer-secret-value"
BODY = b'{"Body":{"stkCallback":{"ResultCode":0}}}'


def _hex_signature() -> str:
    return compute_hmac(SECRET, BODY).hex()


def _base64_signature() -> str:
    return base64.b64encode(compute_hmac(SECRET, BODY)).decode("ascii")


# --- validate_signature: the core HMAC check ------------------------------------


def test_valid_hex_signature_is_accepted():
    assert validate_signature(SECRET, BODY, _hex_signature()) is True


def test_valid_base64_signature_is_accepted():
    assert validate_signature(SECRET, BODY, _base64_signature()) is True


def test_prefixed_sha256_signature_is_accepted():
    assert validate_signature(SECRET, BODY, "sha256=" + _hex_signature()) is True


def test_tampered_body_is_rejected():
    # A single-byte change to the body must invalidate the signature.
    assert validate_signature(SECRET, BODY + b" ", _hex_signature()) is False


def test_wrong_secret_is_rejected():
    assert validate_signature("attacker-secret", BODY, _hex_signature()) is False


@pytest.mark.parametrize("bad", ["", "nothex!!", "00", _hex_signature()[:-1]])
def test_malformed_or_truncated_signature_is_rejected(bad):
    assert validate_signature(SECRET, BODY, bad) is False


# --- validate_daraja_callback: the production webhook entry point ---------------


def test_callback_accepts_matching_signature():
    assert validate_daraja_callback(BODY, _hex_signature(), SECRET) is True


def test_callback_rejects_tampered_body():
    assert validate_daraja_callback(BODY + b"x", _hex_signature(), SECRET) is False


def test_callback_rejects_missing_signature_safely():
    # A missing/None signature must be rejected (return False), never accepted and
    # never raise -- the production path coerces it to "" (Rule #57: fail safely).
    assert validate_daraja_callback(BODY, None, SECRET) is False
    assert validate_daraja_callback(BODY, "", SECRET) is False


def test_callback_rejects_when_secret_missing():
    assert validate_daraja_callback(BODY, _hex_signature(), None) is False
    assert validate_daraja_callback(BODY, _hex_signature(), "") is False


def test_callback_reads_signature_from_headers():
    headers = {"X-MPESA-SIGNATURE": _hex_signature()}
    assert validate_daraja_callback(headers, BODY, SECRET) is True


def test_callback_rejects_headers_without_signature():
    assert validate_daraja_callback({"Content-Type": "application/json"}, BODY, SECRET) is False


def test_callback_base64_encoding_path():
    assert (
        validate_daraja_callback(
            {"X-MPESA-SIGNATURE": _base64_signature()},
            BODY,
            SECRET,
            signature_encoding="base64",
        )
        is True
    )


def test_low_level_none_signature_is_a_documented_caller_contract():
    # validate_signature itself requires three positional args; passing None as the
    # signature raises TypeError. The production caller (validate_daraja_callback)
    # never does this -- it coerces None to "" first (see test above). This pins the
    # low-level contract so a future refactor makes a deliberate choice rather than
    # silently changing it.
    with pytest.raises(TypeError):
        validate_signature(SECRET, BODY, None)
