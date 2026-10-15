"""Regenerate route inventories from the deployed HTTP application topologies."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND_ROOT = ROOT / "pesaguard_backend_pipeline"
OUTPUTS = {
    "dashboard_api": ROOT / "routes_audit_dashboard_api.txt",
    "webhook_receiver": ROOT / "routes_audit_webhook_receiver.txt",
    "unmounted": ROOT / "routes_audit_unmounted.txt",
    "inactive": ROOT / "routes_audit_inactive.txt",
}
INACTIVE_ROUTE_DEFINITIONS = {
    "GET /api/v1/settings/": "INACTIVE:unregistered_blueprint",
    "PATCH /api/v1/settings/": "INACTIVE:unregistered_blueprint",
    "POST /api/v1/settings/test-alert": "INACTIVE:unregistered_blueprint",
}


def _flask_routes(app) -> set[str]:
    return {
        f"{method} {rule.rule}"
        for rule in app.url_map.iter_rules()
        for method in set(rule.methods or ()) - {"HEAD", "OPTIONS"}
    }


def _dashboard_routes() -> set[str]:
    script = """
import json
import sys
from pathlib import Path
root = Path.cwd()
backend = root / "pesaguard_backend_pipeline"
sys.path[:0] = [str(backend / "tests"), str(backend), str(root)]
from test_config import configure_test_database
configure_test_database()
from pesaguard_backend_pipeline.api.dashboard_app import app as dashboard_app
from pesaguard_backend_pipeline.fastapi_app import app as control_plane, _advanced_features
routes = {
    f"{method} {rule.rule}"
    for rule in dashboard_app.url_map.iter_rules()
    for method in set(rule.methods or ()) - {"HEAD", "OPTIONS"}
}
for rule in _advanced_features.app.url_map.iter_rules():
    if not rule.rule.startswith("/auth/"):
        continue
    for method in set(rule.methods or ()) - {"HEAD", "OPTIONS"}:
        routes.add(f"{method} {rule.rule}")
        routes.add(f"{method} /api/v1{rule.rule}")
for route in control_plane.routes:
    if hasattr(route, "path") and hasattr(route, "methods"):
        routes.update(
            f"{method} {route.path}"
            for method in set(route.methods or ()) - {"HEAD", "OPTIONS"}
        )
print(json.dumps(sorted(routes)))
"""
    environment = {
        **os.environ,
        "PYTHON_DOTENV_DISABLED": "1",
        "JWT_SECRET_KEY": "test-secret-key-with-at-least-32-bytes",
        "PYTEST_TEST_DB_URL": "sqlite:///:memory:",
        "USE_IN_MEMORY_TEST_DB": "true",
        "PESAGUARD_API_URL": "http://localhost:5001",
    }
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )
    json_line = next(
        (line for line in reversed(result.stdout.splitlines()) if line.startswith("[")),
        None,
    )
    if json_line is None:
        raise RuntimeError("dashboard route enumeration returned no JSON")
    return set(json.loads(json_line))


def _webhook_receiver_routes() -> set[str]:
    script = """
import json
import os
import sys
from pathlib import Path
root = Path.cwd()
backend = root / "pesaguard_backend_pipeline"
os.environ.update({
    "PYTHON_DOTENV_DISABLED": "1",
    "JWT_SECRET_KEY": "test-secret-key-with-at-least-32-bytes",
    "PYTEST_TEST_DB_URL": "sqlite:///:memory:",
    "USE_IN_MEMORY_TEST_DB": "true",
    "PESAGUARD_API_URL": "http://localhost:5001",
})
sys.path[:0] = [str(backend / "tests"), str(backend), str(root)]
from test_config import configure_test_database
configure_test_database()
from app import app
print(json.dumps(sorted(
    f"{method} {rule.rule}"
    for rule in app.url_map.iter_rules()
    for method in set(rule.methods or ()) - {"HEAD", "OPTIONS"}
)))
"""
    environment = {
        **os.environ,
        "PYTHON_DOTENV_DISABLED": "1",
        "JWT_SECRET_KEY": "test-secret-key-with-at-least-32-bytes",
        "PYTEST_TEST_DB_URL": "sqlite:///:memory:",
        "USE_IN_MEMORY_TEST_DB": "true",
        "PESAGUARD_API_URL": "http://localhost:5001",
    }
    result = subprocess.run(
        [sys.executable, "-c", script],
        cwd=ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )
    json_line = next(
        (line for line in reversed(result.stdout.splitlines()) if line.startswith("[")),
        None,
    )
    if json_line is None:
        raise RuntimeError("webhook receiver route enumeration returned no JSON")
    return set(json.loads(json_line))


def collect_deployed_route_surfaces() -> dict[str, set[str]]:
    """Return method/path pairs for each independently deployed HTTP service."""
    return {
        "dashboard_api": _dashboard_routes(),
        "webhook_receiver": _webhook_receiver_routes(),
    }


def _classification(
    endpoint: str,
    permissions: dict[str, str],
    exemptions: dict[str, str],
) -> str:
    permission = permissions.get(endpoint)
    if permission is not None:
        return permission
    boundary = exemptions.get(endpoint)
    if boundary is not None:
        return f"EXEMPT:{boundary}"
    raise ValueError(f"deployed route has no explicit classification: {endpoint}")


def _write_inventory(
    path: Path,
    routes: set[str],
    permissions: dict[str, str],
    exemptions: dict[str, str],
) -> None:
    rows = [
        f"{endpoint} -> {_classification(endpoint, permissions, exemptions)}"
        for endpoint in sorted(routes)
    ]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    sys.path.insert(0, str(BACKEND_ROOT))
    from authorization_policy import ENDPOINT_EXEMPTIONS, ENDPOINT_PERMISSIONS

    surfaces = collect_deployed_route_surfaces()
    all_deployed = set().union(*surfaces.values())
    classified = set(ENDPOINT_PERMISSIONS) | set(ENDPOINT_EXEMPTIONS)
    missing = all_deployed - classified
    if missing:
        print("UNCLASSIFIED DEPLOYED ROUTES:")
        for endpoint in sorted(missing):
            print(f"  {endpoint}")
        return 1

    _write_inventory(
        ROOT / "routes_audit.txt",
        classified,
        ENDPOINT_PERMISSIONS,
        ENDPOINT_EXEMPTIONS,
    )
    for surface, routes in surfaces.items():
        _write_inventory(
            OUTPUTS[surface],
            routes,
            ENDPOINT_PERMISSIONS,
            ENDPOINT_EXEMPTIONS,
        )
    _write_inventory(
        OUTPUTS["unmounted"],
        classified - all_deployed,
        ENDPOINT_PERMISSIONS,
        ENDPOINT_EXEMPTIONS,
    )
    inactive_rows = [
        f"{endpoint} -> {classification}"
        for endpoint, classification in sorted(INACTIVE_ROUTE_DEFINITIONS.items())
    ]
    OUTPUTS["inactive"].write_text(
        "\n".join(inactive_rows) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        "route inventories regenerated: "
        + ", ".join(f"{name}={len(routes)}" for name, routes in surfaces.items())
        + f", unmounted={len(classified - all_deployed)}"
        + f", inactive={len(INACTIVE_ROUTE_DEFINITIONS)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
