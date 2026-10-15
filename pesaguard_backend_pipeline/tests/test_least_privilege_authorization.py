"""Least-privilege authorization regression tests (deny by default)."""

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve()
TREE_ROOT = HERE.parents[1]  # pesaguard_backend_pipeline/
REPO_ROOT = HERE.parents[2]  # repo root
INACTIVE_ROUTE_KEYS = {
    "GET /api/v1/settings/",
    "PATCH /api/v1/settings/",
    "POST /api/v1/settings/test-alert",
}
# Prefer the working-tree files over any installed/top-level copies.
for candidate in (str(TREE_ROOT), str(REPO_ROOT)):
    if candidate in sys.path:
        sys.path.remove(candidate)
    sys.path.insert(0, candidate)

import pytest

from authorization_policy import (
    ENDPOINT_EXEMPTIONS,
    ENDPOINT_PERMISSIONS,
    is_forbidden_customer_scope,
    is_internal_scope,
    required_permission_for,
    safe_public_error,
    validate_customer_scopes,
    validate_route_audit_records,
)
import authorization_policy as tree_policy


def test_registry_rejects_wildcards_and_internal_scopes():
    for scope in ["*", "manage:*", "internal:key-sync", "internal:core-api",
                  "admin:users", "database:read", "system:admin",
                  "service:publish", "unknown:permission"]:
        if scope == "unknown:permission":
            assert not is_forbidden_customer_scope(scope)
        else:
            assert is_forbidden_customer_scope(scope), scope
        with pytest.raises(ValueError):
            validate_customer_scopes([scope])


def test_internal_scopes_never_customer_visible():
    assert is_internal_scope("internal:key-sync")
    assert is_internal_scope("service:publish")
    assert not is_internal_scope("read:analytics")


def test_customer_scopes_must_be_known_and_sorted():
    assert validate_customer_scopes(["read:discrepancies", "read:analytics"]) == [
        "read:analytics", "read:discrepancies"]
    with pytest.raises(ValueError):
        validate_customer_scopes([])
    with pytest.raises(ValueError):
        validate_customer_scopes(["read:analytics", "nope:missing"])


def test_safe_errors_never_leak_internal_reasons():
    assert safe_public_error("tenant_mismatch") == "access_denied"
    assert safe_public_error("missing_scope") == "insufficient_scope"
    assert safe_public_error("credential_revoked") == "authentication_required"
    assert safe_public_error("something_new") == "access_denied"


def test_every_mapped_endpoint_requires_known_permission():
    # ENDPOINT_PERMISSIONS and CORE_PERMISSIONS come from the same tree
    # module object (bulk:operations exists in the working tree).
    assert len(ENDPOINT_PERMISSIONS) >= 20
    assert "bulk:operations" in tree_policy.CORE_PERMISSIONS
    for endpoint, permission in ENDPOINT_PERMISSIONS.items():
        assert permission in tree_policy.CORE_PERMISSIONS, endpoint


def test_unmapped_endpoint_denies():
    assert required_permission_for("GET", "/nope/missing") is None


def test_normalize_machine_scopes_rejects_privileged():
    from auth_rbac import AuthRBAC
    assert AuthRBAC.normalize_machine_scopes(["read:analytics"]) == ["read:analytics"]
    # Security-critical violations still raise (fail closed at issuance).
    for bad in (["*"], ["manage:*"], ["internal:key-sync"], ["admin:x"],
                ["manage:all_tenants"], ["manage:security"], ["audit:read"],
                ["events:read"], ["fraud:write"], ["service:publish"]):
        with pytest.raises(ValueError):
            AuthRBAC.normalize_machine_scopes(bad)
    assert AuthRBAC.normalize_machine_scopes(["usage:read"]) == ["read:usage"]
    assert AuthRBAC.normalize_machine_scopes(["transactions:read"]) == ["read:transactions"]
    with pytest.raises(ValueError):
        AuthRBAC.normalize_machine_scopes(["transactions:read", "audit:read"])


def test_role_grants_are_subset_of_registry():
    """Every permission a role can grant must exist in the registry."""
    from auth_rbac import AuthRBAC
    missing = {
        permission
        for grants in AuthRBAC.ROLE_PERMISSIONS.values()
        for permission in grants
        if permission not in tree_policy.CORE_PERMISSIONS
    }
    assert missing == set(), f"role grants missing from registry: {sorted(missing)}"


def test_machine_denied_scopes_never_issue():
    from auth_rbac import AuthRBAC
    for scope in ["manage:all_tenants", "manage:tenant_isolation"]:
        with pytest.raises(ValueError):
            AuthRBAC.normalize_machine_scopes([scope])
        with pytest.raises(ValueError):
            validate_customer_scopes([scope])


@pytest.fixture
def least_privilege_dashboard_client(monkeypatch):
    import uuid

    from sqlalchemy.orm import sessionmaker

    from auth_rbac import (
        AuthRBAC,
        _RevocationBase,
        configure_revocation_store,
    )
    from models import Base, Organization, UserAccount
    from pesaguard_backend_pipeline.api import dashboard_app
    from pesaguard_backend_pipeline.communications.models import CommunicationSavedFilter
    import tenant_org_routes

    monkeypatch.setitem(
        AuthRBAC.ROLE_PERMISSIONS,
        "communications-reader",
        ["read:communications"],
    )
    Base.metadata.create_all(dashboard_app.primary_engine)
    _RevocationBase.metadata.create_all(dashboard_app.primary_engine)
    configure_revocation_store(
        dashboard_app.primary_engine,
        sessionmaker(bind=dashboard_app.primary_engine, expire_on_commit=False),
    )
    monkeypatch.setattr(
        tenant_org_routes,
        "_resolve_session_factory",
        lambda: dashboard_app.SessionLocal,
    )

    suffix = uuid.uuid4().hex
    users = {
        "analyst": f"route-audit-analyst-{suffix}",
        "operations": f"route-audit-operations-{suffix}",
        "communications-reader": f"route-audit-comms-reader-{suffix}",
        "admin": f"route-audit-admin-{suffix}",
    }
    organization_id = f"org-route-audit-{suffix}"
    filter_id = f"filter-route-audit-{suffix}"

    with dashboard_app.SessionLocal() as session:
        session.add(Organization(
            id=organization_id,
            tenant_id="tenant-a",
            name="Original",
            slug=f"org-route-audit-{suffix}",
            status="active",
            settings={"region": "ke"},
        ))
        for role, user_id in users.items():
            session.add(UserAccount(
                id=user_id,
                tenant_id="tenant-a",
                username=user_id,
                roles=[role],
                permissions=[],
                status="active",
                authorization_version=1,
            ))
        session.add(CommunicationSavedFilter(
            id=filter_id,
            tenant_id="tenant-a",
            owner_user_id=users["communications-reader"],
            name=f"owned-filter-{suffix}",
            query={"status": "failed"},
        ))
        session.commit()

    tokens = {
        role: AuthRBAC.generate_token(
            user_id=user_id,
            username=user_id,
            tenant_id="tenant-a",
            roles=[role],
        )
        for role, user_id in users.items()
    }
    return (
        dashboard_app.app.test_client(),
        dashboard_app.SessionLocal,
        tokens,
        organization_id,
        filter_id,
        suffix,
    )


def test_sensitive_mutations_require_write_or_management_permissions(
    least_privilege_dashboard_client,
):
    from models import Organization
    from pesaguard_backend_pipeline.communications.models import CommunicationSavedFilter
    from auth_rbac import AuthRBAC

    assert "manage:communications" in AuthRBAC.ROLE_PERMISSIONS["admin"]
    assert "manage:communications" not in AuthRBAC.ROLE_PERMISSIONS[
        "communications-reader"
    ]
    client, session_factory, tokens, organization_id, filter_id, suffix = (
        least_privilege_dashboard_client
    )

    read_only_patch = client.patch(
        f"/api/v1/organizations/{organization_id}",
        headers={"Authorization": f"Bearer {tokens['analyst']}"},
        json={"name": "Unauthorized"},
    )
    assert read_only_patch.status_code == 403
    with session_factory() as session:
        assert session.get(Organization, organization_id).name == "Original"

    authorized_patch = client.patch(
        f"/api/v1/organizations/{organization_id}",
        headers={"Authorization": f"Bearer {tokens['admin']}"},
        json={"name": "Updated"},
    )
    assert authorized_patch.status_code == 200
    assert authorized_patch.get_json()["organization"]["name"] == "Updated"

    filter_path = "/api/v1/communications/saved-filters"
    read_only_headers = {
        "Authorization": f"Bearer {tokens['communications-reader']}",
    }
    denied_update = client.post(
        filter_path,
        headers=read_only_headers,
        json={"name": f"owned-filter-{suffix}", "query": {"status": "resolved"}},
    )
    denied_delete = client.delete(f"{filter_path}/{filter_id}", headers=read_only_headers)
    assert denied_update.status_code == 403
    assert denied_delete.status_code == 403
    with session_factory() as session:
        saved_filter = session.get(CommunicationSavedFilter, filter_id)
        assert saved_filter.query == {"status": "failed"}

    authorized_update = client.post(
        filter_path,
        headers={"Authorization": f"Bearer {tokens['admin']}"},
        json={"name": f"owned-filter-{suffix}", "query": {"status": "resolved"}},
    )
    assert authorized_update.status_code == 201
    assert authorized_update.get_json()["query"] == {"status": "resolved"}
    authorized_delete = client.delete(
        f"{filter_path}/{filter_id}",
        headers={"Authorization": f"Bearer {tokens['admin']}"},
    )
    assert authorized_delete.status_code == 200

    presets_path = "/incidents/filters/presets"
    assert client.get(
        presets_path,
        headers={"Authorization": f"Bearer {tokens['analyst']}"},
    ).status_code == 200
    denied_preset = client.post(
        presets_path,
        headers={"Authorization": f"Bearer {tokens['analyst']}"},
        json={"name": "custom", "filters": {"status": "open"}},
    )
    assert denied_preset.status_code == 403
    generated_preset = client.post(
        presets_path,
        headers={"Authorization": f"Bearer {tokens['operations']}"},
        json={"name": "custom", "filters": {"status": "open"}},
    )
    assert generated_preset.status_code == 200
    assert generated_preset.get_json()["status"] == "generated"


def test_dead_letter_listing_requires_read_scope_and_replay_requires_resolve_scope(
    least_privilege_dashboard_client,
):
    client, _, tokens, _, _, _ = least_privilege_dashboard_client

    listed = client.get(
        "/api/v1/operations/dead-letters",
        headers={"Authorization": f"Bearer {tokens['analyst']}"},
    )
    denied_read = client.get(
        "/api/v1/operations/dead-letters",
        headers={"Authorization": f"Bearer {tokens['communications-reader']}"},
    )
    denied_replay = client.post(
        "/api/v1/operations/dead-letters/not-found/replay",
        headers={"Authorization": f"Bearer {tokens['analyst']}"},
        json={"reason": "least-privilege regression"},
    )

    assert listed.status_code == 200
    assert denied_read.status_code == 403
    assert denied_replay.status_code == 403


def _read_route_audit():
    data = (REPO_ROOT / "routes_audit.txt").read_bytes()
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = data.decode("utf-16")
    return text.splitlines()


def test_route_inventory_has_explicit_permissions_or_exemptions():
    audit = _read_route_audit()
    counts = validate_route_audit_records(audit)

    assert counts == {"routes": 311, "mapped": 206, "exempt": 105}
    assert not (set(ENDPOINT_PERMISSIONS) & set(ENDPOINT_EXEMPTIONS))
    assert all(" -> UNMAPPED" not in row for row in audit)
    for endpoint, boundary in ENDPOINT_EXEMPTIONS.items():
        assert f"{endpoint} -> EXEMPT:{boundary}" in audit
    assert all(
        required_permission_for(*endpoint.split(" ", 1)) is None
        for endpoint in ENDPOINT_EXEMPTIONS
    )
    scanned = subprocess.check_output(
        [sys.executable, str(TREE_ROOT / "operations" / "audit_endpoints.py")],
        cwd=TREE_ROOT,
        text=True,
    ).splitlines()
    assert INACTIVE_ROUTE_KEYS <= {
        row.split("->", 1)[0].strip()
        for row in scanned
        if row.endswith("-> UNMAPPED")
    }
    for row in scanned:
        endpoint, classification = (part.strip() for part in row.rsplit("->", 1))
        if classification != "UNMAPPED":
            assert ENDPOINT_PERMISSIONS.get(endpoint) == classification, endpoint


def test_authentication_boundary_inventory_matches_handler_guards():
    source = (TREE_ROOT / "app_4_advanced_features.py").read_text(
        encoding="utf-8"
    ).splitlines()
    session_routes = {
        endpoint
        for endpoint, boundary in ENDPOINT_EXEMPTIONS.items()
        if boundary == "authenticated_session"
    }
    credential_routes = {
        endpoint
        for endpoint, boundary in ENDPOINT_EXEMPTIONS.items()
        if boundary != "authenticated_session"
        and endpoint.split(" ", 1)[1].startswith("/auth/")
    }

    for endpoint in session_routes | credential_routes:
        method, path = endpoint.split(" ", 1)
        if path.startswith("/api/v1/auth/"):
            path = path.removeprefix("/api/v1")
        decorator = f'@_idempotent_route("{path}", methods=['
        matches = [
            index for index, line in enumerate(source)
            if line.strip().startswith(decorator)
        ]
        assert matches, f"authentication boundary has no runtime route: {endpoint}"
        declarations = [
            "\n".join(
                source[index + 1:next(
                    (
                        position for position in range(index + 1, len(source))
                        if source[position].lstrip().startswith("def ")
                    ),
                    min(index + 12, len(source)),
                )]
            )
            for index in matches
        ]
        guarded = any("@require_auth(" in block for block in declarations)
        if endpoint in session_routes:
            assert guarded, f"session boundary lacks require_auth: {endpoint}"
        else:
            assert not guarded, f"credential boundary unexpectedly requires session: {endpoint}"


def test_deployed_http_surfaces_are_separately_inventoried_and_classified():
    from operations.generate_runtime_route_audits import collect_deployed_route_surfaces

    surfaces = collect_deployed_route_surfaces()
    audited_routes = {}
    for name in (*surfaces, "unmounted"):
        rows = (REPO_ROOT / f"routes_audit_{name}.txt").read_text(
            encoding="utf-8"
        ).splitlines()
        audited_routes[name] = {}
        for row in rows:
            endpoint, classification = (
                part.strip() for part in row.rsplit("->", 1)
            )
            assert endpoint not in audited_routes[name]
            audited_routes[name][endpoint] = classification

    classified_routes = set(ENDPOINT_PERMISSIONS) | set(ENDPOINT_EXEMPTIONS)
    for surface, routes in surfaces.items():
        assert set(audited_routes[surface]) == routes, (
            f"{surface} runtime routes differ from its inventory: "
            f"missing={sorted(routes - set(audited_routes[surface]))}, "
            f"stale={sorted(set(audited_routes[surface]) - routes)}"
        )
        assert routes <= classified_routes, (
            f"{surface} has routes without an explicit classification: "
            f"{sorted(routes - classified_routes)}"
        )
        for endpoint in routes:
            expected = ENDPOINT_PERMISSIONS.get(endpoint)
            if expected is None:
                expected = f"EXEMPT:{ENDPOINT_EXEMPTIONS[endpoint]}"
            assert audited_routes[surface][endpoint] == expected

    unmounted = classified_routes - set().union(*surfaces.values())
    assert set(audited_routes["unmounted"]) == unmounted
    for endpoint in unmounted:
        expected = ENDPOINT_PERMISSIONS.get(endpoint)
        if expected is None:
            expected = f"EXEMPT:{ENDPOINT_EXEMPTIONS[endpoint]}"
        assert audited_routes["unmounted"][endpoint] == expected

    inactive_rows = (REPO_ROOT / "routes_audit_inactive.txt").read_text(
        encoding="utf-8"
    ).splitlines()
    assert {
        row.split("->", 1)[0].strip()
        for row in inactive_rows
    } == INACTIVE_ROUTE_KEYS
    assert not INACTIVE_ROUTE_KEYS & (set().union(*surfaces.values()) | unmounted)


def test_route_inventory_validator_rejects_new_and_inconsistent_routes():
    audit = _read_route_audit()
    with pytest.raises(ValueError, match="unclassified route"):
        validate_route_audit_records(audit + ["GET /new/route -> UNMAPPED"])

    with pytest.raises(ValueError, match="inconsistent classification"):
        validate_route_audit_records(
            audit + ["GET /health -> read:analytics"]
        )

    with pytest.raises(ValueError, match="inconsistent classification"):
        validate_route_audit_records(audit + ["GET /health -> UNMAPPED"])


def test_runtime_resource_decorators_match_permission_inventory():
    source = (TREE_ROOT / "app_4_advanced_features.py").read_text(
        encoding="utf-8"
    ).splitlines()
    expected_permissions = {
        "PATCH /api/v1/iam/api-clients/<client_id>": "manage:api_keys",
        "PATCH /api/v1/iam/service-identities/<identity_id>": "manage:users",
        "POST /api/v1/iam/api-clients/<client_id>/rotate-secret": "manage:api_keys",
        "POST /api/v1/iam/service-identities/<identity_id>/rotate-secret": "manage:users",
        "POST /auth/api-keys/<key_id>/revoke": "manage:api_keys",
        "POST /auth/api-keys/<key_id>/rotate": "manage:api_keys",
        "GET /on-call/schedule/<operator_id>": "read:discrepancies",
        "GET /webhooks/<webhook_id>/deliveries": "manage:webhooks",
        "PUT /escalation-rules/<rule_id>": "write:escalation_rules",
        "PUT /webhooks/<webhook_id>": "manage:webhooks",
    }

    for endpoint, permission in expected_permissions.items():
        assert ENDPOINT_PERMISSIONS[endpoint] == permission
        method, path = endpoint.split(" ", 1)
        decorator = (
            f'@_idempotent_route("{path}", methods=["{method}"])'
        )
        route_indexes = [
            index for index, line in enumerate(source)
            if line.strip() == decorator
        ]
        assert route_indexes, f"runtime route not found: {endpoint}"
        assert any(
            any(
                f'@require_resource_access("{permission}",' in line
                for line in source[index + 1:index + 7]
            )
            for index in route_indexes
        ), f"runtime scope decorator missing for {endpoint}"
