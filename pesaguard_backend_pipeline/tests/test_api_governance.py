from datetime import datetime, timezone
import json
from pathlib import Path

import pytest

from api.dashboard_app import app as dashboard_app
from app_4_advanced_features import app as auth_app
from api_governance import (
    ApiCompatibilityService,
    ApiContractService,
    ApiDeprecationService,
    ApiErrorService,
    ApiVersioningService,
    OpenAPIContractError,
    OpenAPISpecService,
    standardize_error_response,
)
from contract_versioning import ContractPolicy, UnsupportedContractVersion


_OPENAPI_PATH = Path(__file__).parents[1] / "docs" / "api" / "openapi.json"


def test_url_version_selection_uses_policy_and_preserves_legacy_routes():
    service = ApiVersioningService()

    assert service.validate_path("/api/v1/transactions") == "1.0"
    assert service.validate_path("/v1/settings") == "1.0"
    assert service.validate_path("/providers") is None
    with pytest.raises(UnsupportedContractVersion, match="unsupported api contract version 2.0"):
        service.validate_path("/api/v2/transactions")


def test_contract_service_exposes_policy_and_validates_openapi_semver():
    service = ApiContractService()

    assert service.metadata()["supported_versions"] == ["1.0"]
    service.validate_openapi_version({"info": {"version": "1.0.0"}})
    with pytest.raises(OpenAPIContractError, match="does not match API policy"):
        service.validate_openapi_version({"info": {"version": "2.0.0"}})


def test_deprecation_headers_require_an_explicit_notice_and_real_sunset():
    service = ApiDeprecationService(
        {
            "1.0": {
                "sunset": datetime(2030, 1, 1, tzinfo=timezone.utc),
                "successor": "/api/v2",
            }
        }
    )

    assert ApiDeprecationService().headers_for("1.0") == {}
    assert service.headers_for("1.0") == {
        "Deprecation": "true",
        "Sunset": "Tue, 01 Jan 2030 00:00:00 GMT",
        "Link": '</api/v2>; rel="successor-version"',
    }
    with pytest.raises(ValueError, match="timezone"):
        ApiDeprecationService({"1.0": {"sunset": datetime(2030, 1, 1)}}).headers_for("1.0")
    policy = ContractPolicy("api", "1.0", ("1.0",), deprecated=("1.0",))
    assert ApiVersioningService(policy).validate_path("/api/v1/transactions") == "1.0"
    assert ApiDeprecationService(policy=policy).headers_for("1.0") == {"Deprecation": "true"}


def test_compatibility_service_reuses_openapi_breaking_change_comparator():
    previous = {"paths": {"/payments": {"get": {"responses": {"200": {}}}}}}
    candidate = {"paths": {}}

    changes = ApiCompatibilityService.compare(previous, candidate)

    assert [change.kind for change in changes] == ["endpoint_removed"]
    assert ApiCompatibilityService.breaking_changes(previous, candidate) == changes
    with pytest.raises(ValueError, match="removed endpoint"):
        ApiCompatibilityService.assert_compatible(previous, candidate)
    assert ApiCompatibilityService.assert_compatible(previous, previous) == []


def test_openapi_service_loads_and_rejects_invalid_or_unresolved_contracts(tmp_path):
    service = OpenAPISpecService()
    document = service.load(_OPENAPI_PATH)

    assert document["info"]["version"] == "1.0.0"
    assert document["x-api-contract-version"]["supported_versions"] == ["1.0"]

    invalid_path = tmp_path / "invalid-openapi.json"
    invalid_path.write_text(
        json.dumps(
            {
                "openapi": "3.0.3",
                "info": {"title": "Invalid", "version": "1.0.0"},
                "paths": {"/x": {"get": {"responses": {"200": {"$ref": "#/missing"}}}}},
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(OpenAPIContractError, match="Unresolved OpenAPI reference"):
        service.load(invalid_path)

    invalid_key_path = tmp_path / "invalid-openapi-key.json"
    invalid_key_path.write_text(
        json.dumps(
            {
                "openapi": "3.0.3",
                "info": {"title": "Invalid", "version": "1.0.0"},
                "paths": {"/x": {"get": {"responses": {"200": {}}}}},
                "components": {"schemas": {"Broken": {"": "#/components/schemas/Missing"}}},
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(OpenAPIContractError, match="non-empty string keys"):
        service.load(invalid_key_path)

    invalid_parameter_path = tmp_path / "invalid-openapi-path-parameter.json"
    invalid_parameter_path.write_text(
        json.dumps(
            {
                "openapi": "3.0.3",
                "info": {"title": "Invalid", "version": "1.0.0"},
                "paths": {
                    "/x/{item_id}": {
                        "get": {
                            "parameters": [
                                {
                                    "name": "wrong_id",
                                    "in": "path",
                                    "required": True,
                                    "schema": {"type": "string"},
                                }
                            ],
                            "responses": {"200": {}},
                        }
                    }
                },
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(OpenAPIContractError, match="must match its placeholders"):
        service.load(invalid_parameter_path)


def test_error_service_adds_problem_fields_without_dropping_legacy_contract():
    result = ApiErrorService.standardize(
        {"error": "not_found", "message": "Missing resource."},
        status=404,
        instance="/transactions/missing",
        request_id="req-123",
    )

    assert result["type"] == "about:blank"
    assert result["title"] == "Not Found"
    assert result["status"] == 404
    assert result["detail"] == "Missing resource."
    assert result["instance"] == "/transactions/missing"
    assert result["code"] == "not_found"
    assert result["request_id"] == "req-123"
    assert result["error"] == "not_found"
    assert result["message"] == "Missing resource."


def test_error_response_normalization_is_limited_to_json_errors():
    class Response:
        status_code = 404
        is_json = True
        mimetype = "application/json"

        def get_json(self, **_kwargs):
            return {"error": "not_found", "message": "Missing."}

        def set_data(self, data):
            self.data = data

    response = standardize_error_response(Response(), instance="/missing", request_id="req-1")

    assert response.mimetype == "application/problem+json"
    assert json.loads(response.data)["request_id"] == "req-1"

    callback_response = Response()
    callback_response.get_json = lambda **_kwargs: {
        "ResultCode": 1,
        "ResultDesc": "Provider callback error",
    }
    assert standardize_error_response(callback_response, instance="/webhook") is callback_response
    assert not hasattr(callback_response, "data")


def test_dashboard_version_and_error_contracts_are_exposed_at_runtime(monkeypatch):
    monkeypatch.setenv("PESAGUARD_PUBLIC_API_DOCS", "1")
    client = dashboard_app.test_client()

    supported = client.get("/api/v1/unregistered-governance-route")
    unsupported = client.get("/api/v2/unregistered-governance-route")
    spec_response = client.get("/openapi.json")

    assert supported.status_code == 404
    assert supported.headers["X-API-Version"] == "1.0"
    assert supported.mimetype == "application/problem+json"
    assert supported.get_json()["request_id"] == supported.headers["X-Request-ID"]
    assert unsupported.status_code == 400
    assert unsupported.get_json()["code"] == "unsupported_api_version"
    assert "X-API-Version" not in unsupported.headers
    assert spec_response.status_code == 200
    assert spec_response.get_json()["x-api-versioning"]["response_header"] == "X-API-Version"


def test_auth_api_applies_version_headers_and_standard_problem_errors():
    client = auth_app.test_client()

    supported = client.get("/api/v1/unregistered-governance-route")
    unsupported = client.get("/api/v2/unregistered-governance-route")

    assert supported.status_code == 404
    assert supported.headers["X-API-Version"] == "1.0"
    assert supported.mimetype == "application/problem+json"
    assert supported.get_json()["request_id"] == supported.headers["X-Request-ID"]
    assert unsupported.status_code == 400
    assert unsupported.get_json()["code"] == "unsupported_api_version"
