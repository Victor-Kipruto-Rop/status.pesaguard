"""
Versioned Data APIs and developer infrastructure for PesaGuard.

Consolidates versioned transaction APIs, reconciliation result APIs,
risk-event APIs, report/export APIs, webhook event contracts, API usage/
latency metrics, idempotency keys, pagination/filtering, rate limiting,
API schema documentation, and request tracing/correlation IDs.

All endpoints are registered under the /api/v1 URL prefix and are
tenant-isolated, authenticated, rate-limited, and traced.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from typing import Any, Dict, Optional

from flask import Blueprint, jsonify, request
from sqlalchemy import or_

from api_governance import ApiVersioningService
from auth_rbac import TENANT_ID_PATTERN, get_current_user, require_auth
from logging_utils import get_correlation_id
from metrics import record_business_metric, record_http_request
from models import (
    Alert,
    Discrepancy,
    FraudRiskAssessment,
    IdempotencyRecord,
    ProcessedTransaction,
    RateLimitRecord,
    ReconciliationMatch,
    ReportExport,
    RiskEvent,
    ScheduledReport,
    Transaction,
    TransactionEvent,
    TransactionVersion,
    WebhookConfig,
)
from rate_limiter import RateLimiter, rate_limit
from request_context import RequestContext

bp = Blueprint("api_routes", __name__, url_prefix="/api/v1")

api_versioning_service = ApiVersioningService()
api_rate_limiter = RateLimiter()
api_rate_limiter.set_limits(int(os.getenv("PESAGUARD_API_RATE_LIMIT_PER_MINUTE", "120")))


def _tenant_id_from_context() -> tuple[Optional[str], Optional[tuple]]:
    """Extract and validate tenant ID from authenticated user + X-Tenant-ID header."""
    user = get_current_user()
    authenticated_tenant = str(getattr(user, "tenant_id", "") or "").strip()
    requested_tenant = request.headers.get("X-Tenant-ID", "").strip()

    if authenticated_tenant and requested_tenant and requested_tenant != authenticated_tenant:
        return None, (
            jsonify({"error": "tenant_access_denied", "message": "Tenant access denied."}),
            403,
        )

    tenant_id = authenticated_tenant or requested_tenant
    if not tenant_id:
        return None, (
            jsonify({"error": "tenant_context_required", "message": "Tenant context is required."}),
            400,
        )
    if not TENANT_ID_PATTERN.fullmatch(tenant_id):
        return None, (
            jsonify({"error": "invalid_tenant_id", "message": "Tenant ID is invalid."}),
            400,
        )
    return tenant_id, None


def _pagination_params() -> tuple[int, int]:
    """Parse page and per_page query params with sane bounds."""
    try:
        page = max(1, int(request.args.get("page", "1")))
    except (TypeError, ValueError):
        page = 1
    try:
        per_page = min(100, max(1, int(request.args.get("per_page", "20"))))
    except (TypeError, ValueError):
        per_page = 20
    return page, per_page


def _paginate(query: Any, page: int, per_page: int) -> tuple[list[Any], int]:
    """Apply pagination to a SQLAlchemy query and return (items, total)."""
    total = query.count()
    items = query.offset((page - 1) * per_page).limit(per_page).all()
    return items, total


def _meta_pagination(page: int, per_page: int, total: int) -> Dict[str, Any]:
    pages = max(1, (total + per_page - 1) // per_page)
    return {
        "page": page,
        "per_page": per_page,
        "total": total,
        "pages": pages,
        "has_next": page < pages,
        "has_prev": page > 1,
    }


def _iso(dt: Optional[datetime]) -> Optional[str]:
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.isoformat()


def _record_api_metric(endpoint: str, status: int, tenant_id: str) -> None:
    """Record API usage/latency business metrics."""
    try:
        record_http_request(endpoint=endpoint, status=status)
        record_business_metric("api_requests_total")
        if status >= 400:
            record_business_metric("api_request_errors_total")
    except Exception:
        pass


def _apply_rate_limit(tenant_id: str, endpoint: str) -> Optional[tuple]:
    """Apply per-tenant rate limiting; returns a 429 response if limited."""
    try:
        client_id = f"tenant:{tenant_id}"
        allowed, status = api_rate_limiter.is_allowed(client_id, endpoint)
        if not allowed:
            retry_after = status.get("reset_in", 60)
            resp = jsonify({
                "error": "rate_limit_exceeded",
                "message": "Too many requests. Please slow down.",
                "retry_after": retry_after,
            })
            resp.status_code = 429
            resp.headers["Retry-After"] = str(retry_after)
            resp.headers["X-RateLimit-Limit"] = str(status.get("limit", 120))
            resp.headers["X-RateLimit-Remaining"] = "0"
            resp.headers["X-RateLimit-Reset"] = str(retry_after)
            return resp
    except Exception:
        try:
            from metrics import record_security_event
            record_security_event()
        except Exception:
            pass
    return None


def _response_headers() -> Dict[str, str]:
    headers: Dict[str, str] = {}
    correlation_id = get_correlation_id()
    if correlation_id:
        headers["X-Correlation-ID"] = correlation_id
    headers["X-API-Version"] = "1.0"
    return headers


# ---------------------------------------------------------------------------
# Transactions
# ---------------------------------------------------------------------------

@bp.get("/transactions")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def list_transactions():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/transactions")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        query = session.query(Transaction).filter(Transaction.tenant_id == tenant_id)

        status = request.args.get("status", "").strip()
        if status:
            query = query.filter(Transaction.status == status)
        provider = request.args.get("provider", "").strip()
        if provider:
            query = query.filter(Transaction.provider == provider)
        currency = request.args.get("currency", "").strip()
        if currency:
            query = query.filter(Transaction.currency == currency.upper())
        trans_id = request.args.get("trans_id", "").strip()
        if trans_id:
            query = query.filter(Transaction.trans_id == trans_id)
        msisdn = request.args.get("msisdn", "").strip()
        if msisdn:
            query = query.filter(Transaction.msisdn == msisdn)
        from_str = request.args.get("from", "").strip()
        if from_str:
            try:
                from_dt = datetime.fromisoformat(from_str.replace("Z", "+00:00"))
                query = query.filter(Transaction.created_at >= from_dt)
            except ValueError:
                resp = jsonify({"error": "invalid_date_format", "message": "Invalid 'from' ISO timestamp."})
                resp.status_code = 400
                for k, v in _response_headers().items():
                    resp.headers[k] = v
                return resp
        to_str = request.args.get("to", "").strip()
        if to_str:
            try:
                to_dt = datetime.fromisoformat(to_str.replace("Z", "+00:00"))
                query = query.filter(Transaction.created_at <= to_dt)
            except ValueError:
                resp = jsonify({"error": "invalid_date_format", "message": "Invalid 'to' ISO timestamp."})
                resp.status_code = 400
                for k, v in _response_headers().items():
                    resp.headers[k] = v
                return resp
        min_amount = request.args.get("min_amount", "").strip()
        if min_amount:
            try:
                query = query.filter(Transaction.trans_amount >= float(min_amount))
            except ValueError:
                resp = jsonify({"error": "invalid_amount", "message": "Invalid min_amount."})
                resp.status_code = 400
                for k, v in _response_headers().items():
                    resp.headers[k] = v
                return resp
        max_amount = request.args.get("max_amount", "").strip()
        if max_amount:
            try:
                query = query.filter(Transaction.trans_amount <= float(max_amount))
            except ValueError:
                resp = jsonify({"error": "invalid_amount", "message": "Invalid max_amount."})
                resp.status_code = 400
                for k, v in _response_headers().items():
                    resp.headers[k] = v
                return resp

        page, per_page = _pagination_params()
        items, total = _paginate(query, page, per_page)

        results = []
        for t in items:
            results.append({
                "id": t.id,
                "tenant_id": t.tenant_id,
                "trans_id": t.trans_id,
                "status": t.status,
                "trans_amount": str(t.trans_amount),
                "currency": t.currency,
                "provider": t.provider,
                "msisdn": t.msisdn,
                "business_short_code": t.business_short_code,
                "trans_time": t.trans_time,
                "external_reference": t.external_reference,
                "provider_transaction_id": t.provider_transaction_id,
                "idempotency_key": t.idempotency_key,
                "lifecycle_stage": t.lifecycle_stage,
                "created_at": _iso(t.created_at),
            })

        resp = jsonify({
            "items": results,
            "meta": _meta_pagination(page, per_page, total),
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/transactions", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "list_failed", "message": "Failed to list transactions."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/transactions", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.get("/transactions/<transaction_id>/versions")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def get_transaction_versions(transaction_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/transactions/<id>/versions")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        transaction = session.query(Transaction).filter(
            Transaction.tenant_id == tenant_id,
            or_(
                Transaction.id == transaction_id,
                Transaction.trans_id == transaction_id,
            ),
        ).one_or_none()

        if transaction is None:
            resp = jsonify({"error": "not_found", "message": "Transaction not found."})
            resp.status_code = 404
            for k, v in _response_headers().items():
                resp.headers[k] = v
            return resp

        page, per_page = _pagination_params()
        query = session.query(TransactionVersion).filter(
            TransactionVersion.tenant_id == tenant_id,
            TransactionVersion.transaction_id == transaction.id,
        ).order_by(TransactionVersion.version_number.desc())

        items, total = _paginate(query, page, per_page)

        results = []
        for v in items:
            results.append({
                "id": v.id,
                "version_number": v.version_number,
                "changed_fields": v.changed_fields,
                "previous_values": v.previous_values,
                "new_values": v.new_values,
                "changed_by": v.changed_by,
                "change_reason": v.change_reason,
                "created_at": _iso(v.created_at),
            })

        resp = jsonify({
            "transaction_id": transaction.id,
            "trans_id": transaction.trans_id,
            "items": results,
            "meta": _meta_pagination(page, per_page, total),
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/transactions/<id>/versions", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "fetch_failed", "message": "Failed to retrieve transaction versions."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/transactions/<id>/versions", 500, tenant_id)
        return resp
    finally:
        session.close()


# ---------------------------------------------------------------------------
# Reconciliation results
# ---------------------------------------------------------------------------

@bp.get("/reconciliation")
@require_auth(required_permission="read:reconciliation")
@rate_limit(max_requests_per_minute=120)
def list_reconciliation():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/reconciliation")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        page, per_page = _pagination_params()

        match_query = session.query(ReconciliationMatch).filter(
            ReconciliationMatch.tenant_id == tenant_id
        )
        status = request.args.get("status", "").strip()
        if status:
            match_query = match_query.filter(ReconciliationMatch.status == status)
        from_str = request.args.get("from", "").strip()
        if from_str:
            try:
                from_dt = datetime.fromisoformat(from_str.replace("Z", "+00:00"))
                match_query = match_query.filter(ReconciliationMatch.created_at >= from_dt)
            except ValueError:
                resp = jsonify({"error": "invalid_date_format", "message": "Invalid 'from' ISO timestamp."})
                resp.status_code = 400
                for k, v in _response_headers().items():
                    resp.headers[k] = v
                return resp
        matches, match_total = _paginate(match_query, page, per_page)

        disc_query = session.query(Discrepancy).filter(Discrepancy.tenant_id == tenant_id)
        disc_status = request.args.get("discrepancy_status", "").strip()
        if disc_status:
            disc_query = disc_query.filter(Discrepancy.status == disc_status)
        discrepancies, disc_total = _paginate(disc_query, page, per_page)

        match_results = []
        for m in matches:
            match_results.append({
                "id": m.id,
                "tenant_id": m.tenant_id,
                "transaction_id": m.transaction_id,
                "status": m.status,
                "matched_amount": str(m.matched_amount) if m.matched_amount is not None else None,
                "discrepancy_amount": str(m.discrepancy_amount) if m.discrepancy_amount is not None else None,
                "match_type": m.match_type,
                "confidence_score": str(m.confidence_score) if m.confidence_score is not None else None,
                "reason_codes": m.reason_codes,
                "created_at": _iso(m.created_at),
                "updated_at": _iso(m.updated_at),
            })

        disc_results = []
        for d in discrepancies:
            disc_results.append({
                "id": d.id,
                "tenant_id": d.tenant_id,
                "trans_id": d.trans_id,
                "anomaly_type": d.anomaly_type,
                "status": d.status,
                "severity": d.severity,
                "details": d.details,
                "resolved": d.resolved,
                "detected_at": _iso(d.detected_at),
                "resolved_at": _iso(d.resolved_at),
            })

        resp = jsonify({
            "matches": match_results,
            "discrepancies": disc_results,
            "meta": {
                "matches": _meta_pagination(page, per_page, match_total),
                "discrepancies": _meta_pagination(page, per_page, disc_total),
            },
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reconciliation", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "list_failed", "message": "Failed to list reconciliation results."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reconciliation", 500, tenant_id)
        return resp
    finally:
        session.close()


# ---------------------------------------------------------------------------
# Risk events
# ---------------------------------------------------------------------------

@bp.get("/risk-events")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def list_risk_events():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/risk-events")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        page, per_page = _pagination_params()
        query = session.query(RiskEvent).filter(RiskEvent.tenant_id == tenant_id)
        risk_level = request.args.get("risk_level", "").strip()
        if risk_level:
            query = query.filter(RiskEvent.risk_level == risk_level)
        status = request.args.get("status", "").strip()
        if status:
            query = query.filter(RiskEvent.status == status)
        event_type = request.args.get("event_type", "").strip()
        if event_type:
            query = query.filter(RiskEvent.event_type == event_type)
        items, total = _paginate(query, page, per_page)
        results = []
        for e in items:
            results.append({
                "id": e.id,
                "tenant_id": e.tenant_id,
                "event_type": e.event_type,
                "risk_level": e.risk_level,
                "risk_score": str(e.risk_score),
                "transaction_id": e.transaction_id,
                "description": e.description,
                "evidence": e.evidence,
                "status": e.status,
                "resolved_at": _iso(e.resolved_at),
                "resolution_note": e.resolution_note,
                "created_at": _iso(e.created_at),
                "updated_at": _iso(e.updated_at),
            })
        resp = jsonify({
            "items": results,
            "meta": _meta_pagination(page, per_page, total),
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "list_failed", "message": "Failed to list risk events."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.get("/risk-events/<event_id>")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def get_risk_event(event_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/risk-events/<id>")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        event = session.query(RiskEvent).filter(
            RiskEvent.tenant_id == tenant_id,
            RiskEvent.id == event_id,
        ).one_or_none()
        if event is None:
            resp = jsonify({"error": "not_found", "message": "Risk event not found."})
            resp.status_code = 404
            for k, v in _response_headers().items():
                resp.headers[k] = v
            return resp
        resp = jsonify(_risk_event_payload(event))
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "fetch_failed", "message": "Failed to retrieve risk event."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>", 500, tenant_id)
        return resp
    finally:
        session.close()


# ---------------------------------------------------------------------------
# Risk-event investigation workflow
# ---------------------------------------------------------------------------


def _actor_from_context() -> str:
    """Best-effort authenticated principal for audit attribution."""
    user = get_current_user()
    for attr in ("username", "user_id", "id"):
        value = str(getattr(user, attr, "") or "").strip()
        if value:
            return value
    return "api"


def _risk_event_payload(event) -> Dict[str, Any]:
    evidence = event.evidence or {}
    try:
        from fraud_risk_engine import risk_priority

        priority = risk_priority(event.risk_level, evidence.get("rules_triggered") or [])
    except (ValueError, TypeError):
        priority = None
    return {
        "id": event.id,
        "tenant_id": event.tenant_id,
        "event_type": event.event_type,
        "risk_level": event.risk_level,
        "risk_score": str(event.risk_score),
        "priority": priority,
        "transaction_id": event.transaction_id,
        "description": event.description,
        "evidence": evidence,
        "status": event.status,
        "resolved_at": _iso(event.resolved_at),
        "resolution_note": event.resolution_note,
        "created_at": _iso(event.created_at),
        "updated_at": _iso(event.updated_at),
    }


@bp.post("/risk-events/<event_id>/investigation")
@require_auth(required_permission="write:transactions")
@rate_limit(max_requests_per_minute=60)
def start_risk_investigation(event_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/risk-events/<id>/investigation")
    if rl_response is not None:
        return rl_response

    payload = request.get_json(silent=True) or {}
    if not isinstance(payload, dict):
        payload = {}
    note = payload.get("note", "")
    if note and not isinstance(note, str):
        resp = jsonify({"error": "invalid_note", "message": "note must be a string."})
        resp.status_code = 400
        for k, v in _response_headers().items():
            resp.headers[k] = v
        return resp

    from event_store import EventStore
    from risk_indicators import InvalidInvestigationTransition, RiskEventNotFound, start_investigation

    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        event = start_investigation(
            session,
            tenant_id=tenant_id,
            event_id=event_id,
            actor=_actor_from_context(),
            note=note,
        )
        session.commit()
        resp = jsonify(_risk_event_payload(event))
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/investigation", 200, tenant_id)
        return resp
    except RiskEventNotFound:
        session.rollback()
        resp = jsonify({"error": "not_found", "message": "Risk event not found."})
        resp.status_code = 404
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/investigation", 404, tenant_id)
        return resp
    except InvalidInvestigationTransition as exc:
        session.rollback()
        resp = jsonify({"error": "invalid_transition", "message": str(exc)})
        resp.status_code = 409
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/investigation", 409, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "investigation_failed", "message": "Failed to start investigation."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/investigation", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.post("/risk-events/<event_id>/resolution")
@require_auth(required_permission="write:transactions")
@rate_limit(max_requests_per_minute=60)
def resolve_risk_event(event_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/risk-events/<id>/resolution")
    if rl_response is not None:
        return rl_response

    payload = request.get_json(silent=True) or {}
    if not isinstance(payload, dict):
        payload = {}
    outcome = str(payload.get("outcome") or "").strip()
    note = payload.get("note", "")
    from risk_indicators import VALID_OUTCOMES

    if outcome not in VALID_OUTCOMES:
        resp = jsonify({
            "error": "invalid_outcome",
            "message": f"outcome must be one of: {', '.join(VALID_OUTCOMES)}.",
        })
        resp.status_code = 400
        for k, v in _response_headers().items():
            resp.headers[k] = v
        return resp
    if note and not isinstance(note, str):
        resp = jsonify({"error": "invalid_note", "message": "note must be a string."})
        resp.status_code = 400
        for k, v in _response_headers().items():
            resp.headers[k] = v
        return resp

    from event_store import EventStore
    from risk_indicators import InvalidInvestigationTransition, RiskEventNotFound, record_outcome

    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        event = record_outcome(
            session,
            tenant_id=tenant_id,
            event_id=event_id,
            actor=_actor_from_context(),
            outcome=outcome,
            note=note,
        )
        session.commit()
        resp = jsonify(_risk_event_payload(event))
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/resolution", 200, tenant_id)
        return resp
    except RiskEventNotFound:
        session.rollback()
        resp = jsonify({"error": "not_found", "message": "Risk event not found."})
        resp.status_code = 404
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/resolution", 404, tenant_id)
        return resp
    except InvalidInvestigationTransition as exc:
        session.rollback()
        resp = jsonify({"error": "invalid_transition", "message": str(exc)})
        resp.status_code = 409
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/resolution", 409, tenant_id)
        return resp
    except ValueError as exc:
        session.rollback()
        resp = jsonify({"error": "invalid_request", "message": str(exc)})
        resp.status_code = 400
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/resolution", 400, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "resolution_failed", "message": "Failed to record outcome."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/resolution", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.get("/risk-events/<event_id>/audit")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def risk_event_audit(event_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/risk-events/<id>/audit")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    from risk_indicators import RiskEventNotFound, investigation_audit_trail

    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        entries = investigation_audit_trail(
            session, tenant_id=tenant_id, event_id=event_id
        )
        resp = jsonify({"event_id": event_id, "items": entries})
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/audit", 200, tenant_id)
        return resp
    except RiskEventNotFound:
        resp = jsonify({"error": "not_found", "message": "Risk event not found."})
        resp.status_code = 404
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/audit", 404, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "audit_failed", "message": "Failed to fetch audit trail."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/<id>/audit", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.get("/risk-events/metrics")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def risk_event_metrics():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/risk-events/metrics")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    from risk_indicators import false_positive_metrics

    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        metrics_payload = false_positive_metrics(session, tenant_id)
        resp = jsonify(metrics_payload)
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/metrics", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "metrics_failed", "message": "Failed to compute risk metrics."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/risk-events/metrics", 500, tenant_id)
        return resp
    finally:
        session.close()

    # ---------------------------------------------------------------------------
    # Alert center + escalation
    # ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------

@bp.get("/reports")
@require_auth(required_permission="read:reports")
@rate_limit(max_requests_per_minute=120)
def list_reports():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/reports")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        page, per_page = _pagination_params()
        query = session.query(ReportExport).filter(ReportExport.tenant_id == tenant_id)
        report_type = request.args.get("report_type", "").strip()
        if report_type:
            query = query.filter(ReportExport.report_type == report_type)
        status = request.args.get("status", "").strip()
        if status:
            query = query.filter(ReportExport.status == status)
        fmt = request.args.get("format", "").strip()
        if fmt:
            query = query.filter(ReportExport.format == fmt)
        items, total = _paginate(query, page, per_page)
        results = []
        for r in items:
            results.append({
                "id": r.id,
                "tenant_id": r.tenant_id,
                "report_type": r.report_type,
                "format": r.format,
                "status": r.status,
                "record_count": r.record_count,
                "file_url": r.file_url,
                "expires_at": _iso(r.expires_at),
                "download_count": r.download_count,
                "filters": r.filters,
                "created_at": _iso(r.created_at),
                "completed_at": _iso(r.completed_at),
                "error_message": r.error_message,
            })
        resp = jsonify({
            "items": results,
            "meta": _meta_pagination(page, per_page, total),
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reports", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "list_failed", "message": "Failed to list reports."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reports", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.post("/reports")
@require_auth(required_permission="write:reports")
@rate_limit(max_requests_per_minute=30)
def create_report():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/reports")
    if rl_response is not None:
        return rl_response

    payload = request.get_json(silent=True) or {}
    report_type = payload.get("report_type", "").strip()
    fmt = payload.get("format", "csv").strip()
    filters = payload.get("filters", {})
    if not report_type:
        resp = jsonify({"error": "missing_field", "message": "report_type is required."})
        resp.status_code = 400
        for k, v in _response_headers().items():
            resp.headers[k] = v
        return resp
    if fmt not in ("csv", "json", "pdf"):
        resp = jsonify({"error": "invalid_format", "message": "format must be csv, json, or pdf."})
        resp.status_code = 400
        for k, v in _response_headers().items():
            resp.headers[k] = v
        return resp

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        report = ReportExport(
            tenant_id=tenant_id,
            report_type=report_type,
            format=fmt,
            status="queued",
            filters=filters if isinstance(filters, dict) else {},
        )
        session.add(report)
        session.commit()
        session.refresh(report)
        result = {
            "id": report.id,
            "tenant_id": report.tenant_id,
            "report_type": report.report_type,
            "format": report.format,
            "status": report.status,
            "record_count": report.record_count,
            "file_url": report.file_url,
            "expires_at": _iso(report.expires_at),
            "download_count": report.download_count,
            "filters": report.filters,
            "created_at": _iso(report.created_at),
            "completed_at": _iso(report.completed_at),
            "error_message": report.error_message,
        }
        resp = jsonify(result)
        resp.status_code = 201
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reports", 201, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "create_failed", "message": "Failed to create report export."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reports", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.get("/reports/<report_id>")
@require_auth(required_permission="read:reports")
@rate_limit(max_requests_per_minute=120)
def get_report(report_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/reports/<id>")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        report = session.query(ReportExport).filter(
            ReportExport.tenant_id == tenant_id,
            ReportExport.id == report_id,
        ).one_or_none()
        if report is None:
            resp = jsonify({"error": "not_found", "message": "Report not found."})
            resp.status_code = 404
            for k, v in _response_headers().items():
                resp.headers[k] = v
            return resp
        result = {
            "id": report.id,
            "tenant_id": report.tenant_id,
            "report_type": report.report_type,
            "format": report.format,
            "status": report.status,
            "record_count": report.record_count,
            "file_url": report.file_url,
            "expires_at": _iso(report.expires_at),
            "download_count": report.download_count,
            "filters": report.filters,
            "created_at": _iso(report.created_at),
            "completed_at": _iso(report.completed_at),
            "error_message": report.error_message,
        }
        resp = jsonify(result)
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reports/<id>", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "fetch_failed", "message": "Failed to retrieve report."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/reports/<id>", 500, tenant_id)
        return resp
    finally:
        session.close()


# ---------------------------------------------------------------------------
# Webhook configs
# ---------------------------------------------------------------------------

@bp.get("/webhooks")
@require_auth(required_permission="read:webhooks")
@rate_limit(max_requests_per_minute=120)
def list_webhooks():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/webhooks")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        page, per_page = _pagination_params()
        query = session.query(WebhookConfig).filter(WebhookConfig.tenant_id == tenant_id)
        items, total = _paginate(query, page, per_page)
        results = []
        for w in items:
            results.append({
                "id": w.id,
                "tenant_id": w.tenant_id,
                "name": w.name,
                "url": w.url,
                "description": w.description,
                "secret_prefix": (w.signing_secret[:8] + "...") if w.signing_secret else None,
                "timeout_seconds": w.timeout_seconds,
                "created_at": _iso(w.created_at),
                "updated_at": _iso(w.updated_at),
            })
        resp = jsonify({
            "items": results,
            "meta": _meta_pagination(page, per_page, total),
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/webhooks", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "list_failed", "message": "Failed to list webhooks."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/webhooks", 500, tenant_id)
        return resp
    finally:
        session.close()


@bp.get("/webhooks/<webhook_id>")
@require_auth(required_permission="read:webhooks")
@rate_limit(max_requests_per_minute=120)
def get_webhook(webhook_id: str):
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/webhooks/<id>")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        webhook = session.query(WebhookConfig).filter(
            WebhookConfig.tenant_id == tenant_id,
            WebhookConfig.id == webhook_id,
        ).one_or_none()
        if webhook is None:
            resp = jsonify({"error": "not_found", "message": "Webhook not found."})
            resp.status_code = 404
            for k, v in _response_headers().items():
                resp.headers[k] = v
            return resp
        result = {
            "id": webhook.id,
            "tenant_id": webhook.tenant_id,
            "name": webhook.name,
            "url": webhook.url,
            "description": webhook.description,
            "secret_prefix": (webhook.signing_secret[:8] + "...") if webhook.signing_secret else None,
            "timeout_seconds": webhook.timeout_seconds,
            "created_at": _iso(webhook.created_at),
            "updated_at": _iso(webhook.updated_at),
        }
        resp = jsonify(result)
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/webhooks/<id>", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "fetch_failed", "message": "Failed to retrieve webhook."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/webhooks/<id>", 500, tenant_id)
        return resp
    finally:
        session.close()


# ---------------------------------------------------------------------------
# Idempotency keys
# ---------------------------------------------------------------------------

@bp.get("/idempotency")
@require_auth(required_permission="read:transactions")
@rate_limit(max_requests_per_minute=120)
def list_idempotency_keys():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/idempotency")
    if rl_response is not None:
        return rl_response

    from event_store import EventStore
    event_store = EventStore()
    event_store._ensure_ready()
    session = event_store.Session()

    try:
        page, per_page = _pagination_params()
        query = session.query(IdempotencyRecord).filter(IdempotencyRecord.tenant_id == tenant_id)
        items, total = _paginate(query, page, per_page)
        results = []
        for r in items:
            results.append({
                "id": r.id,
                "tenant_id": r.tenant_id,
                "provider": r.provider,
                "idempotency_key": r.idempotency_key,
                "external_reference": r.external_reference,
                "provider_transaction_id": r.provider_transaction_id,
                "created_at": _iso(r.created_at),
                "has_response": r.response is not None,
            })
        resp = jsonify({
            "items": results,
            "meta": _meta_pagination(page, per_page, total),
        })
        resp.status_code = 200
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/idempotency", 200, tenant_id)
        return resp
    except Exception:
        session.rollback()
        resp = jsonify({"error": "list_failed", "message": "Failed to list idempotency keys."})
        resp.status_code = 500
        for k, v in _response_headers().items():
            resp.headers[k] = v
        _record_api_metric("/api/v1/idempotency", 500, tenant_id)
        return resp
    finally:
        session.close()


# ---------------------------------------------------------------------------
# API metrics
# ---------------------------------------------------------------------------

@bp.get("/metrics")
@require_auth(required_permission="read:metrics")
@rate_limit(max_requests_per_minute=60)
def api_metrics():
    tenant_id, error = _tenant_id_from_context()
    if error is not None:
        return error

    rl_response = _apply_rate_limit(tenant_id, "/api/v1/metrics")
    if rl_response is not None:
        return rl_response

    try:
        from metrics import build_metrics_payload
        payload = build_metrics_payload()
    except Exception:
        payload = {}

    resp = jsonify({
        "tenant_id": tenant_id,
        "metrics": payload,
    })
    resp.status_code = 200
    for k, v in _response_headers().items():
        resp.headers[k] = v
    _record_api_metric("/api/v1/metrics", 200, tenant_id)
    return resp
