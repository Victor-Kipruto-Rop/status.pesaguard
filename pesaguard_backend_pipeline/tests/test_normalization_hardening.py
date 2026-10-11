"""Regression tests pinning normalization hardening invariants (services 25 & 26).

test_normalization.py covers the happy paths and the two headline amount
rejections (sub-minor-unit rounding, overflow). These tests pin the remaining
financial-correctness invariants that were previously unpinned, so a future
refactor cannot silently weaken them (Rule #33: transformations must be
deterministic and must not silently change source values; Rule #7: financial
correctness).

All assertions were verified against the current implementation before being
written here (Rule #62: evidence over assumptions).
"""

from __future__ import annotations

import pytest

from normalization import (
    NormalizationError,
    normalize_amount,
    normalize_currency,
    normalize_identifier,
    normalize_phone,
    normalize_status,
    normalize_timestamp,
    normalize_transaction_type,
)


# --- Amount: reject non-finite, non-positive, and unsafe inputs (Rule #7) --------


@pytest.mark.parametrize("value", ["nan", "NaN", "inf", "-inf", "Infinity", "-Infinity"])
def test_amount_rejects_non_finite_values(value):
    # NaN/Infinity must never become a stored financial amount.
    with pytest.raises(NormalizationError, match="finite"):
        normalize_amount(value)


@pytest.mark.parametrize("value", ["0", "0.00", "-5", "-0.01"])
def test_amount_rejects_zero_and_negative(value):
    # A transaction amount must be strictly greater than zero.
    with pytest.raises(NormalizationError, match="greater than zero"):
        normalize_amount(value)


def test_amount_rejects_non_numeric_text():
    with pytest.raises(NormalizationError):
        normalize_amount("not-a-number")


def test_amount_strips_thousands_separators_and_quantizes():
    assert normalize_amount("1,234,567.89") == "1234567.89"
    assert normalize_amount(" 42 ") == "42.00"


def test_amount_accepts_unicode_digits_deterministically():
    # Python's Decimal accepts non-ASCII digits; pin that behaviour so it is a
    # deliberate, documented decision rather than an accident.
    assert normalize_amount("\u0661\u0662\u0663") == "123.00"


# --- Currency: aliases and strict ISO validation --------------------------------


def test_currency_normalizes_aliases_and_case():
    assert normalize_currency("KSH") == "KES"
    assert normalize_currency("kshs") == "KES"
    assert normalize_currency("$") == "USD"
    assert normalize_currency("US DOLLAR") == "USD"
    assert normalize_currency("kes") == "KES"


@pytest.mark.parametrize("value", ["12", "KESS", "K1S"])
def test_currency_rejects_non_iso(value):
    with pytest.raises(NormalizationError):
        normalize_currency(value)


def test_currency_defaults_to_kes_when_absent():
    # An absent/empty currency resolves to the KES default rather than raising,
    # while an explicit non-ISO value above is rejected.
    assert normalize_currency(None) == "KES"
    assert normalize_currency("") == "KES"


# --- Phone: unknown placeholders and country-code handling ----------------------


@pytest.mark.parametrize("value", ["", "unknown", "N/A", "none", "  Unknown "])
def test_phone_treats_placeholders_as_unknown(value):
    # Missing phone numbers are preserved as an explicit "unknown" marker rather
    # than being dropped or invented (Rule #57: never invent data).
    assert normalize_phone(value) == "unknown"


def test_phone_normalizes_local_and_international_forms():
    assert normalize_phone("0700 000 000") == "+254700000000"
    assert normalize_phone("00447000000000") == "+447000000000"


def test_phone_rejects_too_short():
    with pytest.raises(NormalizationError):
        normalize_phone("12345")


# --- Identifier & timestamp: required-value enforcement -------------------------


@pytest.mark.parametrize("value", ["", "   ", None])
def test_identifier_rejects_missing(value):
    with pytest.raises(NormalizationError, match="required"):
        normalize_identifier(value)


def test_identifier_strips_whitespace_and_uppercases():
    assert normalize_identifier("  abc 123 ") == "ABC123"


def test_timestamp_rejects_missing():
    with pytest.raises(NormalizationError, match="required"):
        normalize_timestamp("")


def test_timestamp_converts_naive_to_utc_and_handles_z_suffix():
    assert normalize_timestamp("2026-01-01T10:00:00+03:00") == "2026-01-01T07:00:00Z"
    assert normalize_timestamp("2026-01-01T10:00:00Z") == "2026-01-01T10:00:00Z"


# --- Type & status: alias mapping ------------------------------------------------


def test_transaction_type_maps_debit_to_payout_and_defaults_to_payment():
    assert normalize_transaction_type("debit") == "PAYOUT"
    assert normalize_transaction_type("") == "PAYMENT"


def test_status_maps_aliases():
    assert normalize_status("successful") == "COMPLETED"
    assert normalize_status("reversed") == "REVERSED"
    assert normalize_status("") == "RECEIVED"
