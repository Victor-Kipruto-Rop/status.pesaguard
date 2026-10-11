"""Load backend environment settings without importing application services."""
from __future__ import annotations

import os
import sys
from pathlib import Path

from dotenv import load_dotenv

DEFAULT_ENV_FILE = Path(__file__).resolve().parents[1] / ".env"
_REAL_DEFAULT_ENV_FILE = DEFAULT_ENV_FILE
_ENVIRONMENT_VARIABLES = (
    "PESAGUARD_ENVIRONMENT",
    "PESAGUARD_ENV",
    "ENVIRONMENT",
    "FLASK_ENV",
)


def _running_under_pytest() -> bool:
    """Return True when the process is pytest or a pytest child worker."""
    argv = " ".join(sys.argv).lower()
    return (
        "pytest" in sys.modules
        or bool(os.getenv("PYTEST_CURRENT_TEST"))
        or bool(os.getenv("PYTEST_VERSION"))
        or "pytest" in argv
    )


def runtime_environment() -> str:
    """Resolve one consistent application environment across legacy aliases.

    Under pytest we default to the test environment unless the process explicitly
    sets a different environment variable. This prevents a repository-level
    .env production file from accidentally turning the test suite into a
    production fail-closed runtime.
    """
    configured = [
        (name, os.getenv(name, "").strip().lower())
        for name in _ENVIRONMENT_VARIABLES
        if os.getenv(name, "").strip()
    ]
    normalized = {
        "prod": "production",
        "dev": "development",
        "testing": "test",
    }
    # Compare aliases AFTER normalization so equivalent values (e.g. "prod" and
    # "production") do not collide, while genuinely different environments still
    # fail loudly rather than silently resolving to an ambiguous runtime.
    values = [(name, normalized.get(value, value)) for name, value in configured]
    if len({value for _, value in values}) > 1:
        names = ", ".join(name for name, _ in values)
        raise RuntimeError(f"Conflicting application environment variables are set: {names}")
    if not values:
        if _running_under_pytest():
            return "test"
        return "development"
    value = values[0][1]
    if value not in {"development", "test", "production", "staging"}:
        raise RuntimeError("Application environment must be development, test, staging, or production")
    return value


def load_backend_env(env_path: str | os.PathLike[str] | None = None) -> bool:
    """Fill missing process variables; never search the current working directory.

    PESAGUARD_ENV_FILE selects an explicit deployment file. Set
    PYTHON_DOTENV_DISABLED=1 for environment-only deployments and isolated tests.
    Values are literal (no ${...} expansion), including secrets containing '$'.
    """
    if os.getenv("PYTHON_DOTENV_DISABLED", "").lower() in {"1", "true", "yes", "on"}:
        return False
    configured_path = env_path if env_path is not None else os.getenv("PESAGUARD_ENV_FILE")
    if (
        configured_path is None
        and _running_under_pytest()
        and Path(DEFAULT_ENV_FILE) == _REAL_DEFAULT_ENV_FILE
    ):
        # Never auto-load the real repository .env under pytest (it may hold
        # production secrets / fail-closed settings). Tests that opt in must
        # monkeypatch DEFAULT_ENV_FILE or pass PESAGUARD_ENV_FILE explicitly.
        return False
    path = Path(configured_path) if configured_path is not None else DEFAULT_ENV_FILE
    if not path.is_file():
        if configured_path is not None:
            raise RuntimeError("Configured backend environment file is not available")
        return False
    return load_dotenv(path, override=False, interpolate=False, encoding="utf-8-sig")


def required_env(name: str) -> str:
    """Require an explicit value without including it in errors or logs."""
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"{name} must be configured")
    return value


load_backend_env()
