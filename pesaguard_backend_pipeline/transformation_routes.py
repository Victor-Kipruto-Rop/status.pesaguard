"""Tenant-scoped transformation pipeline REST routes.

Exposes the transformation engine, resource registry, stage store, and
traceability trace as versioned, authenticated, tenant-isolated HTTP endpoints
registered under ``/api/v1/transformation``.

Every endpoint is:
* authenticated via :func:`auth_rbac.require_auth`,
* tenant-scoped via the ``X-Tenant-ID`` header (validated against the
  authenticated principal's tenant), and
* executed against a short-lived SQLAlchemy session supplied by the caller.

Payloads never expose raw financial values beyond what the underlying
components already return (traces return hashes only, never payload contents).
"""

from __future__ import annotations

from dataclasses import asdict, is_dataclass
from datetime import datetime
from typing import Any, Callable, Dict, List, Mapping, Optional

from flask import Blueprint, jsonify, request

from auth_rbac import TENANT_ID_PATTERN, get_current_user, require_auth
from rate_limiter import RateLimiter, rate_limit
from transformation_engine import (
    TransformationError,
    execute,
    validate_definition,
)
from transformation_registry import (
    DependencyCycleError,
    RegistryError,
    ResourceNotFound,
    TransformationRegistry,
)
from transformation_store import STAGES, record_transformation_stage
from transformation_trace import TraceError, trace_transaction

MAX_PAGE_SIZE = 100
DEFAULT_PAGE_SIZE = 20

transformation_rate_limiter = RateLimiter()
transformation_rate_limiter.set_limits(
    int(
        __import__("os").getenv(
            "PESAGUARD_TRANSFORMATION_RATE_LIMIT_PER_MINUTE", "120"
        )
    )
)


def _iso(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def _serialise(value: Any) -> Any:
    """JSON-serialise dataclasses / rows while converting datetimes to ISO8601."""
    if is_dataclass(value):
        return {
            str(key): _serialise(_iso(item))
            for key, item in asdict(value).items()
        }
    if isinstance(value, list):
        return [_serialise(item) for item in value]
    if isinstance(value, tuple):
        return [_serialise(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _serialise(_iso(item)) for key, item in value.items()}
    return _iso(value)


def _json_body() -> Mapping[str, Any]:
    body = request.get_json(silent=True)
    if not isinstance(body, Mapping):
        raise ValueError("request body must be a JSON object")
    return dict(body)


def _tenant_context() -> tuple[Optional[str], Optional[tuple]]:
    """Extract and validate the tenant ID from auth + X-Tenant-ID header."""
    user = get_current_user()
    authenticated_tenant = str(getattr(user, "tenant_id", "") or "").strip()
    requested_tenant = request.headers.get("X-Tenant-ID", "").strip()

    if authenticated_tenant and requested_tenant and requested_tenant != authenticated_tenant:
        return None, (
            jsonify(
                {
                    "error": "tenant_access_denied",
                    "message": "Tenant access denied.",
                }
            ),
            403,
        )

    tenant_id = authenticated_tenant or requested_tenant
    if not tenant_id:
        return None, (
            jsonify(
                {
                    "error": "tenant_context_required",
                    "message": "Tenant context is required.",
                }
            ),
            400,
        )
    if not TENANT_ID_PATTERN.fullmatch(tenant_id):
        return None, (
            jsonify(
                {
                    "error": "invalid_tenant_id",
                    "message": "Tenant ID is invalid.",
                }
            ),
            400,
        )
    return tenant_id, None


def _pagination_params() -> tuple[int, int]:
    try:
        page = max(1, int(request.args.get("page", "1")))
    except (TypeError, ValueError):
        page = 1
    try:
        per_page = min(MAX_PAGE_SIZE, max(1, int(request.args.get("per_page", str(DEFAULT_PAGE_SIZE)))))
    except (TypeError, ValueError):
        per_page = DEFAULT_PAGE_SIZE
    return page, per_page


def _error_response(code: str, message: str, status: int) -> tuple:
    resp = jsonify({"error": code, "message": message})
    resp.status_code = status
    return resp


def create_transformation_blueprint(session_factory: Callable[[], Any]) -> Blueprint:
    """Create the transformation blueprint.

    ``session_factory`` must return a SQLAlchemy :class:`~sqlalchemy.orm.Session`
    bound to the PesaGuard store. Each route opens, uses, and closes its own
    session; write routes commit on success and roll back on failure.
    """
    blueprint = Blueprint("transformation_routes", __name__, url_prefix="/api/v1/transformation")
    registry = TransformationRegistry(session_factory)

    # ------------------------------------------------------------------
    # Engine
    # ------------------------------------------------------------------

    @blueprint.post("/validate")
    @require_auth(required_permission="read:transactions")
    @rate_limit(max_requests_per_minute=120)
    def validate_definition_route():
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        try:
            body = _json_body()
            definition = body.get("definition")
            if not isinstance(definition, Mapping):
                return _error_response("invalid_definition", "definition is required", 400)
            sample_columns = body.get("sample_columns")
            if sample_columns is not None and not isinstance(sample_columns, Mapping):
                return _error_response(
                    "invalid_sample_columns", "sample_columns must be an object", 400
                )
            check = validate_definition(definition, sample_columns)
            return jsonify(_serialise(check)), 200
        except ValueError as exc:
            return _error_response("invalid_request", str(exc), 400)

    @blueprint.post("/execute")
    @require_auth(required_permission="write:transactions")
    @rate_limit(max_requests_per_minute=120)
    def execute_route():
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        try:
            body = _json_body()
            definition = body.get("definition")
            inputs = body.get("inputs", {})
            if not isinstance(definition, Mapping):
                return _error_response("invalid_definition", "definition is required", 400)
            if not isinstance(inputs, Mapping):
                return _error_response("invalid_inputs", "inputs must be an object", 400)

            session = session_factory()
            try:
                run = execute(
                    definition,
                    dict(inputs),
                    tenant_id=tenant_id,
                    session=session,
                )
                session.commit()
            except Exception:
                session.rollback()
                raise
            finally:
                session.close()

            return jsonify(
                {
                    "execution_id": run.execution_id,
                    "output": run.output,
                    "row_count": run.row_count,
                    "steps": _serialise(run.steps),
                }
            ), 200
        except TransformationError as exc:
            return _error_response("invalid_definition", str(exc), 400)
        except ValueError as exc:
            return _error_response("invalid_request", str(exc), 400)

    # ------------------------------------------------------------------
    # Registry
    # ------------------------------------------------------------------

    @blueprint.post("/resources")
    @require_auth(required_permission="write:transactions")
    @rate_limit(max_requests_per_minute=120)
    def create_resource_route():
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        try:
            body = _json_body()
            resource_type = body.get("resource_type")
            name = body.get("name")
            definition = body.get("definition")
            if not isinstance(resource_type, str) or not resource_type.strip():
                return _error_response("invalid_resource_type", "resource_type is required", 400)
            if not isinstance(name, str) or not name.strip():
                return _error_response("invalid_name", "name is required", 400)
            if not isinstance(definition, Mapping):
                return _error_response("invalid_definition", "definition is required", 400)
            description = body.get("description", "") or ""
            tags = body.get("tags", []) or []
            if not isinstance(tags, list) or not all(isinstance(tag, str) for tag in tags):
                return _error_response("invalid_tags", "tags must be a list of strings", 400)

            created = registry.create(
                tenant_id=tenant_id,
                resource_type=resource_type.strip(),
                name=name.strip(),
                definition=dict(definition),
                description=str(description),
                tags=list(tags),
            )
            return jsonify(_serialise(created)), 201
        except RegistryError as exc:
            return _error_response("registry_error", str(exc), 400)
        except ValueError as exc:
            return _error_response("invalid_request", str(exc), 400)

    @blueprint.get("/resources/<name>")
    @require_auth(required_permission="read:transactions")
    @rate_limit(max_requests_per_minute=120)
    def get_resource_route(name: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        resource_type = request.args.get("resource_type")
        version = request.args.get("version", type=int)
        try:
            fetched = registry.get(
                tenant_id=tenant_id,
                name=name,
                resource_type=resource_type,
                version=version,
            )
            return jsonify(_serialise(fetched)), 200
        except ResourceNotFound as exc:
            return _error_response("not_found", str(exc), 404)
        except RegistryError as exc:
            return _error_response("registry_error", str(exc), 400)

    @blueprint.post("/resources/<name>/versions")
    @require_auth(required_permission="write:transactions")
    @rate_limit(max_requests_per_minute=120)
    def save_version_route(name: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        try:
            body = _json_body()
            definition = body.get("definition")
            if not isinstance(definition, Mapping):
                return _error_response("invalid_definition", "definition is required", 400)
            resource_type = body.get("resource_type")
            description = body.get("description", "") or ""
            tags = body.get("tags", []) or []
            if not isinstance(tags, list) or not all(isinstance(tag, str) for tag in tags):
                return _error_response("invalid_tags", "tags must be a list of strings", 400)

            saved = registry.save_version(
                tenant_id=tenant_id,
                name=name,
                definition=dict(definition),
                resource_type=resource_type,
                description=str(description),
                tags=list(tags),
            )
            return jsonify(_serialise(saved)), 201
        except ResourceNotFound as exc:
            return _error_response("not_found", str(exc), 404)
        except RegistryError as exc:
            return _error_response("registry_error", str(exc), 400)
        except ValueError as exc:
            return _error_response("invalid_request", str(exc), 400)

    @blueprint.get("/resources")
    @require_auth(required_permission="read:transactions")
    @rate_limit(max_requests_per_minute=120)
    def search_resources_route():
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        resource_type = request.args.get("resource_type")
        query = request.args.get("query")
        tags_param = request.args.get("tags")
        tags = None
        if tags_param is not None:
            tags = [tag.strip() for tag in tags_param.split(",") if tag.strip()]
        limit = request.args.get("limit", type=int)
        try:
            summaries = registry.search(
                tenant_id=tenant_id,
                resource_type=resource_type,
                query=query,
                tags=tags,
                limit=limit,
            )
            return jsonify({"resources": _serialise(summaries)}), 200
        except RegistryError as exc:
            return _error_response("registry_error", str(exc), 400)

    @blueprint.get("/resources/<name>/versions")
    @require_auth(required_permission="read:transactions")
    @rate_limit(max_requests_per_minute=120)
    def resource_history_route(name: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        limit = request.args.get("limit", default=20, type=int)
        try:
            entries = registry.history(tenant_id=tenant_id, name=name, limit=limit)
            return jsonify({"versions": _serialise(entries)}), 200
        except ResourceNotFound as exc:
            return _error_response("not_found", str(exc), 404)
        except RegistryError as exc:
            return _error_response("registry_error", str(exc), 400)

    @blueprint.get("/resources/<name>/execution-order")
    @require_auth(required_permission="read:transactions")
    @rate_limit(max_requests_per_minute=120)
    def execution_order_route(name: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        resource_type = request.args.get("resource_type", "dataset")
        try:
            order = registry.resolve_execution_order(
                tenant_id=tenant_id, root=name, resource_type=resource_type
            )
            return jsonify({"root": name, "order": order}), 200
        except ResourceNotFound as exc:
            return _error_response("not_found", str(exc), 404)
        except (RegistryError, DependencyCycleError) as exc:
            return _error_response("registry_error", str(exc), 400)

    # ------------------------------------------------------------------
    # Store
    # ------------------------------------------------------------------

    @blueprint.post("/stages")
    @require_auth(required_permission="write:transactions")
    @rate_limit(max_requests_per_minute=120)
    def record_stage_route():
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        try:
            body = _json_body()
            transaction_id = body.get("transaction_id")
            event_id = body.get("event_id")
            stage = body.get("stage")
            payload = body.get("payload")
            source_event_id = body.get("source_event_id", "") or ""
            if not isinstance(transaction_id, str) or not transaction_id.strip():
                return _error_response("invalid_transaction_id", "transaction_id is required", 400)
            if not isinstance(event_id, str) or not event_id.strip():
                return _error_response("invalid_event_id", "event_id is required", 400)
            if not isinstance(stage, str) or str(stage).upper() not in STAGES:
                return _error_response(
                    "invalid_stage",
                    f"stage must be one of {sorted(STAGES)}",
                    400,
                )
            if not isinstance(payload, Mapping):
                return _error_response("invalid_payload", "payload is required", 400)

            session = session_factory()
            try:
                record = record_transformation_stage(
                    session,
                    tenant_id=tenant_id,
                    transaction_id=transaction_id.strip(),
                    event_id=event_id.strip(),
                    stage=str(stage).upper(),
                    payload=dict(payload),
                    source_event_id=str(source_event_id).strip(),
                )
                session.commit()
            except Exception:
                session.rollback()
                raise
            finally:
                session.close()

            return jsonify(
                {
                    "id": record.id,
                    "tenant_id": record.tenant_id,
                    "transaction_id": record.transaction_id,
                    "event_id": record.event_id,
                    "stage": record.stage,
                    "payload_hash": record.payload_hash,
                    "created_at": _iso(record.created_at),
                }
            ), 201
        except ValueError as exc:
            return _error_response("invalid_request", str(exc), 400)

    # ------------------------------------------------------------------
    # Trace
    # ------------------------------------------------------------------

    @blueprint.get("/trace/<transaction_id>")
    @require_auth(required_permission="read:transactions")
    @rate_limit(max_requests_per_minute=120)
    def trace_route(transaction_id: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        session = session_factory()
        try:
            trace = trace_transaction(session, tenant_id=tenant_id, transaction_id=transaction_id)
        finally:
            session.close()
        return jsonify(_serialise(trace.to_payload())), 200

    return blueprint
