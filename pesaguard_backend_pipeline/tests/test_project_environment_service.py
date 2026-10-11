from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, Organization, UserAccount, Project, ProjectEnvironment
from project_environment_service import (
    ConcurrentModification,
    EnvironmentService,
    InvalidStateTransition,
    NotFound,
    ProjectService,
    ValidationError,
    _apply_environment_status,
    _apply_project_status,
    _slugify,
    can_promote,
)


# --- Fixtures --------------------------------------------------------------


@pytest.fixture()
def services():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with factory() as session:
        # Two tenants, each with an organization + an owner user.
        for tenant in ("tenant-a", "tenant-b"):
            session.add(UserAccount(
                id=f"user-{tenant}", username=f"user-{tenant}",
                tenant_id=tenant, roles=["owner"], permissions=[], status="active",
            ))
            session.add(Organization(
                id=f"org-{tenant}", tenant_id=tenant, name=f"Org {tenant}",
                slug=f"org-{tenant}", status="active",
            ))
        session.commit()
    return ProjectService(session_factory=factory), EnvironmentService(session_factory=factory)


def _make_project(project_service: ProjectService, tenant="tenant-a", name="Payments"):
    return project_service.create_project(
        organization_id=f"org-{tenant}", name=name,
        tenant_id=tenant, created_by=f"user-{tenant}",
    )


# --- Unit tests: pure helpers (no DB) -------------------------------------


def test_slugify_basic():
    assert _slugify("Payments Core") == "payments-core"
    assert _slugify("  Hello, World!!  ") == "hello-world"
    assert _slugify("***") == "project"


@pytest.mark.parametrize("current,target,expected", [
    ("development", "sandbox", True),
    ("sandbox", "staging", True),
    ("staging", "production", True),
    ("development", "staging", False),   # skip is forbidden
    ("development", "production", False),
    ("sandbox", "development", False),   # demotion is forbidden
    ("production", "staging", False),    # terminal tier cannot advance
    ("production", "production", False),
])
def test_can_promote_forward_one_tier_only(current, target, expected):
    assert can_promote(current, target) is expected


def test_project_status_helper_lifecycle():
    class _P:  # minimal duck-typed stand-in
        status = "active"

    p = _P()
    _apply_project_status(p, "deactivate")
    assert p.status == "deactivated"
    _apply_project_status(p, "activate")
    assert p.status == "active"
    _apply_project_status(p, "archive")
    assert p.status == "archived"
    with pytest.raises(InvalidStateTransition):
        _apply_project_status(p, "deactivate")  # archived cannot deactivate


def test_environment_status_helper_lifecycle():
    class _E:
        status = "active"

    e = _E()
    _apply_environment_status(e, "suspend")
    assert e.status == "suspended"
    _apply_environment_status(e, "resume")
    assert e.status == "active"
    _apply_environment_status(e, "deactivate")
    assert e.status == "deactivated"
    with pytest.raises(InvalidStateTransition):
        _apply_environment_status(e, "resume")  # deactivated is terminal


# --- Integration: ProjectService ------------------------------------------


def test_create_and_get_project_scoped(services):
    project_service, _ = services
    created = _make_project(project_service)
    assert created["tenant_id"] == "tenant-a"
    assert created["status"] == "active"
    assert created["version"] == 1
    assert created["slug"] == "payments"
    fetched = project_service.get_project(created["id"], "tenant-a")
    assert fetched is not None and fetched["id"] == created["id"]


def test_create_project_slug_dedup(services):
    project_service, _ = services
    a = _make_project(project_service, name="Ledger")
    b = _make_project(project_service, name="Ledger")
    assert a["slug"] == "ledger"
    assert b["slug"] == "ledger-1"


def test_create_project_rejects_unknown_org(services):
    project_service, _ = services
    with pytest.raises(NotFound):
        project_service.create_project(
            organization_id="does-not-exist", name="X",
            tenant_id="tenant-a", created_by="user-tenant-a",
        )


def test_project_lifecycle_transitions(services):
    project_service, _ = services
    pid = _make_project(project_service)["id"]
    assert project_service.deactivate_project(pid, "tenant-a")["status"] == "deactivated"
    assert project_service.activate_project(pid, "tenant-a")["status"] == "active"
    archived = project_service.archive_project(pid, "tenant-a")
    assert archived["status"] == "archived"
    with pytest.raises(InvalidStateTransition):
        project_service.deactivate_project(pid, "tenant-a")


def test_archived_project_must_be_restored_before_reactivate(services):
    project_service, _ = services
    pid = _make_project(project_service)["id"]
    project_service.archive_project(pid, "tenant-a")
    with pytest.raises(InvalidStateTransition):
        project_service.activate_project(pid, "tenant-a")
    assert project_service.restore_project(pid, "tenant-a")["status"] == "active"
    assert project_service.activate_project(pid, "tenant-a")["status"] == "active"


def test_archived_project_cannot_be_edited(services):
    project_service, _ = services
    pid = _make_project(project_service)["id"]
    project_service.archive_project(pid, "tenant-a")
    with pytest.raises(InvalidStateTransition):
        project_service.update_project(pid, "tenant-a", name="Renamed")


def test_update_project_increments_version(services):
    project_service, _ = services
    pid = _make_project(project_service)["id"]
    updated = project_service.update_project(pid, "tenant-a", name="Payments v2")
    assert updated["name"] == "Payments v2"
    assert updated["version"] == 2


def test_project_optimistic_locking_conflict(services):
    project_service, _ = services
    pid = _make_project(project_service)["id"]
    with pytest.raises(ConcurrentModification):
        project_service.update_project(pid, "tenant-a", name="X", expected_version=99)


def test_transfer_project_ownership(services):
    project_service, _ = services
    pid = _make_project(project_service)["id"]
    result = project_service.transfer_project_ownership(pid, "tenant-a", "user-2")
    assert result["owner_user_id"] == "user-2"


# --- Integration + Security: tenant isolation (Project) -------------------


def test_project_cross_tenant_is_404(services):
    project_service, _ = services
    pid = _make_project(project_service, tenant="tenant-a")["id"]
    # tenant-b must not see tenant-a's project (indistinguishable 404).
    assert project_service.get_project(pid, "tenant-b") is None
    with pytest.raises(NotFound):
        project_service.update_project(pid, "tenant-b", name="Hijack")
    with pytest.raises(NotFound):
        project_service.archive_project(pid, "tenant-b")


def test_list_projects_isolated_per_tenant(services):
    project_service, _ = services
    _make_project(project_service, tenant="tenant-a", name="A-only")
    _make_project(project_service, tenant="tenant-b", name="B-only")
    a = project_service.list_projects("tenant-a")
    b = project_service.list_projects("tenant-b")
    assert {p["name"] for p in a} == {"A-only"}
    assert {p["name"] for p in b} == {"B-only"}


def test_create_project_requires_tenant(services):
    project_service, _ = services
    with pytest.raises(ValidationError):
        project_service.create_project(
            organization_id="org-tenant-a", name="X",
            tenant_id="", created_by="user-tenant-a",
        )


def _make_env(env_service, project_id, name="Dev", etype="development", tenant="tenant-a"):
    return env_service.create_environment(
        project_id=project_id, name=name, environment_type=etype,
        tenant_id=tenant, created_by=f"user-{tenant}",
    )


# --- Integration: EnvironmentService --------------------------------------


def test_create_and_get_environment_scoped(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    env = _make_env(env_service, pid, name="Dev", etype="development")
    assert env["tenant_id"] == "tenant-a"
    assert env["organization_id"] == "org-tenant-a"
    assert env["environment_type"] == "development"
    assert env["status"] == "active"
    assert env["version"] == 1
    fetched = env_service.get_environment(env["id"], "tenant-a")
    assert fetched is not None and fetched["id"] == env["id"]


def test_create_environment_requires_active_project(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    project_service.archive_project(pid, "tenant-a")
    with pytest.raises(InvalidStateTransition):
        _make_env(env_service, pid, name="Dev", etype="development")


def test_create_environment_rejects_unknown_project(services):
    _, env_service = services
    with pytest.raises(NotFound):
        _make_env(env_service, "no-such-project", name="Dev", etype="development")


def test_create_environment_rejects_duplicate_name(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    _make_env(env_service, pid, name="Same", etype="development")
    with pytest.raises(ValidationError):
        _make_env(env_service, pid, name="Same", etype="sandbox")


def test_create_environment_rejects_invalid_type(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    with pytest.raises(ValidationError):
        _make_env(env_service, pid, name="Bad", etype="qa")


def test_environment_suspend_resume(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid)["id"]
    assert env_service.suspend_environment(eid, "tenant-a")["status"] == "suspended"
    assert env_service.resume_environment(eid, "tenant-a")["status"] == "active"


def test_environment_deactivate_is_terminal(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid)["id"]
    assert env_service.deactivate_environment(eid, "tenant-a")["status"] == "deactivated"
    with pytest.raises(InvalidStateTransition):
        env_service.resume_environment(eid, "tenant-a")
    with pytest.raises(InvalidStateTransition):
        env_service.promote_environment(eid, "tenant-a", "sandbox")


def test_environment_promote_forward_one_tier(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid, name="E", etype="development")["id"]
    assert env_service.promote_environment(eid, "tenant-a", "sandbox")["environment_type"] == "sandbox"
    assert env_service.promote_environment(eid, "tenant-a", "staging")["environment_type"] == "staging"


def test_environment_promote_rejects_skip(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid, name="E", etype="development")["id"]
    with pytest.raises(InvalidStateTransition):
        env_service.promote_environment(eid, "tenant-a", "production")


def test_environment_promote_rejects_demotion(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid, name="E", etype="staging")["id"]
    with pytest.raises(InvalidStateTransition):
        env_service.promote_environment(eid, "tenant-a", "development")


def test_environment_production_is_terminal_tier(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid, name="Prod", etype="production")["id"]
    with pytest.raises(InvalidStateTransition):
        env_service.promote_environment(eid, "tenant-a", "production")


def test_environment_update_increments_version(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid)["id"]
    updated = env_service.update_environment(eid, "tenant-a", configuration={"region": "eu"})
    assert updated["configuration"] == {"region": "eu"}
    assert updated["version"] == 2


def test_environment_optimistic_locking_conflict(services):
    project_service, env_service = services
    pid = _make_project(project_service)["id"]
    eid = _make_env(env_service, pid)["id"]
    with pytest.raises(ConcurrentModification):
        env_service.update_environment(eid, "tenant-a", configuration={"x": 1}, expected_version=42)



# --- Security: environment tenant isolation -------------------------------


def test_environment_cross_tenant_is_404(services):
    project_service, env_service = services
    pid = _make_project(project_service, tenant="tenant-a")["id"]
    eid = _make_env(env_service, pid, name="Dev", etype="development", tenant="tenant-a")["id"]
    assert env_service.get_environment(eid, "tenant-b") is None
    with pytest.raises(NotFound):
        env_service.update_environment(eid, "tenant-b", configuration={"x": 1})
    with pytest.raises(NotFound):
        env_service.promote_environment(eid, "tenant-b", "sandbox")
    with pytest.raises(NotFound):
        env_service.suspend_environment(eid, "tenant-b")


def test_list_environments_isolated_per_tenant(services):
    project_service, env_service = services
    pa = _make_project(project_service, tenant="tenant-a", name="PA")["id"]
    pb = _make_project(project_service, tenant="tenant-b", name="PB")["id"]
    _make_env(env_service, pa, name="EA", etype="development", tenant="tenant-a")
    _make_env(env_service, pb, name="EB", etype="development", tenant="tenant-b")
    a = env_service.list_environments("tenant-a")
    b = env_service.list_environments("tenant-b")
    assert {e["name"] for e in a} == {"EA"}
    assert {e["name"] for e in b} == {"EB"}


def test_environment_not_reachable_via_other_tenants_project(services):
    project_service, env_service = services
    # tenant-b tries to create an env under tenant-a's project id: must 404.
    pa = _make_project(project_service, tenant="tenant-a", name="PA")["id"]
    with pytest.raises(NotFound):
        _make_env(env_service, pa, name="Injected", etype="development", tenant="tenant-b")

