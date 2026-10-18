"""Compatibility policy for API, event, source, and persistence contracts."""

from __future__ import annotations

import re
from dataclasses import dataclass


class UnsupportedContractVersion(ValueError):
    """Raised when a producer or consumer uses an unknown or retired version."""


@dataclass(frozen=True)
class ContractPolicy:
    name: str
    current: str
    supported: tuple[str, ...]
    deprecated: tuple[str, ...] = ()
    retirement: str = ""
    migration: str = ""


CONTRACT_POLICIES = {
    "api": ContractPolicy("api", "1.0", ("1.0",), migration="/api/v1 remains stable; add /api/v2 for breaking changes."),
    "event": ContractPolicy("event", "1.0", ("1.0",), migration="Consumers must accept event schema 1.x before producers migrate to 2.x."),
    "mpesa": ContractPolicy("mpesa", "1.0", ("1.0",), migration="Normalize legacy provider fields at the adapter boundary."),
    "airtel-money": ContractPolicy("airtel-money", "1.0", ("1.0",), migration="Map provider payloads to the canonical transaction contract."),
    "bank": ContractPolicy("bank", "1.0", ("1.0",), migration="Add statement/API adapters without changing canonical fields."),
    "pos": ContractPolicy("pos", "1.0", ("1.0",), migration="Map terminal and merchant fields into canonical metadata."),
    "equity": ContractPolicy("equity", "1.0", ("1.0",), migration="Map Equity Bank statement fields into canonical metadata."),
    "kcb": ContractPolicy("kcb", "1.0", ("1.0",), migration="Map KCB Bank statement fields into canonical metadata."),
    "absa": ContractPolicy("absa", "1.0", ("1.0",), migration="Map Absa Bank statement fields into canonical metadata."),
    "visa": ContractPolicy("visa", "1.0", ("1.0",), migration="Map card network fields into canonical metadata."),
    "mastercard": ContractPolicy("mastercard", "1.0", ("1.0",), migration="Map card network fields into canonical metadata."),
    "database": ContractPolicy("database", "1.0", ("1.0",), migration="Use expand, backfill, contract Alembic revisions."),
}

_VERSION = re.compile(r"^(?P<major>[0-9]+)\.(?P<minor>[0-9]+)$")


def normalize_version(value: str) -> str:
    match = _VERSION.fullmatch(str(value).strip())
    if not match:
        raise UnsupportedContractVersion(f"invalid contract version: {value!r}")
    return f"{int(match.group('major'))}.{int(match.group('minor'))}"


def validate_contract_version(contract: str, version: str) -> str:
    policy = CONTRACT_POLICIES.get(contract)
    if policy is None:
        raise UnsupportedContractVersion(f"unknown contract: {contract}")
    normalized = normalize_version(version)
    if normalized not in policy.supported:
        raise UnsupportedContractVersion(
            f"unsupported {contract} contract version {normalized}; supported versions: {', '.join(policy.supported)}"
        )
    if normalized in policy.deprecated:
        raise UnsupportedContractVersion(f"retired {contract} contract version {normalized}: {policy.retirement}")
    return normalized