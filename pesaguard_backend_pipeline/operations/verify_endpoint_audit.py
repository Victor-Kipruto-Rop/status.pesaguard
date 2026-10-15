"""Fail fast if the endpoint-permission and deployed-surface audits drift.

``routes_audit.txt`` records every classified endpoint. The service-specific
snapshots separate the dashboard API, webhook receiver, and source-only routes
not mounted in either deployed HTTP service. ``generate_runtime_route_audits.py``
enumerates the deployed applications; the route tests independently compare
those live registrations with the committed snapshots.

This guard exists so the two failure modes can never silently pass CI:

1. **Missing artifact** - the file was deleted or lost during a restructure, so
   the authorization tests error out with an opaque ``FileNotFoundError``.
2. **Drift** - a deployed route was added or removed without updating the
   service-specific snapshots and permission policy.

Run from the repo root before the test suite (see .github/workflows/ci.yml).
Exit code 0 = healthy, 1 = broken.
"""

from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve()
BACKEND_ROOT = HERE.parents[1]
REPO_ROOT = HERE.parents[2]

for candidate in (str(BACKEND_ROOT), str(REPO_ROOT)):
    if candidate not in sys.path:
        sys.path.insert(0, candidate)

AUDIT_PATH = REPO_ROOT / "routes_audit.txt"
SURFACE_AUDITS = {
    "dashboard_api": REPO_ROOT / "routes_audit_dashboard_api.txt",
    "webhook_receiver": REPO_ROOT / "routes_audit_webhook_receiver.txt",
    "unmounted": REPO_ROOT / "routes_audit_unmounted.txt",
    "inactive": REPO_ROOT / "routes_audit_inactive.txt",
}


def _decode(path: Path) -> list[str]:
    data = path.read_bytes()
    # The artifact has historically been written as UTF-16 (PowerShell) and
    # UTF-8; accept both the way the test does.
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = data.decode("utf-16")
    return [line.strip() for line in text.splitlines() if line.strip()]


def main() -> int:
    if not AUDIT_PATH.exists():
        print(
            "ERROR: routes_audit.txt is missing at the repository root.\n"
            "The endpoint-permission inventory must be present and committed.\n"
            "Run operations/generate_runtime_route_audits.py and re-run CI.",
            file=sys.stderr,
        )
        return 1

    try:
        import authorization_policy as policy
        from operations.generate_runtime_route_audits import INACTIVE_ROUTE_DEFINITIONS
    except Exception as exc:  # pragma: no cover - import guard
        print(f"ERROR: could not import route audit policy: {exc}", file=sys.stderr)
        return 1

    try:
        counts = policy.validate_route_audit_records(_decode(AUDIT_PATH))
    except ValueError as exc:
        print(
            f"ERROR: routes_audit.txt is out of sync with authorization_policy.\n"
            f"{exc}\n"
            "A route was likely added/removed without updating the audit and\n"
            "ENDPOINT_PERMISSIONS/ENDPOINT_EXEMPTIONS together.",
            file=sys.stderr,
        )
        return 1

    expected = {"routes": 306, "mapped": 201, "exempt": 105}
    if counts != expected:
        print(
            f"ERROR: unexpected route inventory {counts}, expected {expected}.\n"
            "If the route surface intentionally changed, update the expected\n"
            "counts in tests/test_least_privilege_authorization.py and this guard.",
            file=sys.stderr,
        )
        return 1

    surface_routes: dict[str, set[str]] = {}
    known_routes = set(policy.ENDPOINT_PERMISSIONS) | set(policy.ENDPOINT_EXEMPTIONS)
    for surface in ("dashboard_api", "webhook_receiver", "unmounted"):
        path = SURFACE_AUDITS[surface]
        if not path.exists():
            print(f"ERROR: {surface} route inventory is missing: {path}", file=sys.stderr)
            return 1
        observed: set[str] = set()
        for number, row in enumerate(_decode(path), start=1):
            if "->" not in row:
                print(f"ERROR: invalid {surface} inventory row {number}: {row}", file=sys.stderr)
                return 1
            endpoint, classification = (part.strip() for part in row.rsplit("->", 1))
            if endpoint not in known_routes:
                print(f"ERROR: unclassified {surface} route: {endpoint}", file=sys.stderr)
                return 1
            if endpoint in observed:
                print(f"ERROR: duplicate {surface} route: {endpoint}", file=sys.stderr)
                return 1
            expected_classification = policy.ENDPOINT_PERMISSIONS.get(endpoint)
            if expected_classification is None:
                expected_classification = f"EXEMPT:{policy.ENDPOINT_EXEMPTIONS[endpoint]}"
            if classification != expected_classification:
                print(
                    f"ERROR: incorrect classification for {surface} route {endpoint}: "
                    f"{classification} (expected {expected_classification})",
                    file=sys.stderr,
                )
                return 1
            observed.add(endpoint)
        surface_routes[surface] = observed

    deployed = surface_routes["dashboard_api"] | surface_routes["webhook_receiver"]
    unmounted = surface_routes["unmounted"]
    if not SURFACE_AUDITS["inactive"].exists():
        print("ERROR: inactive route inventory is missing.", file=sys.stderr)
        return 1
    inactive_rows = _decode(SURFACE_AUDITS["inactive"])
    inactive = {}
    for row in inactive_rows:
        if "->" not in row:
            print(f"ERROR: invalid inactive route row: {row}", file=sys.stderr)
            return 1
        endpoint, classification = (part.strip() for part in row.rsplit("->", 1))
        inactive[endpoint] = classification
    if inactive != INACTIVE_ROUTE_DEFINITIONS:
        print("ERROR: inactive route inventory differs from source evidence.", file=sys.stderr)
        return 1
    if (
        deployed & unmounted
        or deployed | unmounted != known_routes
        or set(inactive) & (deployed | unmounted)
    ):
        print(
            "ERROR: deployed, unmounted, and inactive route inventories overlap "
            "or do not cover their expected scopes.",
            file=sys.stderr,
        )
        return 1

    print(f"endpoint audit OK: {counts}")
    print(
        "route surfaces OK: "
        + ", ".join(f"{name}={len(routes)}" for name, routes in surface_routes.items())
        + f", inactive={len(inactive)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
