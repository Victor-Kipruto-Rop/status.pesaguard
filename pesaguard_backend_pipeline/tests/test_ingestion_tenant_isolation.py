"""Tenant-isolation regression tests for the ingestion / canonical-model boundary
(services 21 & 27).

The canonical transaction id is derived from (tenant_id, provider,
provider_transaction_id). Two invariants keep this safe under Rule #11:

  1. Cross-tenant isolation: the same provider TransID under two different
     tenants must derive DIFFERENT canonical ids, so one tenant can never collide
     with or read another tenant's transaction.
  2. Determinism: the same (tenant, provider, id) must always derive the SAME
     canonical id, so idempotent replays are recognised rather than duplicated.

These were verified against the current implementation before being pinned here
(Rule #62). All assertions use an in-memory SQLite DATABASE_URL because importing
the ingestion package instantiates a module-level EventStore.
"""

from __future__ import annotations

import os

os.environ.setdefault("DATABASE_URL", "sqlite:///:memory:")

from ingestion import _canonical_transaction_id  # noqa: E402


def test_canonical_id_is_tenant_scoped():
    # Same provider transaction id, different tenants -> different canonical ids.
    tenant_a = _canonical_transaction_id("tenant-a", "mpesa", "SHARED-TX-1")
    tenant_b = _canonical_transaction_id("tenant-b", "mpesa", "SHARED-TX-1")
    assert tenant_a != tenant_b


def test_canonical_id_is_deterministic_for_same_inputs():
    first = _canonical_transaction_id("tenant-a", "mpesa", "TX-DETERMINISTIC")
    second = _canonical_transaction_id("tenant-a", "mpesa", "TX-DETERMINISTIC")
    assert first == second


def test_canonical_id_distinguishes_providers_within_a_tenant():
    mpesa = _canonical_transaction_id("tenant-a", "mpesa", "SAME-ID")
    bank = _canonical_transaction_id("tenant-a", "bank", "SAME-ID")
    assert mpesa != bank


def test_casing_is_normalized_by_the_caller_before_id_derivation():
    # _canonical_transaction_id does NOT normalize casing itself; the ingestion
    # adapters normalize the provider reference to upper case via
    # normalize_identifier BEFORE deriving the canonical id. This pins that the
    # real path is case-insensitive so a retry with different casing is recognised
    # as the same financial event rather than duplicated (Rule #9).
    from normalization import normalize_identifier

    lower = _canonical_transaction_id("tenant-a", "mpesa", normalize_identifier("tx-lower"))
    upper = _canonical_transaction_id("tenant-a", "mpesa", normalize_identifier("TX-LOWER"))
    assert lower == upper


def test_raw_id_derivation_is_case_sensitive_documenting_caller_contract():
    # Documents the internal contract explicitly: the raw helper is case-sensitive,
    # which is why callers MUST normalize first. If a future change makes the helper
    # itself normalize, this test will surface it for a deliberate decision.
    assert _canonical_transaction_id("tenant-a", "mpesa", "abc") != _canonical_transaction_id(
        "tenant-a", "mpesa", "ABC"
    )
