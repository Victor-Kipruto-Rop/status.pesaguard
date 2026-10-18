"""Executable HTTP request and response contract validation."""

from __future__ import annotations

from typing import Any, Mapping

from jsonschema import Draft202012Validator

from normalization import NormalizationError, normalize_amount, normalize_currency


class ApiContractError(ValueError):
    """Raised when an HTTP request or response violates its contract."""


TRANSACTION_CREATE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "anyOf": [
        {"required": ["provider_transaction_id", "provider_account_id"]},
        {"required": ["TransID", "BusinessShortCode"]},
    ],
    "required": ["provider", "TransAmount", "Currency", "MSISDN", "TransTime"],
    "properties": {
        "provider_transaction_id": {"type": "string", "minLength": 1, "maxLength": 255},
        "provider_account_id": {"type": "string", "minLength": 1, "maxLength": 255},
        "TransID": {"type": "string", "minLength": 1, "maxLength": 255},
        "BusinessShortCode": {"type": ["string", "number"], "minLength": 1},
        "provider": {"type": "string", "enum": ["mpesa", "safaricom", "airtel-money", "bank", "pos", "visa", "mastercard", "equity", "kcb", "absa"]},
        "TransAmount": {"type": ["string", "number"]},
        "Currency": {"type": "string", "minLength": 1},
        "MSISDN": {"type": "string", "pattern": "^254[17][0-9]{8}$"},
        "TransTime": {"type": "string", "minLength": 1},
    },
    "additionalProperties": True,
}

TRANSACTION_ACCEPTED_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["status", "duplicate", "idempotency_key"],
    "properties": {"status": {"const": "accepted"}, "duplicate": {"type": "boolean"}, "idempotency_key": {"type": "string", "minLength": 1}},
    "additionalProperties": False,
}

TRANSACTION_QUARANTINED_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["status", "idempotency_key"],
    "properties": {
        "status": {"const": "quarantined"},
        "idempotency_key": {"type": "string", "minLength": 1},
    },
    "additionalProperties": False,
}

TRANSACTION_RESPONSE_SCHEMA: dict[str, Any] = {
    "oneOf": [TRANSACTION_ACCEPTED_SCHEMA, TRANSACTION_QUARANTINED_SCHEMA],
}


def _validate(payload: Mapping[str, Any], schema: Mapping[str, Any], boundary: str) -> None:
    errors = sorted(Draft202012Validator(schema).iter_errors(payload), key=lambda error: list(error.path))
    if errors:
        error = errors[0]
        location = ".".join(str(part) for part in error.path) or "$"
        raise ApiContractError(f"{boundary} validation failed at {location}: {error.message}")


def validate_transaction_create(payload: Any) -> None:
    if not isinstance(payload, Mapping):
        raise ApiContractError("request body must be a JSON object")
    _validate(payload, TRANSACTION_CREATE_SCHEMA, "transaction request")
    try:
        currency = normalize_currency(payload["Currency"])
        normalize_amount(payload["TransAmount"], currency=currency)
    except NormalizationError as exc:
        raise ApiContractError(f"transaction amount or Currency is invalid: {exc}") from exc


def validate_transaction_response(payload: Mapping[str, Any]) -> None:
    _validate(payload, TRANSACTION_RESPONSE_SCHEMA, "transaction response")