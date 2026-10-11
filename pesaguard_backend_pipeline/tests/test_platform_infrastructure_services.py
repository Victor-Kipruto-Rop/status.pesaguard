from __future__ import annotations

import pytest

from cache_service import CacheService
from distributed_lock import DistributedLockService
from feature_flags import FeatureFlagService
from rate_limiter import RateLimiter, TokenBucketRateLimiter
from request_context import RequestContextService
from runtime_config import RuntimeConfig
from tenant_settings_store import TenantSettingsStore
from version_service import VersionService


class FakeRedis:
    def __init__(self):
        self.values: dict[str, tuple[str, int | None]] = {}

    def get(self, key):
        value = self.values.get(key)
        return value[0] if value else None

    def set(self, key, value, ex=None):
        self.values[key] = (value, ex)
        return True

    def delete(self, key):
        return int(self.values.pop(key, None) is not None)


def test_configuration_service_rejects_unsafe_or_invalid_values(monkeypatch):
    monkeypatch.setenv("PESAGUARD_API_URL", "https://api.example.test/")
    for name in (
        "PESAGUARD_API_MAX_BODY_BYTES",
        "PESAGUARD_WEBHOOK_MAX_BODY_BYTES",
        "PESAGUARD_API_RATE_LIMIT_PER_MINUTE",
        "PORT",
        "TENANT_ID",
        "KAFKA_TOPIC_TRANSACTIONS",
    ):
        monkeypatch.delenv(name, raising=False)

    config = RuntimeConfig.from_env()
    assert config.public_api_url == "https://api.example.test"
    assert config.api_body_limit > 0
    assert 1 <= config.port <= 65535

    for name, value in (
        ("PESAGUARD_API_MAX_BODY_BYTES", "0"),
        ("PESAGUARD_WEBHOOK_MAX_BODY_BYTES", "-1"),
        ("PESAGUARD_API_RATE_LIMIT_PER_MINUTE", "0"),
        ("PORT", "65536"),
    ):
        monkeypatch.setenv(name, value)
        with pytest.raises(ValueError):
            RuntimeConfig.from_env()
        monkeypatch.delenv(name)

    credentialed_url = "https://" + "operator" + ":" + "secret" + "@example.test"
    monkeypatch.setenv("PESAGUARD_API_URL", credentialed_url)
    with pytest.raises(ValueError, match="must not include credentials"):
        RuntimeConfig.from_env()
    monkeypatch.setenv("PESAGUARD_API_URL", "https://@example.test")
    with pytest.raises(ValueError, match="must not include credentials"):
        RuntimeConfig.from_env()
    monkeypatch.setenv("PESAGUARD_API_URL", "https://example.test:70000")
    with pytest.raises(ValueError, match="valid port"):
        RuntimeConfig.from_env()


def test_request_context_bounds_untrusted_ids_and_ignores_tenant_header():
    context = RequestContextService.create({
        "X-Request-ID": "bad\r\nInjected: yes",
        "X-Correlation-ID": "correlation-17",
        "X-Tenant-ID": "tenant-attacker",
        "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    })

    assert context.request_id != "bad\r\nInjected: yes"
    assert context.correlation_id == "correlation-17"
    assert context.trace_id == "0123456789abcdef0123456789abcdef"
    assert context.tenant_id == ""
    assert context.traceparent.startswith(f"00-{context.trace_id}-")
    assert RequestContextService.create({}, trusted_tenant_id="tenant-server").tenant_id == "tenant-server"


def test_cache_is_tenant_isolated_bounded_and_json_only(monkeypatch):
    fake = FakeRedis()
    cache = CacheService(fake, max_entries=2)
    cache.set(tenant_id="tenant-a", key="dashboard", value={"count": 5}, ttl_seconds=30)
    cache.set(tenant_id="tenant-b", key="dashboard", value={"count": 9}, ttl_seconds=30)

    assert cache.get(tenant_id="tenant-a", key="dashboard") == {"count": 5}
    assert cache.get(tenant_id="tenant-b", key="dashboard") == {"count": 9}
    assert len(fake.values) == 2
    assert cache.delete(tenant_id="tenant-a", key="dashboard") is True
    assert cache.get(tenant_id="tenant-a", key="dashboard") is None

    bounded = CacheService(max_entries=2)
    for index in range(3):
        bounded.set(tenant_id="tenant-a", key=f"key-{index}", value=index, ttl_seconds=30)
    assert bounded.snapshot()["memory_entries"] == 2
    assert bounded.get(tenant_id="tenant-a", key="key-0") is None

    for value, ttl in ((object(), 10), ({"bad": float("nan")}, 10), ({"x": 1}, 0)):
        with pytest.raises(ValueError):
            cache.set(tenant_id="tenant-a", key="invalid", value=value, ttl_seconds=ttl)

    clock = [100.0]
    monkeypatch.setattr("cache_service.time.monotonic", lambda: clock[0])
    local = CacheService(max_entries=2)
    local.set(tenant_id="tenant-a", key="short", value="cached", ttl_seconds=1)
    clock[0] += 2
    assert local.get(tenant_id="tenant-a", key="short") is None
    assert local.snapshot()["memory_entries"] == 0


def test_cache_uses_bounded_local_fallback_on_redis_failure():
    class BrokenRedis:
        def get(self, _key):
            raise ConnectionError("offline")

        def set(self, *_args, **_kwargs):
            raise ConnectionError("offline")

    cache = CacheService(BrokenRedis(), max_entries=1)
    cache.set(tenant_id="tenant-a", key="key", value="value", ttl_seconds=10)
    assert cache.get(tenant_id="tenant-a", key="key") == "value"
    assert cache.snapshot()["fallbacks"] == 2
    assert cache.snapshot()["memory_entries"] == 1


def test_request_limits_and_locks_reject_invalid_operational_values():
    for value in (0, -1, True):
        with pytest.raises(ValueError):
            TokenBucketRateLimiter(default_max_per_minute=value)
    limiter_service = RateLimiter(default_max_per_minute=2)
    with pytest.raises(ValueError):
        limiter_service.set_limits(0)
    assert limiter_service.max_requests_per_minute == 2
    limiter = TokenBucketRateLimiter(default_max_per_minute=1)
    with pytest.raises(ValueError):
        limiter.is_allowed("client", "route", tokens_required=0)
    with pytest.raises(ValueError):
        limiter.record_distinct("client", "route", "item", window_seconds=0)

    lock = DistributedLockService(redis_url="")
    for ttl in (0, -1, float("inf"), True):
        with pytest.raises(ValueError):
            lock.acquire("resource", ttl_seconds=ttl)
    with pytest.raises(ValueError):
        lock.acquire_blocking("resource", wait_seconds=-1)
    with pytest.raises(ValueError):
        lock.acquire("   ")


def test_feature_flag_environment_kill_switch_and_tenant_validation(monkeypatch):
    service = FeatureFlagService(tenant_store={"tenant-a": {"feature_flags": {"alerting": True}}})
    service.register_override("alerting", True)
    monkeypatch.setenv("PESAGUARD_FLAG_ALERTING", "false")
    assert service.resolve("tenant-a", "alerting") is False

    with pytest.raises(ValueError, match="tenant_id is required"):
        service.resolve("", "alerting")
    with pytest.raises(ValueError, match="tenant_id is required"):
        service.set_tenant_override("", "alerting", False)


def test_concurrent_feature_flag_updates_preserve_distinct_flags(tmp_path):
    from concurrent.futures import ThreadPoolExecutor

    store = TenantSettingsStore(path=str(tmp_path / "tenant-settings.json"))
    service = FeatureFlagService(tenant_store=store)
    flags = ("alerting", "ai_assistant", "beta_dashboard", "webhook_retry")

    with ThreadPoolExecutor(max_workers=len(flags)) as executor:
        list(executor.map(
            lambda flag: service.set_tenant_override("tenant-a", flag, True),
            flags,
        ))

    assert all(service.resolve("tenant-a", flag) is True for flag in flags)


def test_version_service_exposes_only_safe_release_metadata(monkeypatch):
    monkeypatch.setenv("PESAGUARD_RELEASE", "release-2026.10.10\nTOKEN=secret")
    payload = VersionService(service_name="dashboard").snapshot()
    assert payload["service"] == "dashboard"
    assert payload["version"] == "unknown"
    assert payload["environment"] in {"test", "development", "staging", "production"}


def test_control_plane_version_endpoint_is_uncached_and_non_sensitive(monkeypatch):
    from fastapi.testclient import TestClient
    from pesaguard_backend_pipeline.fastapi_app import app

    monkeypatch.setenv("PESAGUARD_RELEASE", "release-test-1")
    response = TestClient(app).get("/version")

    assert response.status_code == 200
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json()["version"] == "release-test-1"
    assert set(response.json()) == {"service", "version", "environment"}

    from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app

    dashboard_client = dashboard_app.test_client()
    assert dashboard_client.get("/live").json == {"status": "ok"}
    dashboard_response = dashboard_client.get("/version")
    assert dashboard_response.status_code == 200
    assert dashboard_response.headers["Cache-Control"] == "no-store"
    assert dashboard_response.json["version"] == "release-test-1"
