"""Tenant-scoped HTTP routes for the double-entry ledger.

Follows the transaction_routes.py conventions exactly: Flask Blueprint under
/api/v1, @require_auth permission checks, authenticated-tenant scoping that
never trusts a client-supplied tenant_id (Rule #11), Idempotency-Key on writes
(Rule #9), and minor-unit integer money (Rule #7). Ledger/account creation is
admin-only; posting/reading is transaction-permission scoped.
"""

from __future__ import annotations

import hashlib
import json
from typing import Callable

from flask import Blueprint, jsonify, request
from sqlalchemy.orm import Session

from auth_rbac import TENANT_ID_PATTERN, get_current_user, require_auth
from double_entry_ledger import (
    LedgerConflictError,
    LedgerEntryInput,
    LedgerError,
    LedgerNotFoundError,
    LedgerStateError,
    LedgerUnbalancedError,
    account_balance,
    get_transaction,
    post_transaction,
    to_minor_units,
    void_pending_transaction,
)
from models import Ledger, LedgerAccount, LedgerTransaction


def _tenant_context():
    """Resolve the authenticated tenant; never trust a client-supplied id."""
    user = get_current_user()
    requested_tenant = request.headers.get("X-Tenant-ID", "").strip()
    authenticated_tenant = str(getattr(user, "tenant_id", "") or "").strip()

    if authenticated_tenant and requested_tenant and requested_tenant != authenticated_tenant:
        return None, (jsonify({"error": "tenant_access_denied", "message": "Tenant access denied."}), 403)

    tenant_id = authenticated_tenant or requested_tenant
    if not tenant_id:
        return None, (jsonify({"error": "tenant_context_required", "message": "Tenant context is required."}), 400)
    if not TENANT_ID_PATTERN.fullmatch(tenant_id):
        return None, (jsonify({"error": "invalid_tenant_id", "message": "Tenant ID is invalid."}), 400)
    return tenant_id, None


def _error(exc: LedgerError):
    """Map ledger domain errors to stable HTTP responses (Rule #18: no internals)."""
    if isinstance(exc, LedgerNotFoundError):
        return jsonify({"error": "not_found", "message": "Ledger resource not found."}), 404
    if isinstance(exc, LedgerConflictError):
        return jsonify({"error": "conflict", "message": "Ledger conflict or stale version."}), 409
    if isinstance(exc, LedgerStateError):
        return jsonify({"error": "invalid_state", "message": "Invalid ledger state transition."}), 409
    if isinstance(exc, (LedgerUnbalancedError, LedgerError)):
        return jsonify({"error": "invalid_request", "message": str(exc)}), 400
    return jsonify({"error": "ledger_error", "message": "Ledger operation failed."}), 400


def create_ledger_blueprint(
    session_factory: Callable[[], Session],
    write_session_factory: Callable[[], Session] | None = None,
) -> Blueprint:
    """Create ledger endpoints using the supplied DB session factories."""
    blueprint = Blueprint("ledger_api", __name__, url_prefix="/api/v1")
    write_session_factory = write_session_factory or session_factory

    @blueprint.post("/ledgers")
    @require_auth(required_permission="admin:ledgers")
    def create_ledger():
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
        slug = str(payload.get("slug") or "").strip()
        name = str(payload.get("name") or "").strip()
        if not slug or not name or len(slug) > 80 or len(name) > 120:
            return jsonify({"error": "invalid_request", "message": "slug and name are required."}), 400
        if set(payload) - {"slug", "name", "description"}:
            return jsonify({"error": "invalid_request", "message": "Unsupported fields present."}), 400
        session = write_session_factory()
        try:
            ledger = Ledger(
                tenant_id=tenant_id,
                slug=slug,
                name=name,
                description=payload.get("description"),
            )
            session.add(ledger)
            session.commit()
            return jsonify({"id": ledger.id, "tenant_id": tenant_id, "slug": slug, "name": name}), 201
        except Exception:
            session.rollback()
            return jsonify({"error": "ledger_conflict", "message": "Ledger slug already exists."}), 409
        finally:
            session.close()

    @blueprint.post("/ledgers/<ledger_id>/transactions")
    @require_auth(required_permission="write:ledgers")
    def post_ledger_transaction(ledger_id: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
        external_id = str(payload.get("external_id") or "").strip()
        raw_entries = payload.get("entries")
        if not external_id or len(external_id) > 180:
            return jsonify({"error": "invalid_request", "message": "external_id is required (max 180)."}), 400
        if not isinstance(raw_entries, list) or len(raw_entries) < 2:
            return jsonify({"error": "invalid_request", "message": "At least two entries are required."}), 400
        if set(payload) - {"external_id", "entries", "description", "metadata"}:
            return jsonify({"error": "invalid_request", "message": "Unsupported fields present."}), 400

        entries = []
        for raw in raw_entries:
            if not isinstance(raw, dict):
                return jsonify({"error": "invalid_request", "message": "Each entry must be an object."}), 400
            code = str(raw.get("account_code") or "").strip()
            direction = str(raw.get("direction") or "").strip().lower()
            currency = str(raw.get("currency") or "KES").strip().upper()
            if not code or direction not in {"debit", "credit"}:
                return jsonify({"error": "invalid_request", "message": "Each entry needs account_code and direction."}), 400
            try:
                amount_minor = to_minor_units(raw.get("amount"), currency)
            except LedgerError as exc:
                return jsonify({"error": "invalid_amount", "message": str(exc)}), 400
            entries.append(LedgerEntryInput(
                account_code=code, direction=direction,
                amount_minor=amount_minor, currency=currency, memo=raw.get("memo"),
            ))

        metadata = payload.get("metadata")
        if metadata is not None and not isinstance(metadata, dict):
            return jsonify({"error": "invalid_request", "message": "metadata must be an object."}), 400

        session = write_session_factory()
        try:
            txn = post_transaction(
                session,
                tenant_id=tenant_id,
                ledger_id=ledger_id,
                external_id=external_id,
                entries=entries,
                description=payload.get("description"),
                metadata=metadata,
            )
            session.commit()
            return jsonify({
                "id": txn.id,
                "ledger_id": ledger_id,
                "external_id": external_id,
                "status": txn.status,
                "posted_at": txn.posted_at.isoformat() if txn.posted_at else None,
            }), 201
        except LedgerError as exc:
            session.rollback()
            return _error(exc)
        finally:
            session.close()

    @blueprint.post("/ledgers/<ledger_id>/accounts")
    @require_auth(required_permission="admin:ledgers")
    def create_account(ledger_id: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
        code = str(payload.get("code") or "").strip()
        name = str(payload.get("name") or "").strip()
        currency = str(payload.get("currency") or "KES").strip().upper()
        normal_balance = str(payload.get("normal_balance") or "debit").strip().lower()
        if not code or not name or len(code) > 80 or len(name) > 120:
            return jsonify({"error": "invalid_request", "message": "code and name are required."}), 400
        if normal_balance not in {"debit", "credit"}:
            return jsonify({"error": "invalid_request", "message": "normal_balance must be debit or credit."}), 400
        if set(payload) - {"code", "name", "currency", "normal_balance"}:
            return jsonify({"error": "invalid_request", "message": "Unsupported fields present."}), 400
        session = write_session_factory()
        try:
            ledger = session.query(Ledger).filter_by(tenant_id=tenant_id, id=ledger_id).one_or_none()
            if ledger is None:
                return jsonify({"error": "not_found", "message": "Ledger not found."}), 404
            account = LedgerAccount(
                tenant_id=tenant_id,
                ledger_id=ledger_id,
                code=code,
                name=name,
                currency=currency,
                normal_balance=normal_balance,
            )
            session.add(account)
            session.commit()
            return jsonify({
                "id": account.id,
                "ledger_id": ledger_id,
                "code": code,
                "currency": currency,
                "normal_balance": normal_balance,
            }), 201
        except Exception:
            session.rollback()
            return jsonify({"error": "account_conflict", "message": "Account code already exists."}), 409
        finally:
            session.close()

    @blueprint.get("/ledger-transactions/<transaction_id>")
    @require_auth(required_permission="read:ledgers")
    def read_transaction(transaction_id: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        session = session_factory()
        try:
            return jsonify(get_transaction(session, tenant_id=tenant_id, transaction_id=transaction_id)), 200
        except LedgerError as exc:
            return _error(exc)
        finally:
            session.close()

    @blueprint.get("/ledgers/<ledger_id>/accounts/<account_code>/balance")
    @require_auth(required_permission="read:ledgers")
    def read_balance(ledger_id: str, account_code: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        session = session_factory()
        try:
            return jsonify(account_balance(
                session, tenant_id=tenant_id, ledger_id=ledger_id, account_code=account_code,
            )), 200
        except LedgerError as exc:
            return _error(exc)
        finally:
            session.close()

    @blueprint.post("/ledger-transactions/<transaction_id>/void")
    @require_auth(required_permission="write:ledgers")
    def void_transaction(transaction_id: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        session = write_session_factory()
        try:
            txn = void_pending_transaction(session, tenant_id=tenant_id, transaction_id=transaction_id)
            session.commit()
            return jsonify({"id": txn.id, "status": txn.status}), 200
        except LedgerError as exc:
            session.rollback()
            return _error(exc)
        finally:
            session.close()

    return blueprint

    @require_auth(required_permission="admin:ledgers")
    def create_account(ledger_id: str):
        tenant_id, error = _tenant_context()
        if error is not None:
            return error
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify({"error": "invalid_request", "message": "A JSON object is required."}), 400
        code = str(payload.get("code") or "").strip()
        name = str(payload.get("name") or "").strip()
        currency = str(payload.get("currency") or "KES").strip().upper()
        normal_balance = str(payload.get("normal_balance") or "debit").strip().lower()
        if not code or not name or len(code) > 80 or len(name) > 120:
            return jsonify({"error": "invalid_request", "message": "code and name are required."}), 400
        if normal_balance not in {"debit", "credit"}:
            return jsonify({"error": "invalid_request", "message": "normal_balance must be debit or credit."}), 400
        if set(payload) - {"code", "name", "currency", "normal_balance"}:
            return jsonify({"error": "invalid_request", "message": "Unsupported fields present."}), 400
        session = write_session_factory()
        try:
            ledger = session.query(Ledger).filter_by(tenant_id=tenant_id, id=ledger_id).one_or_none()
            if ledger is None:
                return jsonify({"error": "not_found", "message": "Ledger not found."}), 404
            account = LedgerAccount(
                tenant_id=tenant_id,
                ledger_id=ledger_id,
                code=code,
                name=name,
                currency=currency,
                normal_balance=normal_balance,
            )
            session.add(account)
            session.commit()
            return jsonify({
                "id": account.id,
                "ledger_id": ledger_id,
                "code": code,
                "currency": currency,
                "normal_balance": normal_balance,
            }), 201
        except Exception:
            session.rollback()
            return jsonify({"error": "account_conflict", "message": "Account code already exists."}), 409
        finally:
            session.close()

