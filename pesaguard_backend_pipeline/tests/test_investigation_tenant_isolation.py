"""Investigation Service (62): tenant isolation and configuration validation.

Pins the tenant boundary for escalation rules and on-call rotations:

- ``EscalationEngine.update_rule``/``delete_rule`` must only touch rules that
  belong to the caller's tenant and must never mutate identity fields
  (Rule #11: no Tenant A -> Tenant B data access).
- Rule configuration rejects unknown operators/actions/fields at write time so
  a rule can never silently never-match (Rule #47: docs must match behavior).
- The live ``PUT /escalation-rules/<rule_id>`` route returns 404 for a rule
  outside the caller's tenant (no existence oracle) and 400 for invalid input.
"""

from __future__ import annotations

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from test_config import configure_test_database

configure_test_database()

from app_4_advanced_features import SessionLocal, app  # noqa: E402
from auth_rbac import AuthRBAC  # noqa: E402
from escalation_engine import EscalationEngine  # noqa: E402
from models import Base, EscalationRule, OnCallRotation, UserAccount  # noqa: E402
from on_call_service import OnCallService  # noqa: E402


def build_session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False)()


def _rule():
    return {
        "tenant_id": "tenant-a",
        "name": "Critical escalation",
        "description": "Escalate critical incidents",
        "condition_field": "severity",
        "condition_operator": "equals",
        "condition_value": "critical",
        "action": "escalate",
        "target": "senior_operator",
        "priority": 1,
    }


# --- Service-level tenant boundary -------------------------------------------


def test_update_rule_cannot_modify_another_tenants_rule():
    engine, session = build_session()
    engine_service = EscalationEngine(session)
    created = engine_service.create_rule(**_rule())

    result = engine_service.update_rule(created["id"], tenant_id="tenant-b", name="hijacked")

    assert result == {"error": "rule_not_found"}
    with sessionmaker(bind=engine)() as verify:
        stored = verify.get(EscalationRule, created["id"])
        assert stored.name == "Critical escalation"
        assert stored.tenant_id == "tenant-a"
    session.close()
    engine.dispose()


def test_update_rule_changes_allowed_fields_only():
    engine, session = build_session()
    engine_service = EscalationEngine(session)
    created = engine_service.create_rule(**_rule())

    result = engine_service.update_rule(
        created["id"],
        tenant_id="tenant-a",
        name="Renamed rule",
        priority=9,
    )

    assert result["name"] == "Renamed rule"
    assert result["priority"] == 9
    session.close()
    engine.dispose()


def test_update_rule_rejects_identity_and_unknown_fields():
    engine, session = build_session()
    engine_service = EscalationEngine(session)
    created = engine_service.create_rule(**_rule())

    for forbidden in (
        {"id": "rule_stolen"},
        {"created_at": "2026-01-01T00:00:00+00:00"},
        {"unknown_field": "value"},
    ):
        with pytest.raises(ValueError):
            engine_service.update_rule(created["id"], tenant_id="tenant-a", **forbidden)

    with sessionmaker(bind=engine)() as verify:
        stored = verify.get(EscalationRule, created["id"])
        assert stored.id == created["id"]
        assert stored.tenant_id == "tenant-a"
    session.close()
    engine.dispose()


def test_update_rule_rejects_unknown_operator_and_action():
    engine, session = build_session()
    engine_service = EscalationEngine(session)
    created = engine_service.create_rule(**_rule())

    with pytest.raises(ValueError):
        engine_service.update_rule(
            created["id"], tenant_id="tenant-a", condition_operator="like"
        )
    with pytest.raises(ValueError):
        engine_service.update_rule(created["id"], tenant_id="tenant-a", action="block_funds")
    session.close()
    engine.dispose()


def test_create_rule_rejects_unknown_operator_action_and_field():
    engine, session = build_session()
    engine_service = EscalationEngine(session)

    with pytest.raises(ValueError):
        engine_service.create_rule(**{**_rule(), "condition_operator": "sounds_like"})
    with pytest.raises(ValueError):
        engine_service.create_rule(**{**_rule(), "action": "refund"})
    with pytest.raises(ValueError):
        engine_service.create_rule(**{**_rule(), "condition_field": "customer_mood"})
    session.close()
    engine.dispose()



def test_delete_rule_is_tenant_scoped():
    engine, session = build_session()
    engine_service = EscalationEngine(session)
    created = engine_service.create_rule(**_rule())

    assert engine_service.delete_rule(created["id"], tenant_id="tenant-b") == {
        "error": "rule_not_found"
    }
    with sessionmaker(bind=engine)() as verify:
        assert verify.get(EscalationRule, created["id"]) is not None

    assert engine_service.delete_rule(created["id"], tenant_id="tenant-a") == {
        "status": "deleted"
    }
    with sessionmaker(bind=engine)() as verify:
        assert verify.get(EscalationRule, created["id"]) is None
    session.close()
    engine.dispose()


def test_update_and_delete_rotation_are_tenant_scoped():
    engine, session = build_session()
    on_call = OnCallService(session)
    rotation = on_call.create_rotation(
        tenant_id="tenant-a",
        operator_id="op-1",
        operator_name="Operator One",
        operator_email="op1@example.com",
        operator_phone="+254700000001",
        shift_start="2026-09-14T08:00:00+00:00",
        shift_end="2026-09-14T20:00:00+00:00",
    )

    assert on_call.update_rotation(
        rotation["id"], tenant_id="tenant-b", operator_name="stolen"
    ) == {"error": "rotation_not_found"}
    assert on_call.delete_rotation(rotation["id"], tenant_id="tenant-b") == {
        "error": "rotation_not_found"
    }
    with sessionmaker(bind=engine)() as verify:
        stored = verify.get(OnCallRotation, rotation["id"])
        assert stored is not None
        assert stored.operator_name == "Operator One"
        assert stored.tenant_id == "tenant-a"

    with pytest.raises(ValueError):
        on_call.update_rotation(rotation["id"], tenant_id="tenant-a", id="rotation_other")

    assert on_call.update_rotation(
        rotation["id"], tenant_id="tenant-a", operator_name="Operator Updated"
    )["operator_name"] == "Operator Updated"
    session.close()
    engine.dispose()



# --- HTTP boundary (live route in app_4) --------------------------------------


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as test_client:
        yield test_client


@pytest.fixture
def admin_token():
    account = UserAccount(
        id="user_iso_admin",
        username="iso-admin",
        tenant_id="test-tenant",
        roles=["admin"],
        status="active",
        authorization_version=1,
    )
    with SessionLocal() as session:
        session.add(account)
        session.commit()
    try:
        yield AuthRBAC.generate_token(
            user_id="user_iso_admin",
            username="iso-admin",
            tenant_id="test-tenant",
            roles=["admin"],
        )
    finally:
        with SessionLocal() as session:
            session.query(EscalationRule).filter(
                EscalationRule.tenant_id.in_(["test-tenant", "tenant-iso-b"])
            ).delete(synchronize_session=False)
            session.query(UserAccount).filter(
                UserAccount.id == "user_iso_admin"
            ).delete(synchronize_session=False)
            session.commit()


def _seed_rule(tenant_id: str, name: str) -> str:
    with SessionLocal() as session:
        created = EscalationEngine(session).create_rule(
            **{**_rule(), "tenant_id": tenant_id, "name": name}
        )
        return created["id"]


def test_put_rule_from_another_tenant_returns_404(client, admin_token):
    foreign_rule = _seed_rule("tenant-iso-b", "Foreign rule")
    try:
        response = client.put(
            f"/escalation-rules/{foreign_rule}",
            headers={"Authorization": f"Bearer {admin_token}"},
            json={"tenant_id": "test-tenant", "name": "hijacked"},
        )
        assert response.status_code == 404
        assert response.get_json()["error"] == "rule_not_found"

        with SessionLocal() as session:
            stored = session.get(EscalationRule, foreign_rule)
            assert stored.name == "Foreign rule"
            assert stored.tenant_id == "tenant-iso-b"
    finally:
        with SessionLocal() as session:
            session.query(EscalationRule).filter(
                EscalationRule.id == foreign_rule
            ).delete(synchronize_session=False)
            session.commit()


def test_put_rule_requires_an_explicit_tenant(client, admin_token):
    rule_id = _seed_rule("test-tenant", "Own rule")
    response = client.put(
        f"/escalation-rules/{rule_id}",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"name": "no tenant declared"},
    )
    assert response.status_code == 400
    assert response.get_json()["error"] == "missing_tenant_id"


def test_put_rule_rejects_identity_fields_with_400(client, admin_token):
    rule_id = _seed_rule("test-tenant", "Own rule")
    response = client.put(
        f"/escalation-rules/{rule_id}",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"tenant_id": "test-tenant", "id": "rule_stolen"},
    )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_request"


def test_put_rule_updates_own_rule(client, admin_token):
    rule_id = _seed_rule("test-tenant", "Own rule")
    response = client.put(
        f"/escalation-rules/{rule_id}",
        headers={"Authorization": f"Bearer {admin_token}"},
        json={"tenant_id": "test-tenant", "name": "Renamed", "priority": 7},
    )
    assert response.status_code == 200
    body = response.get_json()
    assert body["name"] == "Renamed"
    assert body["priority"] == 7
    assert body["tenant_id"] == "test-tenant"

