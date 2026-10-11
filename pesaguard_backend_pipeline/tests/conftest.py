import os
import sys
import tempfile

import pytest

# Tests must never inherit credentials or service endpoints from deployment files.
os.environ["PYTHON_DOTENV_DISABLED"] = "1"
os.environ.setdefault("PESAGUARD_API_URL", "http://localhost:5001")

TESTS_DIR = os.path.dirname(__file__)
PACKAGE_DIR = os.path.dirname(TESTS_DIR)
REPOSITORY_ROOT = os.path.dirname(PACKAGE_DIR)

owned_paths = {
    os.path.normcase(os.path.realpath(path))
    for path in (REPOSITORY_ROOT, PACKAGE_DIR)
}
sys.path[:] = [
    path for path in sys.path
    if os.path.normcase(os.path.realpath(path or os.getcwd())) not in owned_paths
]
sys.path[0:0] = [TESTS_DIR, PACKAGE_DIR, REPOSITORY_ROOT]

if len(os.environ.get("JWT_SECRET_KEY", "").encode("utf-8")) < 32:
    os.environ["JWT_SECRET_KEY"] = "test-secret-key-with-at-least-32-bytes"
_test_db_directory = None
_test_db_path = None
if not os.getenv("PYTEST_TEST_DB_URL") and not os.getenv("TEST_DATABASE_URL"):
    _test_db_directory = tempfile.TemporaryDirectory(prefix="pesaguard-test-")
    test_db_path = os.path.join(_test_db_directory.name, "test.sqlite3")
    _test_db_path = os.path.normcase(os.path.realpath(test_db_path))
    os.environ["PYTEST_TEST_DB_URL"] = f"sqlite:///{test_db_path}"

from test_config import configure_test_database  # noqa: E402


configure_test_database()

from app_4_advanced_features import engine  # noqa: E402
from auth_rbac import _RevocationBase  # noqa: E402
from models import Base  # noqa: E402


Base.metadata.create_all(engine)
_RevocationBase.metadata.create_all(engine)


@pytest.fixture(scope="session", autouse=True)
def cleanup_test_database():
    yield
    if _test_db_directory is not None:
        from sqlalchemy.orm import close_all_sessions
        import auth_rbac

        close_all_sessions()
        engines = [engine, getattr(auth_rbac, "_revocation_engine", None)]
        for module_name, attribute in (
            ("app", "event_store"),
            ("pesaguard_backend_pipeline.app", "event_store"),
            ("event_store", "event_store"),
            ("pesaguard_backend_pipeline.event_store", "event_store"),
            ("api.dashboard_app", "transaction_event_store"),
            ("pesaguard_backend_pipeline.api.dashboard_app", "transaction_event_store"),
            ("reconciliation_job", "event_store"),
            ("pesaguard_backend_pipeline.reconciliation_job", "event_store"),
        ):
            module = sys.modules.get(module_name)
            store = getattr(module, attribute, None) if module is not None else None
            if store is not None:
                engines.append(getattr(store, "engine", None))
        for module_name, attribute in (
            ("app_1", "engine"),
            ("app_3_features", "engine"),
            ("app_4_advanced_features", "engine"),
            ("api.dashboard_app", "primary_engine"),
            ("pesaguard_backend_pipeline.api.dashboard_app", "primary_engine"),
            ("background_tasks", "_task_db_engine"),
            ("pesaguard_backend_pipeline.background_tasks", "_task_db_engine"),
            ("metrics", "_metrics_engine"),
            ("pesaguard_backend_pipeline.metrics", "_metrics_engine"),
        ):
            module = sys.modules.get(module_name)
            test_engine = getattr(module, attribute, None) if module is not None else None
            if test_engine is not None:
                engines.append(test_engine)

        disposed = set()
        for test_engine in engines:
            if test_engine is None or id(test_engine) in disposed:
                continue
            database_path = getattr(test_engine.url, "database", None)
            if database_path and os.path.normcase(os.path.realpath(database_path)) == _test_db_path:
                test_engine.dispose()
                disposed.add(id(test_engine))
        _test_db_directory.cleanup()


@pytest.fixture(autouse=True)
def reset_in_memory_login_rate_limits():
    from app_4_advanced_features import _LOGIN_PROTECTION_LIMITERS
    from rate_limiter import _memory_limiter

    for limiter in _LOGIN_PROTECTION_LIMITERS.values():
        with limiter._memory._lock:
            limiter._memory.buckets.clear()
            limiter._memory.distinct_values.clear()
    with _memory_limiter._lock:
        _memory_limiter.buckets.clear()
        _memory_limiter.distinct_values.clear()
    yield


@pytest.fixture(autouse=True)
def restore_revocation_store():
    import auth_rbac

    previous_engine = auth_rbac._revocation_engine
    previous_session_factory = auth_rbac._RevocationSession
    previous_checked = auth_rbac._revocation_store_checked
    yield
    current_engine = auth_rbac._revocation_engine
    auth_rbac.configure_revocation_store(previous_engine, previous_session_factory)
    auth_rbac._revocation_store_checked = previous_checked
    if current_engine is not None and current_engine is not previous_engine:
        current_engine.dispose()
