"""Probe: why does RedisRateLimiter fail in production runtime?"""
import os
import sys
import traceback

os.environ["PYTHON_DOTENV_DISABLED"] = "1"
os.environ["REDIS_URL"] = "redis://localhost:6379/0"
os.environ["PESAGUARD_ENVIRONMENT"] = "production"
os.environ["JWT_SECRET_KEY"] = "test-secret-key-with-at-least-32-bytes"
os.environ["DATABASE_URL"] = "sqlite+pysqlite:///:memory:"
os.environ["PESAGUARD_API_URL"] = "http://testserver"

sys.path.insert(0, os.getcwd())

import rate_limiter

print("ENABLE_REDIS_RATE_LIMITING =", rate_limiter.ENABLE_REDIS_RATE_LIMITING)
print("redis_rate_limiting_enabled() =", rate_limiter.redis_rate_limiting_enabled())

limiter = rate_limiter.get_redis_limiter()
print("get_redis_limiter() =", limiter)

if limiter is not None:
    try:
        result = limiter.is_allowed("probe_user", "probe_endpoint", 30, 1)
        print("is_allowed OK:", result)
    except Exception as exc:
        print("is_allowed FAILED:", type(exc).__name__, exc)
        traceback.print_exc()
else:
    print("limiter is None - checking why")
    print("REDIS_URL =", repr(rate_limiter.REDIS_URL))
    try:
        import redis
        client = redis.from_url("redis://localhost:6379/0", socket_connect_timeout=2, socket_timeout=2)
        print("direct ping:", client.ping())
    except Exception as exc:
        print("direct redis FAILED:", type(exc).__name__, exc)
        traceback.print_exc()
