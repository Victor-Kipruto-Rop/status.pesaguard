"""Distributed lock service for cross-worker mutual exclusion.

Guarantees a critical section runs on exactly one worker at a time, even across
many processes/containers sharing a Redis instance. Safety properties:

- **Ownership token (fencing token):** each acquisition returns a unique token;
  only the holder that owns the token can release or extend the lock. This
  prevents one worker releasing a lock another worker acquired after a slow
  holder's TTL expired.
- **Atomic acquire:** uses Redis ``SET key token NX PX ttl`` so acquire is a
  single atomic operation.
- **Atomic release:** a Lua script deletes the key only if the stored token
  matches, avoiding the check-then-delete race.
- **Extend/refresh:** renew only if the token still matches.
- **Graceful degradation:** when Redis is unavailable the service falls back to
  a thread-safe in-process lock so single-instance deployments still work. In
  production, misconfiguration (no Redis) fails closed and raises rather than
  silently pretending the lock is distributed.
"""

from __future__ import annotations

import logging
import math
import os
import threading
import time
import uuid
from contextlib import contextmanager
from typing import Any, Dict, Optional

logger = logging.getLogger("pesaguard.distributed_lock")


class LockNotAcquiredError(Exception):
    """Raised by non-blocking acquire when the lock is already held."""


# Lua: release only when the token matches. Returns 1 if released, 0 if the
# token did not own the key (already expired and possibly re-acquired).
_RELEASE_LUA = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
"""

# Lua: extend TTL only when the token matches.
_EXTEND_LUA = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('PEXPIRE', KEYS[1], ARGV[2])
else
    return 0
end
"""


class _InProcessLockBackend:
    """Thread-safe fallback used when Redis is not configured/available.

    This only provides mutual exclusion within a single process. It is correct
    for development and single-worker deployments; production multi-worker
    deployments must configure Redis (the service fails closed there).
    """

    def __init__(self) -> None:
        self._mutex = threading.Lock()
        self._held: Dict[str, tuple] = {}

    def acquire(self, key: str, token: str, ttl_seconds: float) -> bool:
        with self._mutex:
            now = time.monotonic()
            existing = self._held.get(key)
            if existing is not None and existing[1] > now:
                return False
            self._held[key] = (token, now + ttl_seconds)
            return True

    def release(self, key: str, token: str) -> bool:
        with self._mutex:
            existing = self._held.get(key)
            if existing is None or existing[0] != token:
                return False
            self._held.pop(key, None)
            return True

    def extend(self, key: str, token: str, ttl_seconds: float) -> bool:
        with self._mutex:
            existing = self._held.get(key)
            if existing is None or existing[0] != token:
                return False
            self._held[key] = (token, time.monotonic() + ttl_seconds)
            return True


class DistributedLockService:
    """Redis-backed distributed lock with a safe in-process fallback."""

    def __init__(
        self,
        redis_client: Optional[Any] = None,
        *,
        redis_url: Optional[str] = None,
        key_prefix: Optional[str] = None,
        socket_timeout: float = 2.0,
    ) -> None:
        self._redis = redis_client
        self._owns_client = False
        self._prefix = key_prefix or os.getenv("PESAGUARD_LOCK_PREFIX", "pesaguard:lock")
        self._release_script = None
        self._extend_script = None
        self._fallback = _InProcessLockBackend()

        # Test / development environments must keep these services hermetic: a
        # live Redis auto-discovered from REDIS_URL is ignored so unit tests run
        # against the in-process fallback and cannot leak durable state between
        # test runs. An explicitly injected client is always honored — it is a
        # deliberate, isolated test double rather than ambient environment
        # configuration — so its Lua scripts are registered and exercised.
        environment = os.getenv("PESAGUARD_ENVIRONMENT", "development").lower()
        if self._redis is None and environment in {"development", "test", "testing"}:
            return

        if self._redis is None:
            url = (redis_url if redis_url is not None else os.getenv("REDIS_URL", "")).strip()
            if url:
                try:
                    import redis

                    self._redis = redis.from_url(url, socket_connect_timeout=socket_timeout, socket_timeout=socket_timeout)
                    self._redis.ping()
                    self._owns_client = True
                except Exception as exc:  # pragma: no cover - depends on live Redis
                    if environment in {"production", "prod"}:
                        raise RuntimeError("Redis is required for distributed locking in production") from exc
                    logger.warning("Distributed lock falling back to in-process backend: %s", exc)
                    self._redis = None
            elif environment in {"production", "prod"}:
                raise RuntimeError("REDIS_URL must be configured for distributed locking in production")

        if self._redis is not None:
            self._register_scripts()

    def _register_scripts(self) -> None:
        try:
            self._release_script = self._redis.register_script(_RELEASE_LUA)
            self._extend_script = self._redis.register_script(_EXTEND_LUA)
        except Exception:  # pragma: no cover - fake clients may not support scripts
            self._release_script = None
            self._extend_script = None

    @property
    def is_distributed(self) -> bool:
        """True when backed by Redis (real cross-process mutual exclusion)."""
        return self._redis is not None

    def _key(self, name: str) -> str:
        if not isinstance(name, str) or not name.strip():
            raise ValueError("lock name is required")
        return f"{self._prefix}:{name}"

    @staticmethod
    def _ttl_milliseconds(ttl_seconds: float) -> int:
        if isinstance(ttl_seconds, bool) or not isinstance(ttl_seconds, (int, float)):
            raise ValueError("ttl_seconds must be a positive finite number")
        if not math.isfinite(ttl_seconds) or ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be a positive finite number")
        return max(1, int(ttl_seconds * 1000))

    def acquire(self, name: str, ttl_seconds: float = 30.0) -> Optional[str]:
        """Try to acquire the lock. Returns an ownership token or None if held."""
        ttl_ms = self._ttl_milliseconds(ttl_seconds)
        token = uuid.uuid4().hex
        if self._redis is not None:
            acquired = self._redis.set(self._key(name), token, nx=True, px=ttl_ms)
            return token if acquired else None
        return token if self._fallback.acquire(self._key(name), token, ttl_seconds) else None

    def acquire_blocking(
        self, name: str, ttl_seconds: float = 30.0, *, wait_seconds: float = 10.0, poll_interval: float = 0.05
    ) -> Optional[str]:
        """Acquire the lock, retrying until ``wait_seconds`` elapses."""
        if isinstance(wait_seconds, bool) or not isinstance(wait_seconds, (int, float)):
            raise ValueError("wait_seconds must be a non-negative finite number")
        if isinstance(poll_interval, bool) or not isinstance(poll_interval, (int, float)):
            raise ValueError("poll_interval must be a positive finite number")
        if not math.isfinite(wait_seconds) or wait_seconds < 0:
            raise ValueError("wait_seconds must be a non-negative finite number")
        if not math.isfinite(poll_interval) or poll_interval <= 0:
            raise ValueError("poll_interval must be a positive finite number")
        deadline = time.monotonic() + wait_seconds
        while True:
            token = self.acquire(name, ttl_seconds)
            if token is not None:
                return token
            if time.monotonic() >= deadline:
                return None
            time.sleep(poll_interval)


    def release(self, name: str, token: str) -> bool:
        """Release a lock only if ``token`` still owns it. Never raises on mismatch."""
        key = self._key(name)
        if not isinstance(token, str) or not token:
            return False
        if self._redis is not None:
            if self._release_script is not None:
                return bool(self._release_script(keys=[key], args=[token]))
            current = self._redis.get(key)
            if current is not None:
                current = current.decode() if isinstance(current, bytes) else current
                if current == token:
                    self._redis.delete(key)
                    return True
            return False
        return self._fallback.release(key, token)

    def extend(self, name: str, token: str, ttl_seconds: float = 30.0) -> bool:
        """Extend a held lock's TTL only if ``token`` still owns it."""
        key = self._key(name)
        ttl_ms = self._ttl_milliseconds(ttl_seconds)
        if not isinstance(token, str) or not token:
            return False
        if self._redis is not None:
            if self._extend_script is not None:
                return bool(self._extend_script(keys=[key], args=[token, ttl_ms]))
            current = self._redis.get(key)
            if current is not None:
                current = current.decode() if isinstance(current, bytes) else current
                if current == token:
                    self._redis.pexpire(key, ttl_ms)
                    return True
            return False
        return self._fallback.extend(key, token, ttl_seconds)

    def is_locked(self, name: str) -> bool:
        key = self._key(name)
        if self._redis is not None:
            return self._redis.exists(key) == 1
        with self._fallback._mutex:
            existing = self._fallback._held.get(key)
            return existing is not None and existing[1] > time.monotonic()

    @contextmanager
    def hold(self, name: str, ttl_seconds: float = 30.0, *, wait_seconds: float = 10.0):
        """Context manager acquiring the lock and always releasing it on exit."""
        token = self.acquire_blocking(name, ttl_seconds, wait_seconds=wait_seconds)
        if token is None:
            raise LockNotAcquiredError(f"Could not acquire lock '{name}' within {wait_seconds}s")
        try:
            yield token
        finally:
            self.release(name, token)

    def close(self) -> None:
        if self._owns_client and self._redis is not None:
            try:
                self._redis.close()
            except Exception:  # pragma: no cover
                logger.debug("Error closing distributed-lock redis client", exc_info=True)


_default_service: Optional[DistributedLockService] = None
_default_lock = threading.Lock()


def get_distributed_lock() -> DistributedLockService:
    """Process-wide lazily-created DistributedLockService singleton."""
    global _default_service
    if _default_service is None:
        with _default_lock:
            if _default_service is None:
                _default_service = DistributedLockService()
    return _default_service
