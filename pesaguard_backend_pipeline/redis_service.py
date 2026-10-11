"""Explicitly managed Redis connection boundary."""

from __future__ import annotations

import logging
import math
import os
import threading
from typing import Any
from urllib.parse import urlsplit

logger = logging.getLogger("pesaguard.redis")


class RedisService:
    """Create and own a bounded-timeout Redis client without silent fallbacks."""

    def __init__(
        self,
        redis_url: str | None = None,
        *,
        client: Any | None = None,
        socket_connect_timeout: float = 2.0,
        socket_timeout: float = 5.0,
        decode_responses: bool = False,
    ) -> None:
        if (
            isinstance(socket_connect_timeout, bool)
            or not isinstance(socket_connect_timeout, (int, float))
            or not math.isfinite(socket_connect_timeout)
            or socket_connect_timeout <= 0
            or isinstance(socket_timeout, bool)
            or not isinstance(socket_timeout, (int, float))
            or not math.isfinite(socket_timeout)
            or socket_timeout <= 0
        ):
            raise ValueError("Redis timeouts must be positive")
        self.redis_url = redis_url if redis_url is not None else os.getenv("REDIS_URL", "")
        self._validate_url(self.redis_url)
        self._client = client
        self._owns_client = client is None
        self._connect_timeout = socket_connect_timeout
        self._socket_timeout = socket_timeout
        self._decode_responses = decode_responses
        self._lock = threading.Lock()

    @staticmethod
    def _validate_url(redis_url: str) -> None:
        if not isinstance(redis_url, str) or not redis_url.strip():
            raise ValueError("REDIS_URL must be configured")
        parsed = urlsplit(redis_url)
        if parsed.scheme not in {"redis", "rediss"} or not parsed.hostname:
            raise ValueError("REDIS_URL must be an absolute redis or rediss URL")
        try:
            parsed.port
        except ValueError as exc:
            raise ValueError("REDIS_URL must use a valid port") from exc

    def client(self) -> Any:
        if self._client is not None:
            return self._client
        with self._lock:
            if self._client is None:
                try:
                    import redis
                except ImportError as exc:
                    raise RuntimeError("redis package not installed") from exc
                self._client = redis.from_url(
                    self.redis_url,
                    socket_connect_timeout=self._connect_timeout,
                    socket_timeout=self._socket_timeout,
                    decode_responses=self._decode_responses,
                )
            return self._client

    def ping(self) -> bool:
        result = self.client().ping()
        if result is not True:
            raise ConnectionError("Redis ping did not return success")
        return True

    def close(self) -> None:
        if self._owns_client and self._client is not None:
            close = getattr(self._client, "close", None)
            if callable(close):
                close()
            self._client = None
