"""Trusted tenant context propagation to PostgreSQL transaction-local state."""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar, Token
from functools import wraps
import inspect
import re
import sys
from typing import Any, Callable, Iterator, TypeVar

from sqlalchemy import Engine, event, text


_TENANT_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_current_tenant: ContextVar[str | None] = ContextVar("pesaguard_tenant_id", default=None)
_F = TypeVar("_F", bound=Callable[..., Any])


def current_tenant_id() -> str | None:
    """Return the trusted tenant scope for this execution context, if any."""
    return _current_tenant.get()


def is_valid_tenant_id(tenant_id: object) -> bool:
    """Return whether a tenant identifier is valid after normalizing whitespace."""
    return isinstance(tenant_id, str) and bool(_TENANT_ID.fullmatch(tenant_id.strip()))


@contextmanager
def tenant_scope(tenant_id: str | None) -> Iterator[None]:
    """Set a validated tenant scope for the current request/job execution only.

    A missing tenant explicitly represents deny-all database context; it never
    falls back to a default tenant.
    """
    normalized = tenant_id.strip() if isinstance(tenant_id, str) else ""
    if normalized and not is_valid_tenant_id(normalized):
        raise ValueError("tenant_id has an invalid format")
    token: Token[str | None] = _current_tenant.set(normalized or None)
    try:
        yield
    finally:
        _current_tenant.reset(token)


def tenant_scoped_argument(argument_name: str = "tenant_id") -> Callable[[_F], _F]:
    """Scope a service/job call using its explicit tenant argument."""
    def decorate(function: _F) -> _F:
        signature = inspect.signature(function)

        @wraps(function)
        def wrapped(*args: Any, **kwargs: Any) -> Any:
            bound = signature.bind_partial(*args, **kwargs)
            tenant_id = bound.arguments.get(argument_name)
            with tenant_scope(tenant_id):
                return function(*args, **kwargs)

        return wrapped  # type: ignore[return-value]

    return decorate


@event.listens_for(Engine, "begin")
def _set_transaction_local_tenant(connection) -> None:
    """Clear or set the tenant GUC in every PostgreSQL transaction.

    PostgreSQL's third ``set_config`` argument makes the value transaction-local,
    so it is discarded on commit/rollback before a pooled connection is reused.
    Empty context is deliberately deny-all under the RLS policies.
    """
    if connection.dialect.name != "postgresql":
        return
    tenant_id = current_tenant_id() or ""
    connection.execute(
        text("SELECT set_config('app.tenant_id', :tenant_id, true)"),
        {"tenant_id": tenant_id},
    )


# Ensure legacy top-level imports and package imports share this exact ContextVar
# and SQLAlchemy listener rather than maintaining divergent tenant scopes.
sys.modules.setdefault("pesaguard_backend_pipeline.tenant_context", sys.modules[__name__])
