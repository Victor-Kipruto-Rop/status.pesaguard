"""Service discovery registry for PesaGuard worker/API instances.

Each instance registers itself with a metadata record and a heartbeat; a Redis
key with a TTL represents liveness, so instances that crash or are partitioned
are automatically pruned when their heartbeat expires. Each instance has its
own lease, allowing multiple workers for one service. When Redis is absent the
registry degrades to an in-process store (single-process deployments only);
production multi-process deployments must configure Redis.

Health semantics: an instance is "healthy" only if its registry entry is still
present (TTL not expired). Stale instances are excluded from ``discover`` so
callers never select an instance that has stopped heartbeating.
"""

from __future__ import annotations

import json
import hashlib
import logging
import math
import os
import threading
import time
import uuid
from typing import Any, Dict, List, Optional

logger = logging.getLogger("pesaguard.service_discovery")

DEFAULT_TTL_SECONDS = 30.0
_TTL_GRACE_SECONDS = 0.005


_HEARTBEAT_LUA = """
local value = redis.call('GET', KEYS[1])
if not value then return 0 end
local ok, data = pcall(cjson.decode, value)
if not ok or data['instance_id'] ~= ARGV[1] then return 0 end
data['last_heartbeat'] = tonumber(ARGV[2])
redis.call('SET', KEYS[1], cjson.encode(data), 'PX', ARGV[3])
return 1
"""

_REGISTER_LUA = """
local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
if not result then return 0 end
redis.call('SADD', KEYS[2], KEYS[1])
return 1
"""

_DEREGISTER_LUA = """
local value = redis.call('GET', KEYS[1])
if not value then return 0 end
local ok, data = pcall(cjson.decode, value)
if not ok or data['instance_id'] ~= ARGV[1] then return 0 end
redis.call('DEL', KEYS[1])
redis.call('SREM', KEYS[2], KEYS[1])
if redis.call('SCARD', KEYS[2]) == 0 then redis.call('DEL', KEYS[2]) end
return 1
"""

_PRUNE_EXPIRED_LUA = """
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SREM', KEYS[2], KEYS[1])
    if redis.call('SCARD', KEYS[2]) == 0 then redis.call('DEL', KEYS[2]) end
    return 1
end
return 0
"""


def _validate_ttl(value: float) -> tuple[float, int]:
    ttl = float(value)
    if not math.isfinite(ttl) or ttl <= 0:
        raise ValueError("ttl must be positive finite seconds.")
    ttl_ms = max(1, math.ceil(ttl * 1000))
    return ttl, ttl_ms


def _validate_identifier(value: str, label: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 255:
        raise ValueError(f"{label} must be a non-empty string of at most 255 characters.")
    return value.strip()


def _key_component(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class _InMemoryRegistry:
    """Process-local registry used when Redis is not configured."""

    def __init__(self) -> None:
        self._mutex = threading.Lock()
        self._instances: Dict[tuple[str, str], tuple] = {}

    def _expire(self) -> None:
        now = time.monotonic()
        for key in [k for k, v in self._instances.items() if v[1] + _TTL_GRACE_SECONDS <= now]:
            self._instances.pop(key, None)

    def register(self, name: str, instance_id: str, metadata: Dict[str, Any], ttl: float) -> None:
        with self._mutex:
            self._expire()
            key = (name, instance_id)
            if key in self._instances:
                raise ValueError("instance_id is already registered for this service.")
            self._instances[key] = (metadata, time.monotonic() + ttl)

    def heartbeat(self, name: str, instance_id: str, ttl: float) -> bool:
        with self._mutex:
            self._expire()
            key = (name, instance_id)
            existing = self._instances.get(key)
            if existing is None:
                return False
            self._instances[key] = (existing[0], time.monotonic() + ttl)
            return True

    def deregister(self, name: str, instance_id: str) -> bool:
        with self._mutex:
            self._expire()
            key = (name, instance_id)
            if key not in self._instances:
                return False
            self._instances.pop(key, None)
            return True

    def get_all(self, name: str) -> List[Dict[str, Any]]:
        with self._mutex:
            self._expire()
            return [
                {"name": service_name, "instance_id": instance_id, "metadata": metadata}
                for (service_name, instance_id), (metadata, _) in sorted(self._instances.items())
                if service_name == name
            ]



class ServiceDiscovery:
    """Registry of service instances backed by Redis with an in-memory fallback."""

    def __init__(
        self,
        redis_client: Optional[Any] = None,
        *,
        redis_url: Optional[str] = None,
        key_prefix: Optional[str] = None,
        default_ttl: float = DEFAULT_TTL_SECONDS,
        socket_timeout: float = 2.0,
    ) -> None:
        self._redis = redis_client
        self._owns_client = False
        self._prefix = key_prefix or os.getenv("PESAGUARD_DISCOVERY_PREFIX", "pesaguard:svc")
        if not isinstance(self._prefix, str) or not self._prefix.strip() or any(char in self._prefix for char in "{}"):
            raise ValueError("service-discovery key prefix must be non-empty and cannot contain Redis hash-tag braces.")
        self._default_ttl, _ = _validate_ttl(default_ttl)
        self._registry = _InMemoryRegistry()
        self._heartbeat_script = None
        self._register_script = None
        self._deregister_script = None
        self._prune_expired_script = None

        # Test / development environments must keep these services hermetic: a
        # live Redis auto-discovered from REDIS_URL is ignored so unit tests run
        # against the in-memory registry and cannot leak durable state between
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
                        raise RuntimeError("Redis is required for service discovery in production") from exc
                    logger.warning("Service discovery falling back to in-memory registry: %s", exc)
                    self._redis = None
            elif environment in {"production", "prod"}:
                raise RuntimeError("REDIS_URL must be configured for service discovery in production")

        if self._redis is not None:
            self._register_script = self._redis.register_script(_REGISTER_LUA)
            self._heartbeat_script = self._redis.register_script(_HEARTBEAT_LUA)
            self._deregister_script = self._redis.register_script(_DEREGISTER_LUA)
            self._prune_expired_script = self._redis.register_script(_PRUNE_EXPIRED_LUA)

    @property
    def is_distributed(self) -> bool:
        return self._redis is not None

    def _key(self, name: str, instance_id: str) -> str:
        return f"{self._prefix}:{{{_key_component(name)}}}:instances:{_key_component(instance_id)}"

    def _index_key(self, name: str) -> str:
        return f"{self._prefix}:{{{_key_component(name)}}}:index"

    def register(self, name: str, instance_id: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None, ttl: Optional[float] = None) -> str:
        """Register an instance of ``name``. Returns the instance_id."""
        name = _validate_identifier(name, "name")
        if isinstance(instance_id, dict) and metadata is None:
            metadata = instance_id
            instance_id = None
        instance_id = _validate_identifier(instance_id if instance_id is not None else uuid.uuid4().hex, "instance_id")
        ttl, ttl_ms = _validate_ttl(self._default_ttl if ttl is None else ttl)
        if metadata is not None and not isinstance(metadata, dict):
            raise ValueError("metadata must be a dictionary.")
        metadata = dict(metadata or {})
        if self._redis is not None:
            payload = json.dumps({"instance_id": instance_id, "metadata": metadata, "registered_at": time.time()})
            registered = self._register_script(
                keys=[self._key(name, instance_id), self._index_key(name)],
                args=[payload, ttl_ms],
            )
            if not registered:
                raise ValueError("instance_id is already registered for this service.")
        else:
            self._registry.register(name, instance_id, metadata, ttl)
        return instance_id

    def heartbeat(self, name: str, instance_id: str, ttl: Optional[float] = None) -> bool:
        """Refresh an instance's TTL. Returns False if it is no longer registered."""
        name = _validate_identifier(name, "name")
        instance_id = _validate_identifier(instance_id, "instance_id")
        ttl, ttl_ms = _validate_ttl(self._default_ttl if ttl is None else ttl)
        if self._redis is not None:
            return bool(self._heartbeat_script(
                keys=[self._key(name, instance_id)],
                args=[instance_id, time.time(), ttl_ms],
            ))
        return self._registry.heartbeat(name, instance_id, ttl)

    def deregister(self, name: str, instance_id: str) -> bool:
        name = _validate_identifier(name, "name")
        instance_id = _validate_identifier(instance_id, "instance_id")
        if self._redis is not None:
            return bool(self._deregister_script(
                keys=[self._key(name, instance_id), self._index_key(name)],
                args=[instance_id],
            ))
        return self._registry.deregister(name, instance_id)

    def discover(self, name: str) -> Optional[Dict[str, Any]]:
        """Return one healthy instance for ``name`` (None if expired/absent)."""
        instances = self.discover_all(name)
        return instances[0] if instances else None

    def discover_all(self, name: str) -> List[Dict[str, Any]]:
        """Return all currently healthy instances registered for ``name``."""
        name = _validate_identifier(name, "name")
        if self._redis is not None:
            instances = []
            index_key = self._index_key(name)
            for key in self._redis.smembers(index_key):
                key = key.decode() if isinstance(key, bytes) else key
                current = self._redis.get(key)
                if current is None:
                    self._prune_expired_script(keys=[key, index_key], args=[])
                    continue
                current = current.decode() if isinstance(current, bytes) else current
                try:
                    data = json.loads(current)
                except (TypeError, ValueError):
                    logger.warning("Ignoring malformed service-discovery record for service %s", name)
                    continue
                instance_id = data.get("instance_id")
                metadata = data.get("metadata")
                if not isinstance(instance_id, str) or not isinstance(metadata, dict):
                    logger.warning("Ignoring invalid service-discovery record for service %s", name)
                    continue
                instances.append({"name": name, "instance_id": instance_id, "metadata": metadata})
            return sorted(instances, key=lambda instance: instance["instance_id"])
        return self._registry.get_all(name)

    def is_healthy(self, name: str) -> bool:
        return self.discover(name) is not None

    def close(self) -> None:
        if self._owns_client and self._redis is not None:
            try:
                self._redis.close()
            except Exception:  # pragma: no cover
                logger.debug("Error closing service-discovery redis client", exc_info=True)


_default_discovery: Optional[ServiceDiscovery] = None
_default_lock = threading.Lock()


def get_service_discovery() -> ServiceDiscovery:
    """Process-wide lazily-created ServiceDiscovery singleton."""
    global _default_discovery
    if _default_discovery is None:
        with _default_lock:
            if _default_discovery is None:
                _default_discovery = ServiceDiscovery()
    return _default_discovery
