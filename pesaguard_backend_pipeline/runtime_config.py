from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlsplit

from environment import required_env


def _int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


@dataclass(frozen=True)
class RuntimeConfig:
    public_api_url: str
    api_body_limit: int
    webhook_body_limit: int
    api_rate_limit_per_minute: int
    tenant_id: str
    kafka_topic: str
    redis_url: str
    admin_api_token: str
    daraja_consumer_key: str
    daraja_consumer_secret: str
    port: int

    @classmethod
    def from_env(cls) -> "RuntimeConfig":
        public_api_url = required_env("PESAGUARD_API_URL").rstrip("/")
        parsed_url = urlsplit(public_api_url)
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.hostname:
            raise ValueError("PESAGUARD_API_URL must be an absolute http or https URL")
        if parsed_url.username is not None or parsed_url.password is not None:
            raise ValueError("PESAGUARD_API_URL must not include credentials")
        try:
            parsed_url.port
        except ValueError as exc:
            raise ValueError("PESAGUARD_API_URL must use a valid port") from exc

        api_body_limit = _int_env("PESAGUARD_API_MAX_BODY_BYTES", 1048576)
        webhook_body_limit = _int_env("PESAGUARD_WEBHOOK_MAX_BODY_BYTES", 1048576)
        api_rate_limit_per_minute = _int_env("PESAGUARD_API_RATE_LIMIT_PER_MINUTE", 60)
        port = _int_env("PORT", 5001)
        for name, value in (
            ("PESAGUARD_API_MAX_BODY_BYTES", api_body_limit),
            ("PESAGUARD_WEBHOOK_MAX_BODY_BYTES", webhook_body_limit),
            ("PESAGUARD_API_RATE_LIMIT_PER_MINUTE", api_rate_limit_per_minute),
        ):
            if value < 1:
                raise ValueError(f"{name} must be greater than zero")
        if not 1 <= port <= 65535:
            raise ValueError("PORT must be between 1 and 65535")
        tenant_id = os.getenv("TENANT_ID", "default").strip()
        if not tenant_id:
            raise ValueError("TENANT_ID must not be empty")
        kafka_topic = os.getenv("KAFKA_TOPIC_TRANSACTIONS", "pesaguard.transactions.raw").strip()
        if not kafka_topic:
            raise ValueError("KAFKA_TOPIC_TRANSACTIONS must not be empty")

        return cls(
            public_api_url=public_api_url,
            api_body_limit=api_body_limit,
            webhook_body_limit=webhook_body_limit,
            api_rate_limit_per_minute=api_rate_limit_per_minute,
            tenant_id=tenant_id,
            kafka_topic=kafka_topic,
            redis_url=os.getenv("REDIS_URL", "redis://localhost:6379/0").strip(),
            admin_api_token=os.getenv("PESAGUARD_ADMIN_API_TOKEN", ""),
            daraja_consumer_key=os.getenv("DARAJA_CONSUMER_KEY", ""),
            daraja_consumer_secret=os.getenv("DARAJA_CONSUMER_SECRET", ""),
            port=port,
        )