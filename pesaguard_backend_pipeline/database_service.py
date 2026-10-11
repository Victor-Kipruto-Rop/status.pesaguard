"""Shared SQLAlchemy engine, session, and pool configuration."""

from __future__ import annotations

import logging
import os
from contextlib import contextmanager
from typing import Any, Generator

from sqlalchemy import Engine, create_engine, make_url
from sqlalchemy.exc import ArgumentError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import NullPool, StaticPool

logger = logging.getLogger("pesaguard.database")


def _integer_setting(name: str, default: int, *, minimum: int = 1) -> int:
    raw = os.getenv(name)
    try:
        value = default if raw is None else int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


class DatabaseService:
    """Owns one SQLAlchemy engine and its session factory."""

    def __init__(
        self,
        database_url: str,
        *,
        session_options: dict[str, Any] | None = None,
        **engine_options: Any,
    ) -> None:
        if not isinstance(database_url, str) or not database_url.strip():
            raise ValueError("database_url is required")
        self.engine = create_database_engine(database_url, **engine_options)
        factory_options = {"expire_on_commit": False, **(session_options or {})}
        self.session_factory = sessionmaker(bind=self.engine, **factory_options)

    @contextmanager
    def session(self) -> Generator[Session, None, None]:
        """Yield a session and always close it; transaction boundaries stay explicit."""
        session = self.session_factory()
        try:
            yield session
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    @contextmanager
    def transaction(self) -> Generator[Session, None, None]:
        """Run one explicit transaction, rolling back on failure."""
        with self.session() as session:
            with session.begin():
                yield session

    def pool_snapshot(self) -> dict[str, int | str]:
        pool = self.engine.pool
        snapshot: dict[str, int | str] = {
            "pool_class": type(pool).__name__,
            "status": pool.status(),
        }
        for name in ("size", "checkedin", "checkedout", "overflow"):
            method = getattr(pool, name, None)
            if callable(method):
                try:
                    snapshot[name] = int(method())
                except (NotImplementedError, TypeError):
                    continue
        return snapshot

    def close(self) -> None:
        self.engine.dispose()


def create_database_engine(database_url: str, **overrides: Any) -> Engine:
    """Create a dialect-aware engine with validated pool bounds and query metrics."""
    try:
        url = make_url(database_url)
    except (ArgumentError, TypeError, ValueError) as exc:
        raise ValueError("database_url must be a valid SQLAlchemy URL") from exc

    options: dict[str, Any] = {"pool_pre_ping": True}
    caller_connect_args = overrides.pop("connect_args", {})
    if not isinstance(caller_connect_args, dict):
        raise ValueError("connect_args must be a dictionary")

    if url.get_backend_name() == "sqlite":
        connect_args = {"check_same_thread": False, **caller_connect_args}
        if url.database in (None, "", ":memory:"):
            options["poolclass"] = StaticPool
        else:
            options["poolclass"] = NullPool
    else:
        connect_args = dict(caller_connect_args)
        if url.get_backend_name() == "postgresql":
            connect_args.setdefault("connect_timeout", _integer_setting("DB_CONNECT_TIMEOUT", 5))
            options.update({
                "pool_size": _integer_setting("DB_POOL_SIZE", 10),
                "max_overflow": _integer_setting("DB_MAX_OVERFLOW", 20, minimum=0),
                "pool_timeout": _integer_setting("DB_POOL_TIMEOUT", 30),
                "pool_recycle": _integer_setting("DB_POOL_RECYCLE", 1800),
            })

    for name, minimum in (("pool_size", 1), ("max_overflow", 0), ("pool_timeout", 1), ("pool_recycle", 1)):
        if name in overrides:
            value = overrides[name]
            if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
                raise ValueError(f"{name} must be an integer of at least {minimum}")
            if name in {"pool_size", "max_overflow"} and value > 100:
                raise ValueError(f"{name} must not exceed 100")
    if "pool_size" in options and options["pool_size"] > 100:
        raise ValueError("pool_size must not exceed 100")
    if "max_overflow" in options and options["max_overflow"] > 100:
        raise ValueError("max_overflow must not exceed 100")
    options["connect_args"] = connect_args
    options.update(overrides)
    engine = create_engine(url, **options)
    try:
        from metrics import instrument_engine_query_timing

        if not instrument_engine_query_timing(engine):
            logger.warning("Database query timing instrumentation is unavailable.")
    except ImportError:
        logger.warning("Database query timing instrumentation module is unavailable.", exc_info=True)
    return engine
