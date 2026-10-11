"""Tenant-isolated advisory cache with Redis and bounded process-local fallback.

Cache contents are never an authority for financial or authorization decisions.
Only JSON values are accepted; arbitrary Python object deserialization is avoided.
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import threading
import time
from collections import OrderedDict
from typing import Any

logger = logging.getLogger("pesaguard.cache")


class CacheService:
    def __init__(
        self,
        redis_client: Any | None = None,
        *,
        key_prefix: str = "pesaguard:cache:v1",
        max_entries: int = 10_000,
    ) -> None:
        if not isinstance(max_entries, int) or isinstance(max_entries, bool) or max_entries < 1:
            raise ValueError("max_entries must be a positive integer")
        if not isinstance(key_prefix, str) or not key_prefix.strip():
            raise ValueError("key_prefix is required")
        self._redis = redis_client
        self._prefix = key_prefix.strip()
        self._max_entries = max_entries
        self._memory: OrderedDict[str, tuple[float, str]] = OrderedDict()
        self._lock = threading.RLock()
        self._stats = {"hits": 0, "misses": 0, "fallbacks": 0, "errors": 0}

    @property
    def backend(self) -> str:
        return "redis" if self._redis is not None else "memory"

    def _key(self, tenant_id: str, key: str) -> str:
        if not isinstance(tenant_id, str) or not tenant_id.strip():
            raise ValueError("tenant_id is required")
        if not isinstance(key, str) or not key.strip():
            raise ValueError("cache key is required")
        digest = hashlib.sha256(f"{tenant_id.strip()}\0{key.strip()}".encode("utf-8")).hexdigest()
        return f"{self._prefix}:{digest}"

    @staticmethod
    def _ttl(ttl_seconds: int | float) -> int:
        if isinstance(ttl_seconds, bool) or not isinstance(ttl_seconds, (int, float)):
            raise ValueError("ttl_seconds must be a positive finite number")
        if not math.isfinite(ttl_seconds) or ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be a positive finite number")
        return max(1, math.ceil(ttl_seconds))

    def get(self, *, tenant_id: str, key: str) -> Any | None:
        cache_key = self._key(tenant_id, key)
        if self._redis is not None:
            try:
                raw = self._redis.get(cache_key)
            except Exception:
                self._record_fallback()
                logger.warning("Redis cache read failed; using process-local fallback.", exc_info=True)
                return self._get_memory(cache_key)
            if raw is None:
                self._increment("misses")
                return None
            try:
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8")
                value = json.loads(raw)
            except (UnicodeDecodeError, TypeError, json.JSONDecodeError):
                self._increment("errors")
                logger.warning("Invalid JSON value found in cache; treating it as a miss.")
                try:
                    self._redis.delete(cache_key)
                except Exception:
                    logger.debug("Unable to remove malformed Redis cache entry.", exc_info=True)
                return None
            self._increment("hits")
            return value
        return self._get_memory(cache_key)

    def set(self, *, tenant_id: str, key: str, value: Any, ttl_seconds: int | float) -> None:
        cache_key = self._key(tenant_id, key)
        ttl = self._ttl(ttl_seconds)
        try:
            encoded = json.dumps(value, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError) as exc:
            raise ValueError("cache value must be JSON serializable") from exc
        self._set_memory(cache_key, encoded, ttl)
        if self._redis is not None:
            try:
                self._redis.set(cache_key, encoded, ex=ttl)
            except Exception:
                self._record_fallback()
                logger.warning("Redis cache write failed; retained value in process-local fallback.", exc_info=True)

    def delete(self, *, tenant_id: str, key: str) -> bool:
        cache_key = self._key(tenant_id, key)
        with self._lock:
            removed = self._memory.pop(cache_key, None) is not None
        if self._redis is not None:
            try:
                removed = bool(self._redis.delete(cache_key)) or removed
            except Exception:
                self._record_fallback()
                logger.warning("Redis cache deletion failed; removed process-local entry only.", exc_info=True)
        return removed

    def clear_memory(self) -> None:
        """Clear only this process's fallback entries; remote cache is untouched."""
        with self._lock:
            self._memory.clear()

    def snapshot(self) -> dict[str, int | str]:
        with self._lock:
            return {"backend": self.backend, **self._stats, "memory_entries": len(self._memory)}

    def _get_memory(self, cache_key: str) -> Any | None:
        now = time.monotonic()
        with self._lock:
            cached = self._memory.get(cache_key)
            if cached is None:
                self._stats["misses"] += 1
                return None
            expires_at, encoded = cached
            if expires_at <= now:
                self._memory.pop(cache_key, None)
                self._stats["misses"] += 1
                return None
            self._memory.move_to_end(cache_key)
            try:
                value = json.loads(encoded)
            except json.JSONDecodeError:
                self._memory.pop(cache_key, None)
                self._stats["errors"] += 1
                return None
            self._stats["hits"] += 1
            return value

    def _set_memory(self, cache_key: str, encoded: str, ttl: int) -> None:
        expires_at = time.monotonic() + ttl
        with self._lock:
            self._memory[cache_key] = (expires_at, encoded)
            self._memory.move_to_end(cache_key)
            self._evict_expired(time.monotonic())
            while len(self._memory) > self._max_entries:
                self._memory.popitem(last=False)

    def _evict_expired(self, now: float) -> None:
        expired = [key for key, (expires_at, _) in self._memory.items() if expires_at <= now]
        for key in expired:
            self._memory.pop(key, None)

    def _increment(self, name: str) -> None:
        with self._lock:
            self._stats[name] += 1

    def _record_fallback(self) -> None:
        with self._lock:
            self._stats["fallbacks"] += 1
            self._stats["errors"] += 1
