"""Probe: which test file import breaks the pesaguard rate limiter?"""
import os
import sys
import importlib

os.environ["PYTHON_DOTENV_DISABLED"] = "1"
os.environ["REDIS_URL"] = "redis://localhost:6379/0"
os.environ["JWT_SECRET_KEY"] = "test-secret-key-with-at-least-32-bytes"
os.environ["DATABASE_URL"] = "sqlite+pysqlite:///:memory:"
os.environ["PESAGUARD_API_URL"] = "http://testserver"

REPO_ROOT = os.path.dirname(os.getcwd())
sys.path.insert(0, os.path.join(os.getcwd(), "tests"))
sys.path.insert(0, os.getcwd())
sys.path.insert(0, REPO_ROOT)

# Simulate conftest import chain
import rate_limiter as top_rl
print(f"0. after import rate_limiter: top={top_rl.redis_rate_limiting_enabled()}")

import pesaguard_backend_pipeline.rate_limiter as pkg_rl
print(f"1. pkg instance enabled: {pkg_rl.redis_rate_limiting_enabled()}")

# The login decorator uses app_4's import
import app_4_advanced_features
print(f"2. after app_4 import: top={top_rl.redis_rate_limiting_enabled()} pkg={pkg_rl.redis_rate_limiting_enabled()}")
print(f"   app_4.ENABLE_REDIS_RATE_LIMITING={getattr(app_4_advanced_features, 'ENABLE_REDIS_RATE_LIMITING', 'MISSING')}")

# Now import each failing test file and check state after each
test_files = [
    "test_auth_lifecycle",
    "test_developer_api_key_sync",
    "test_device_risk_auth",
    "test_login_attempt_protection",
    "test_login_hardening",
    "test_password_credentials",
    "test_password_policy",
    "test_session_management",
    "test_token_revocation",
    "test_security_event_instrumentation",
    "test_audit_hardening",
    "test_iam_foundation",
]
for name in test_files:
    try:
        importlib.import_module(name)
        t = top_rl.redis_rate_limiting_enabled()
        p = pkg_rl.redis_rate_limiting_enabled()
        a = getattr(app_4_advanced_features, "ENABLE_REDIS_RATE_LIMITING", "MISSING")
        flag = " <<<< CHANGED" if not (t and p) else ""
        print(f"   imported {name}: top={t} pkg={p} app4={a}{flag}")
    except Exception as exc:
        print(f"   FAILED to import {name}: {type(exc).__name__}: {exc}")
