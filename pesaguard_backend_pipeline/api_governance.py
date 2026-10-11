"""Shared API version, contract, OpenAPI, and error governance."""

from __future__ import annotations

from datetime import datetime, timezone
from email.utils import format_datetime
from http import HTTPStatus
import json
from pathlib import Path
import re
from typing import Any, Callable, Mapping
from urllib.parse import urlparse

from contract_versioning import CONTRACT_POLICIES, ContractPolicy, UnsupportedContractVersion, normalize_version
from schema_compatibility import IncompatibleSchemaError, SchemaChange, compare_openapi

_VERSIONED_PATH = re.compile(r"^/(?:api/)?v(?P<major>[0-9]+)(?:/|$)")
_METHODS = frozenset({"get", "post", "put", "patch", "delete", "options", "head", "trace"})


class OpenAPIContractError(ValueError):
    """Raised when an OpenAPI document is malformed or has invalid local refs."""


class ApiVersioningService:
    """Resolve URL-based API versions against the configured API contract policy."""

    def __init__(self, policy: ContractPolicy | None = None) -> None:
        self.policy = policy or CONTRACT_POLICIES["api"]

    def version_for_path(self, path: str) -> str | None:
        match = _VERSIONED_PATH.match(path)
        if match is None:
            return None
        return f"{int(match.group('major'))}.0"

    def validate_path(self, path: str) -> str | None:
        version = self.version_for_path(path)
        if version is None:
            return None
        if version not in self.policy.supported:
            raise UnsupportedContractVersion(
                f"unsupported api contract version {version}; "
                f"supported versions: {', '.join(self.policy.supported)}"
            )
        return version

    def apply_response_headers(self, headers: Any, path: str) -> None:
        try:
            version = self.validate_path(path)
        except UnsupportedContractVersion:
            return
        if version is not None:
            headers["X-API-Version"] = version


class ApiContractService:
    """Expose and validate the API contract version metadata."""

    def __init__(self, policy: ContractPolicy | None = None) -> None:
        self.policy = policy or CONTRACT_POLICIES["api"]

    def metadata(self) -> dict[str, Any]:
        return {
            "version": self.policy.current,
            "supported_versions": list(self.policy.supported),
            "deprecated_versions": list(self.policy.deprecated),
            "migration": self.policy.migration,
        }

    def validate_openapi_version(self, document: Mapping[str, Any]) -> None:
        info = document.get("info")
        version = info.get("version") if isinstance(info, Mapping) else None
        if not isinstance(version, str):
            raise OpenAPIContractError("OpenAPI contract version must be a string")
        version_parts = version.split(".")
        if len(version_parts) not in {2, 3} or any(not part.isdecimal() for part in version_parts):
            raise OpenAPIContractError(f"Invalid OpenAPI contract version: {version}")
        try:
            contract_version = normalize_version(".".join(version_parts[:2]))
        except UnsupportedContractVersion as exc:
            raise OpenAPIContractError(f"Invalid OpenAPI contract version: {version}") from exc
        if contract_version != self.policy.current:
            raise OpenAPIContractError(
                f"OpenAPI version {version} does not match API policy {self.policy.current}"
            )


class ApiDeprecationService:
    """Produce lifecycle headers only for explicitly scheduled API versions."""

    def __init__(
        self,
        notices: Mapping[str, Mapping[str, Any]] | None = None,
        *,
        policy: ContractPolicy | None = None,
    ) -> None:
        contract_policy = policy or CONTRACT_POLICIES["api"]
        self._notices = {version: {} for version in contract_policy.deprecated}
        self._notices.update(dict(notices or {}))

    def headers_for(self, version: str | None) -> dict[str, str]:
        if version is None or version not in self._notices:
            return {}
        notice = self._notices[version]
        headers = {"Deprecation": "true"}
        sunset = notice.get("sunset")
        if isinstance(sunset, datetime):
            if sunset.tzinfo is None:
                raise ValueError("API sunset timestamps must include a timezone")
            headers["Sunset"] = format_datetime(sunset.astimezone(timezone.utc), usegmt=True)
        elif sunset is not None:
            raise ValueError("API sunset timestamps must be timezone-aware datetimes")
        successor = notice.get("successor")
        if isinstance(successor, str):
            parsed = urlparse(successor)
            if any(character in successor for character in '<>"\r\n'):
                raise ValueError("API successor links must not contain header or link delimiters")
            if parsed.scheme == "https" and parsed.netloc:
                headers["Link"] = f'<{successor}>; rel="successor-version"'
            elif successor.startswith("/") and not successor.startswith("//"):
                headers["Link"] = f'<{successor}>; rel="successor-version"'
            else:
                raise ValueError("API successor links must be HTTPS URLs or absolute paths")
        elif successor is not None:
            raise ValueError("API successor links must be strings")
        return headers

    def apply_response_headers(self, headers: Any, version: str | None) -> None:
        for name, value in self.headers_for(version).items():
            headers[name] = value


class ApiCompatibilityService:
    """Reuse the shared OpenAPI comparator and report compatibility explicitly."""

    @staticmethod
    def compare(old: Mapping[str, Any], new: Mapping[str, Any]) -> list[SchemaChange]:
        return compare_openapi(old, new)

    @classmethod
    def breaking_changes(
        cls, old: Mapping[str, Any], new: Mapping[str, Any]
    ) -> list[SchemaChange]:
        return [change for change in cls.compare(old, new) if change.breaking]

    @classmethod
    def assert_compatible(
        cls, old: Mapping[str, Any], new: Mapping[str, Any]
    ) -> list[SchemaChange]:
        changes = cls.compare(old, new)
        breaking = [change for change in changes if change.breaking]
        if breaking:
            raise IncompatibleSchemaError(breaking)
        return changes


class OpenAPISpecService:
    """Load and structurally validate the authoritative OpenAPI document."""

    def load(self, path: Path) -> dict[str, Any]:
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise OpenAPIContractError(f"Unable to load OpenAPI contract: {path}") from exc
        self.validate(document)
        return document

    def validate(self, document: Any) -> None:
        if not isinstance(document, dict):
            raise OpenAPIContractError("OpenAPI document must be a JSON object")
        if not isinstance(document.get("openapi"), str) or not document["openapi"].startswith("3."):
            raise OpenAPIContractError("OpenAPI version must be a supported 3.x version")
        info = document.get("info")
        paths = document.get("paths")
        if not isinstance(info, dict) or not isinstance(info.get("title"), str) or not isinstance(info.get("version"), str):
            raise OpenAPIContractError("OpenAPI info must include a title and version")
        if not isinstance(paths, dict):
            raise OpenAPIContractError("OpenAPI paths must be an object")
        ApiContractService().validate_openapi_version(document)

        operation_ids: set[str] = set()
        for path, path_item in paths.items():
            if not isinstance(path, str) or not path.startswith("/") or not isinstance(path_item, dict):
                raise OpenAPIContractError("OpenAPI paths must map absolute paths to path objects")
            for method, operation in path_item.items():
                if not isinstance(method, str) or method.lower() not in _METHODS:
                    continue
                if not isinstance(operation, dict) or not isinstance(operation.get("responses"), dict) or not operation["responses"]:
                    raise OpenAPIContractError(f"OpenAPI operation {method.upper()} {path} must define responses")
                operation_id = operation.get("operationId")
                if operation_id is not None:
                    if not isinstance(operation_id, str) or operation_id in operation_ids:
                        raise OpenAPIContractError(f"OpenAPI operationId must be unique: {operation_id!r}")
                    operation_ids.add(operation_id)

        self._validate_local_refs(document)

    def _validate_local_refs(self, document: Mapping[str, Any]) -> None:
        def walk(value: Any) -> None:
            if isinstance(value, dict):
                ref = value.get("$ref")
                if isinstance(ref, str) and ref.startswith("#/"):
                    target: Any = document
                    try:
                        for part in ref[2:].split("/"):
                            key = part.replace("~1", "/").replace("~0", "~")
                            target = target[int(key)] if isinstance(target, list) else target[key]
                    except (KeyError, IndexError, ValueError, TypeError) as exc:
                        raise OpenAPIContractError(f"Unresolved OpenAPI reference: {ref}") from exc
                    if target is None:
                        raise OpenAPIContractError(f"Unresolved OpenAPI reference: {ref}")
                for child in value.values():
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)

        walk(document)


class ApiErrorService:
    """Add RFC 9457 fields while retaining established PesaGuard error keys."""

    @staticmethod
    def standardize(
        payload: Mapping[str, Any],
        *,
        status: int,
        instance: str,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        result = dict(payload)
        legacy_error = result.get("error")
        nested_error = legacy_error if isinstance(legacy_error, Mapping) else {}
        code = (
            legacy_error
            if isinstance(legacy_error, str)
            else nested_error.get("code")
            or result.get("error_code")
            or f"http_{status}"
        )
        detail = result.get("message") or nested_error.get("message") or "The request could not be completed."
        try:
            title = HTTPStatus(status).phrase
        except ValueError:
            title = "Error"

        result.setdefault("type", "about:blank")
        result.setdefault("title", title)
        result.setdefault("status", status)
        result.setdefault("detail", detail)
        result.setdefault("instance", instance)
        result.setdefault("code", str(code))
        resolved_request_id = request_id or result.get("request_id")
        if resolved_request_id:
            result.setdefault("request_id", resolved_request_id)
        return result


def standardize_error_response(
    response: Any,
    *,
    instance: str,
    request_id: str | None = None,
    json_dumps: Callable[[Any], str] = json.dumps,
) -> Any:
    """Normalize JSON error bodies without altering success or non-JSON responses."""
    if response.status_code < 400 or not response.is_json:
        return response
    payload = response.get_json(silent=True)
    if not isinstance(payload, dict) or not ("error" in payload or "error_code" in payload):
        return response
    standardized = ApiErrorService.standardize(
        payload,
        status=response.status_code,
        instance=instance,
        request_id=request_id,
    )
    response.set_data(json_dumps(standardized))
    response.mimetype = "application/problem+json"
    return response


def validate_api_path_version(path: str) -> str | None:
    """Return a supported URL version, raising for an unsupported one."""
    return ApiVersioningService().validate_path(path)
