"""
Multi-backend Rate Limiting Engine for PesaGuard API endpoints and webhooks.

Supports distributed Redis rate limiting for multi-worker production environments
and fallback thread-safe in-memory Token Bucket algorithm for standalone single-instance setups.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from functools import wraps
from typing import Any, Dict, Optional, Tuple

from flask import Response, g, jsonify, request
from environment import runtime_environment
from security_helpers import get_client_ip

logger = logging.getLogger("pesaguard.rate_limiter")

_BUCKET_IDLE_TTL_SECONDS = 60

# Redis connection environment defaults
# Keep this disabled unless an operator explicitly configures Redis; a default
# localhost URL causes the auth layer to believe distributed rate limiting is
# available even in local/test environments where no Redis service exists.
REDIS_URL = os.getenv("REDIS_URL", "").strip()


def redis_rate_limiting_enabled() -> bool:
    """Evaluate runtime configuration while honoring explicit module overrides.

    Tests and local feature toggles often monkeypatch the module global directly,
    so we prefer a concrete override when one is set instead of re-reading only
    environment variables.
    """
    override = globals().get("ENABLE_REDIS_RATE_LIMITING")
    if isinstance(override, bool):
        return override

    raw_env = os.getenv("ENABLE_REDIS_RATE_LIMITING", "").strip().lower()
    if raw_env in {"1", "true", "yes", "on"}:
        return True
    if raw_env in {"0", "false", "no", "off"}:
        return False

    if os.getenv("PESAGUARD_ENABLE_REDIS_RATE_LIMIT", "0").strip().lower() in {"1", "true", "yes", "on"}:
        return True

    return bool(REDIS_URL)


ENABLE_REDIS_RATE_LIMITING = redis_rate_limiting_enabled()

# Atomic Lua script for distributed token bucket evaluation in Redis
_REDIS_TOKEN_BUCKET_LUA = """
local key = KEYS[1]
local max_tokens = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local tokens_required = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(data[1])
local last_refill = tonumber(data[2])

if not tokens or not last_refill then
    tokens = max_tokens
    last_refill = now
else
    local delta = math.max(0, now - last_refill)
    tokens = math.min(max_tokens, tokens + (delta * refill_rate))
end

if tokens >= tokens_required then
    tokens = tokens - tokens_required
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, ttl)
    return {1, math.floor(tokens), max_tokens, 0}
else
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, ttl)
    local needed = tokens_required - tokens
    local reset_in = math.ceil(needed / refill_rate)
    return {0, 0, max_tokens, reset_in}
end
"""


class TokenBucketRateLimiter:
    """Thread-safe in-memory Token Bucket rate limiter."""

    def __init__(self, default_max_per_minute: int = 30):
        self._validate_positive_integer(default_max_per_minute, "default_max_per_minute")
        self.buckets: Dict[str, Tuple[float, float]] = {}
        self.distinct_values: Dict[str, Tuple[set[str], float, int]] = {}
        self.max_tokens_per_minute = default_max_per_minute
        self.refill_rate = default_max_per_minute / 60.0
        self._lock = threading.Lock()

    def set_limits(self, max_requests_per_minute: int) -> None:
        """Update rate limit configuration."""
        self._validate_positive_integer(max_requests_per_minute, "max_requests_per_minute")
        self.max_tokens_per_minute = max_requests_per_minute
        self.refill_rate = max_requests_per_minute / 60.0

    @staticmethod
    def _validate_positive_integer(value: int, name: str) -> None:
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            raise ValueError(f"{name} must be a positive integer")

    def _evict_stale_buckets(self, current_time: float) -> None:
        """Purge stale buckets to prevent memory bloat under unauthenticated IP sweeps."""
        stale_keys = [
            key for key, (_, last_refill) in self.buckets.items()
            if current_time - last_refill > _BUCKET_IDLE_TTL_SECONDS
        ]
        for key in stale_keys:
            del self.buckets[key]
        stale_distinct_keys = [
            key for key, (_, last_seen, window_seconds) in self.distinct_values.items()
            if current_time - last_seen > window_seconds
        ]
        for key in stale_distinct_keys:
            del self.distinct_values[key]

    def is_allowed(self, client_id: str, endpoint: str, tokens_required: int = 1) -> Tuple[bool, Dict[str, Any]]:
        """Evaluate token availability for a request."""
        self._validate_positive_integer(tokens_required, "tokens_required")
        if not client_id or not endpoint:
            raise ValueError("client_id and endpoint are required")
        bucket_key = f"{client_id}:{endpoint}"
        current_time = time.time()

        with self._lock:
            self._evict_stale_buckets(current_time)

            if bucket_key not in self.buckets:
                self.buckets[bucket_key] = (float(self.max_tokens_per_minute), current_time)

            tokens, last_refill = self.buckets[bucket_key]
            time_passed = current_time - last_refill

            tokens = min(float(self.max_tokens_per_minute), tokens + (time_passed * self.refill_rate))

            if tokens >= tokens_required:
                tokens -= tokens_required
                self.buckets[bucket_key] = (tokens, current_time)
                return True, {
                    "remaining": int(tokens),
                    "limit": self.max_tokens_per_minute,
                    "reset_in": 0,
                    "retry_after": 0,
                }

            self.buckets[bucket_key] = (tokens, current_time)
            needed = tokens_required - tokens
            reset_in = int(needed / self.refill_rate) + 1
            return False, {
                "remaining": 0,
                "limit": self.max_tokens_per_minute,
                "reset_in": reset_in,
                "retry_after": reset_in,
            }

    def record_distinct(
        self,
        client_id: str,
        endpoint: str,
        value: str,
        window_seconds: int,
    ) -> int:
        """Track distinct identifiers per client over a bounded detection window."""
        self._validate_positive_integer(window_seconds, "window_seconds")
        if not client_id or not endpoint or not value:
            raise ValueError("client_id, endpoint, and value are required")
        key = f"{client_id}:{endpoint}"
        now = time.time()
        with self._lock:
            current = self.distinct_values.get(key)
            if current is None or now - current[1] >= current[2]:
                values: set[str] = set()
            else:
                values = current[0]
            values.add(value)
            self.distinct_values[key] = (values, now, window_seconds)
            return len(values)

    def reset(self, client_id: str, endpoint: str) -> None:
        """Clear one in-memory bucket, for example after an authorized unlock."""
        key = f"{client_id}:{endpoint}"
        with self._lock:
            self.buckets.pop(key, None)


class RedisRateLimiter:
    """Distributed Redis-backed rate limiter with atomic Lua evaluation."""

    def __init__(self, redis_url: str = REDIS_URL):
        self.redis_url = redis_url
        self._client: Any = None
        self._script: Any = None
        self._lock = threading.Lock()

    def _get_client(self) -> Any:
        if self._client is not None:
            return self._client
        with self._lock:
            if self._client is not None:
                return self._client
            import redis
            client = redis.from_url(self.redis_url, socket_connect_timeout=2, socket_timeout=2)
            self._script = client.register_script(_REDIS_TOKEN_BUCKET_LUA)
            self._client = client
            return self._client

    def is_allowed(self, client_id: str, endpoint: str, max_per_minute: int, tokens_required: int = 1) -> Tuple[bool, Dict[str, Any]]:
        """Execute atomic Lua script on Redis store."""
        TokenBucketRateLimiter._validate_positive_integer(max_per_minute, "max_per_minute")
        TokenBucketRateLimiter._validate_positive_integer(tokens_required, "tokens_required")
        if not client_id or not endpoint:
            raise ValueError("client_id and endpoint are required")
        key = f"pesaguard:ratelimit:{client_id}:{endpoint}"
        refill_rate = max_per_minute / 60.0
        now = time.time()
        ttl = _BUCKET_IDLE_TTL_SECONDS

        client = self._get_client()
        res = self._script(keys=[key], args=[max_per_minute, refill_rate, tokens_required, now, ttl], client=client)

        allowed = bool(res[0])
        remaining = int(res[1])
        limit = int(res[2])
        reset_in = int(res[3])

        return allowed, {
            "remaining": remaining,
            "limit": limit,
            "reset_in": reset_in,
            "retry_after": reset_in,
        }

    def record_distinct(self, client_id: str, endpoint: str, value: str, window_seconds: int) -> int:
        """Track distinct values atomically in Redis for distributed detection."""
        TokenBucketRateLimiter._validate_positive_integer(window_seconds, "window_seconds")
        if not client_id or not endpoint or not value:
            raise ValueError("client_id, endpoint, and value are required")
        key = f"pesaguard:ratelimit:{client_id}:{endpoint}"
        client = self._get_client()
        pipe = client.pipeline(transaction=True)
        pipe.sadd(key, value)
        pipe.expire(key, window_seconds)
        pipe.scard(key)
        result = pipe.execute()
        return int(result[2])

    def reset(self, client_id: str, endpoint: str) -> None:
        """Delete a distributed token bucket during an authorized account unlock."""
        key = f"pesaguard:ratelimit:{client_id}:{endpoint}"
        self._get_client().delete(key)


class RateLimiter:
    """Backward-compatible wrapper exposing set_limits/is_allowed for app webhook usage."""

    def __init__(self, default_max_per_minute: int = 30, *, fail_closed: bool = False):
        TokenBucketRateLimiter._validate_positive_integer(default_max_per_minute, "default_max_per_minute")
        self.max_requests_per_minute = default_max_per_minute
        self.fail_closed = fail_closed
        self._memory = TokenBucketRateLimiter(default_max_per_minute=default_max_per_minute)
        self._redis = RedisRateLimiter() if redis_rate_limiting_enabled() else None

    @staticmethod
    def _redis_enabled_for_runtime() -> bool:
        return redis_rate_limiting_enabled()

    def _fail_closed_without_distributed_backend(self) -> bool:
        if not self.fail_closed:
            return False
        if runtime_environment() in {"development", "test"}:
            return False
        return not self._redis_enabled_for_runtime() or self._redis is None

    def set_limits(self, max_requests_per_minute: int) -> None:
        TokenBucketRateLimiter._validate_positive_integer(
            max_requests_per_minute, "max_requests_per_minute"
        )
        self.max_requests_per_minute = max_requests_per_minute
        self._memory.set_limits(max_requests_per_minute)

    def is_allowed(self, client_id: str, endpoint: str, tokens_required: int = 1) -> Tuple[bool, Dict[str, Any]]:
        redis_enabled = self._redis_enabled_for_runtime()
        if self._fail_closed_without_distributed_backend():
            _record_auth_limiter_unavailable()
            return False, {
                "remaining": 0,
                "limit": self.max_requests_per_minute,
                "reset_in": 1,
                "retry_after": 1,
                "unavailable": True,
            }

        if redis_enabled and self._redis:
            try:
                return self._redis.is_allowed(
                    client_id,
                    endpoint,
                    max_per_minute=self.max_requests_per_minute,
                    tokens_required=tokens_required,
                )
            except Exception as exc:
                logger.warning("Redis limiter failed, falling back to memory: %s", exc)
                if self.fail_closed:
                    _record_auth_limiter_unavailable()
                    return False, {
                        "remaining": 0,
                        "limit": self.max_requests_per_minute,
                        "reset_in": 1,
                        "retry_after": 1,
                        "unavailable": True,
                    }
        return self._memory.is_allowed(client_id, endpoint, tokens_required=tokens_required)

    def record_distinct(
        self,
        client_id: str,
        endpoint: str,
        value: str,
        window_seconds: int,
    ) -> int:
        """Record a distinct value in the distributed store when configured."""
        if self._fail_closed_without_distributed_backend():
            _record_auth_limiter_unavailable()
            raise RuntimeError("Distributed rate limiter is unavailable")
        if self._redis_enabled_for_runtime() and self._redis:
            try:
                return self._redis.record_distinct(client_id, endpoint, value, window_seconds)
            except Exception as exc:
                logger.warning("Redis distinct-attempt tracker failed: %s", exc)
                if self.fail_closed:
                    _record_auth_limiter_unavailable()
                    raise RuntimeError("Distributed rate limiter is unavailable") from exc
        return self._memory.record_distinct(client_id, endpoint, value, window_seconds)

    def reset(self, client_id: str, endpoint: str) -> None:
        """Clear a client bucket in configured stores."""
        if self._fail_closed_without_distributed_backend():
            _record_auth_limiter_unavailable()
            raise RuntimeError("Distributed rate limiter is unavailable")
        if self._redis_enabled_for_runtime() and self._redis:
            try:
                self._redis.reset(client_id, endpoint)
                return
            except Exception as exc:
                logger.warning("Redis rate-limiter reset failed: %s", exc)
                if self.fail_closed:
                    _record_auth_limiter_unavailable()
                    raise RuntimeError("Distributed rate limiter is unavailable") from exc
        self._memory.reset(client_id, endpoint)


# Global limiter instances
_memory_limiter = TokenBucketRateLimiter()
_redis_limiter: Optional[RedisRateLimiter] = None


def get_redis_limiter() -> Optional[RedisRateLimiter]:
    global _redis_limiter
    if not redis_rate_limiting_enabled():
        _redis_limiter = None
        return None
    if _redis_limiter is None:
        _redis_limiter = RedisRateLimiter()
    return _redis_limiter


def _runtime_requires_distributed_limiter() -> bool:
    """Only production and staging require a shared limiter by default.

    Local/test runs may use the memory limiter even when a route is marked
    fail-closed; otherwise the auth stack becomes unusable during normal
    development and CI workloads.
    """
    return runtime_environment() in {"production", "staging"}


def _record_auth_limiter_unavailable() -> None:
    try:
        from metrics import record_business_metric
        record_business_metric("auth_rate_limiter_unavailable")
    except Exception:
        logger.debug("Unable to record auth rate-limiter outage metric", exc_info=True)


def _get_client_identifier() -> str:
    """Extract authenticated user ID, tenant ID, or client IP safely."""
    if hasattr(g, "user") and getattr(g.user, "user_id", None):
        return f"user_{g.user.user_id}"
    
    tenant_id = getattr(g, "tenant_id", None)
    ip = get_client_ip(request) or "unknown"

    if tenant_id:
        return f"tenant_{tenant_id}_{ip}"
    return f"ip_{ip}"


def rate_limit(
    max_requests_per_minute: int = 30,
    tokens_per_request: int = 1,
    endpoint_name: Optional[str] = None,
    *,
    fail_closed: Optional[bool] = None,
):
    """
    Enforce rate limits on Flask route endpoints.
    Emits standard RFC rate-limiting headers.
    """
    def decorator(func):
        @wraps(func)
        def decorated_function(*args, **kwargs):
            runtime = runtime_environment()
            production = runtime == "production"
            # Production and staging require shared limits by default. An
            # explicit fail_closed=True contract means the endpoint REQUIRES
            # the distributed limiter in every runtime -- tests that verify
            # fail-closed behavior depend on this invariant, and weakening it
            # for any runtime would silently disable the protection under
            # test coverage (PesaGuard Rule #9).
            require_distributed_limiter = (
                production or runtime == "staging" or fail_closed is True
            )
            redis_enabled = redis_rate_limiting_enabled()
            if require_distributed_limiter and not redis_enabled:
                _record_auth_limiter_unavailable()
                response = jsonify({
                    "error": "rate_limiter_unavailable",
                    "message": "Request protection is temporarily unavailable.",
                })
                response.status_code = 503
                response.headers["Retry-After"] = "1"
                return response

            client_id = _get_client_identifier()
            endpoint = endpoint_name or request.endpoint or func.__name__

            allowed = True
            status: Dict[str, Any] = {}

            # Try Redis distributed limiter first if configured
            redis_limiter = get_redis_limiter()
            if redis_limiter is not None:
                try:
                    allowed, status = redis_limiter.is_allowed(
                        client_id, endpoint, max_requests_per_minute, tokens_per_request
                    )
                except Exception as exc:
                    logger.warning("Redis rate limiter unavailable, falling back to memory bucket: %s", exc)
                    if require_distributed_limiter:
                        try:
                            from metrics import record_security_event
                            record_security_event()
                        except Exception:
                            logger.debug("Unable to record auth rate-limit security event", exc_info=True)
                        _record_auth_limiter_unavailable()
                        resp = jsonify({
                            "error": "rate_limiter_unavailable",
                            "message": "Authentication is temporarily unavailable.",
                            "retry_after": 1,
                        })
                        resp.status_code = 503
                        resp.headers["Retry-After"] = "1"
                        return resp
                    _memory_limiter.set_limits(max_requests_per_minute)
                    allowed, status = _memory_limiter.is_allowed(client_id, endpoint, tokens_per_request)
            else:
                _memory_limiter.set_limits(max_requests_per_minute)
                allowed, status = _memory_limiter.is_allowed(client_id, endpoint, tokens_per_request)

            g.rate_limit_status = status

            if not allowed:
                from metrics import record_security_event
                record_security_event()
                retry_after = status.get("reset_in", 60)
                resp = jsonify({
                    "error": "rate_limit_exceeded",
                    "message": "Too many requests. Please slow down.",
                    "retry_after": retry_after,
                })
                resp.status_code = 429
                resp.headers["Retry-After"] = str(retry_after)
                resp.headers["X-RateLimit-Limit"] = str(status.get("limit", max_requests_per_minute))
                resp.headers["X-RateLimit-Remaining"] = "0"
                resp.headers["X-RateLimit-Reset"] = str(retry_after)
                return resp

            response = func(*args, **kwargs)

            # Attach telemetry headers to valid responses
            if isinstance(response, Response):
                response.headers["X-RateLimit-Limit"] = str(status.get("limit", max_requests_per_minute))
                response.headers["X-RateLimit-Remaining"] = str(status.get("remaining", 0))

            return response

        return decorated_function

    return decorator


def get_rate_limit_status() -> Dict[str, Any]:
    """Retrieve rate limit telemetry status for the current request context."""
    return getattr(g, "rate_limit_status", {})
