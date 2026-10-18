"""Tests for fraud/anomaly indicators, risk priority, investigation workflow,
false-positive tracking, and auditability (checklist item 13).

Engine-level tests exercise rule triggers, explanations, correlation, and
priority mapping. DB-backed tests verify idempotent RiskEvent emission,
tenant isolation, the investigation state machine, audit trail, and metrics.
"""

from __future__ import annotations

import importlib

import pytest

from fraud_risk_engine import assess_transaction, evaluate_rules, calculate_features, risk_priority
from risk_indicators import (
    InvalidInvestigationTransition,
    RiskEventNotFound,
    emit_risk_events,
    false_positive_metrics,
    investigation_audit_trail,
    record_outcome,
    start_investigation,
)


def tx(identifier, *, amount="1000", customer="254700000001", merchant="600000",
       when="20260914100000", status="COMPLETED", reference=None):
    payload = {
        "TransID": identifier,
        "TransAmount": amount,
        "MSISDN": customer,
        "BusinessShortCode": merchant,
        "TransTime": when,
        "status": status,
    }
    if reference is not None:
        payload["BillRefNumber"] = reference
    return payload


# ---------------------------------------------------------------------------
# 1. Rule-based suspicious-pattern detection
# ---------------------------------------------------------------------------

def test_normal_transaction_triggers_no_indicators():
    decision = assess_transaction(tx("clean-1"), [tx("clean-0", when="20260913090000")])
    assert decision.rules_triggered == ()
    assert decision.rule_reasons == {}
    assert decision.risk_level == "LOW"


def test_velocity_and_amount_rules_are_explainable():
    history = [tx(f"v-{i}", when="20260914100000") for i in range(5)]
    decision = assess_transaction(tx("v-current", amount="300000"), history)
    assert "velocity" in decision.rules_triggered
    assert "amount_threshold" in decision.rules_triggered
    assert decision.rule_reasons["velocity"]
    assert decision.rule_reasons["amount_threshold"]


# ---------------------------------------------------------------------------
# 2. Repeated transaction reference alerts
# ---------------------------------------------------------------------------

def test_repeated_reference_alerts_when_shared_across_transactions():
    history = [tx(f"r-{i}", reference="INV-20418") for i in range(3)]
    decision = assess_transaction(tx("r-current", reference="INV-20418"), history)
    assert "repeated_reference" in decision.rules_triggered
    assert "3 other distinct" in decision.rule_reasons["repeated_reference"]


def test_unique_references_do_not_alert():
    history = [tx(f"u-{i}", reference=f"INV-{i}") for i in range(3)]
    decision = assess_transaction(tx("u-current", reference="INV-99"), history)
    assert "repeated_reference" not in decision.rules_triggered


def test_missing_references_never_alert():
    history = [tx(f"n-{i}") for i in range(5)]
    decision = assess_transaction(tx("n-current"), history)
    assert "repeated_reference" not in decision.rules_triggered


# ---------------------------------------------------------------------------
# 3. Unusual amount thresholds
# ---------------------------------------------------------------------------

def test_unusual_amount_threshold_rule_fires_with_reason():
    decision = assess_transaction(tx("amt", amount="500000"), [tx("amt-0", amount="1000")])
    assert "amount_threshold" in decision.rules_triggered
    assert "threshold" in decision.rule_reasons["amount_threshold"]


def test_amount_below_threshold_does_not_fire():
    decision = assess_transaction(tx("amt-low", amount="500"), [tx("amt-low-0", amount="1000")])
    assert "amount_threshold" not in decision.rules_triggered


# ---------------------------------------------------------------------------
# 4. Unusual transaction frequency
# ---------------------------------------------------------------------------

def test_high_frequency_same_customer_fires_velocity():
    history = [tx(f"f-{i}", when="20260914100000") for i in range(6)]
    decision = assess_transaction(tx("f-current", when="20260914100000"), history)
    assert "velocity" in decision.rules_triggered


# ---------------------------------------------------------------------------
# 5. Repeated processing failure patterns
# ---------------------------------------------------------------------------

def test_repeated_processing_failures_fire_pattern_rule():
    history = [
        tx(f"pf-{i}", status="FAILED", when="20260913090000") for i in range(3)
    ]
    decision = assess_transaction(tx("pf-current", when="20260914100000"), history)
    assert "processing_failure_pattern" in decision.rules_triggered
    assert "failures" in decision.rule_reasons["processing_failure_pattern"]


def test_isolated_failures_do_not_fire_pattern_rule():
    history = [
        tx("pf-single", status="FAILED", when="20260913090000"),
        tx("pf-ok-1", status="COMPLETED", when="20260913091000"),
        tx("pf-ok-2", status="COMPLETED", when="20260913092000"),
    ]
    decision = assess_transaction(tx("pf-current", when="20260914100000"), history)
    assert "processing_failure_pattern" not in decision.rules_triggered


def test_failures_for_other_customer_do_not_fire():
    history = [
        tx(
            f"pf-other-{i}",
            customer="254700000099",
            merchant="600099",
            status="FAILED",
            when="20260913090000",
        )
        for i in range(4)
    ]
    decision = assess_transaction(tx("pf-mine", when="20260914100000"), history)
    assert "processing_failure_pattern" not in decision.rules_triggered


# ---------------------------------------------------------------------------
# 6. Cross-event correlation (justified only)
# ---------------------------------------------------------------------------

def test_correlated_indicators_fire_when_families_are_independent():
    # velocity + amount + unusual-time are three independent families.
    history = [
        tx(f"c-{i}", amount="299000", when="20260914025959", status="COMPLETED")
        for i in range(5)
    ]
    decision = assess_transaction(tx("c-current", amount="300000", when="20260914030000"), history)
    assert "correlated_indicators" in decision.rules_triggered
    assert "families correlated" in decision.rule_reasons["correlated_indicators"]


def test_correlated_indicators_do_not_fire_for_single_family():
    # duplicate_activity + repeated_reference share the "reference" family only.
    history = [
        tx("same-id", amount="100", reference="INV-1"),
        tx("s-1", amount="100", reference="INV-1"),
        tx("s-2", amount="100", reference="INV-1"),
        tx("s-3", amount="100", reference="INV-1"),
    ]
    decision = assess_transaction(tx("same-id", amount="100", reference="INV-1"), history)
    assert "duplicate_activity" in decision.rules_triggered
    assert "repeated_reference" in decision.rules_triggered
    assert "correlated_indicators" not in decision.rules_triggered


# ---------------------------------------------------------------------------
# 7. Explainable detection reasons
# ---------------------------------------------------------------------------

def test_every_triggered_rule_has_a_reason():
    history = [tx(f"e-{i}", amount="299000", when="20260914025959") for i in range(5)]
    decision = assess_transaction(tx("e-current", amount="300000", when="20260914030000"), history)
    assert decision.rules_triggered
    for code in decision.rules_triggered:
        assert decision.rule_reasons.get(code), f"missing reason for {code}"
    payload = decision.as_dict()
    assert set(payload["rule_reasons"].keys()) >= set(decision.rules_triggered)
    assert payload["priority"]


# ---------------------------------------------------------------------------
# 8. Risk-priority rules
# ---------------------------------------------------------------------------

def test_risk_priority_maps_levels_and_escalates_on_correlation():
    assert risk_priority("LOW") == "P4"
    assert risk_priority("MEDIUM") == "P3"
    assert risk_priority("HIGH") == "P2"
    assert risk_priority("CRITICAL") == "P1"
    # Correlated indicators escalate one step, capped at P1.
    assert risk_priority("LOW", ["correlated_indicators"]) == "P3"
    assert risk_priority("HIGH", ["correlated_indicators"]) == "P1"
    assert risk_priority("CRITICAL", ["correlated_indicators"]) == "P1"


def test_risk_priority_rejects_unknown_level():
    with pytest.raises(ValueError):
        risk_priority("BOGUS")


# ---------------------------------------------------------------------------
# DB-backed: RiskEvent emission, investigation workflow, audit, metrics
# ---------------------------------------------------------------------------

@pytest.fixture()
def api_app(tmp_path, monkeypatch):
    database_url = f"sqlite:///{tmp_path / 'fraud_indicators.db'}"
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("PESAGUARD_API_AUTH_REQUIRED", "0")
    monkeypatch.setenv("JWT_SECRET_KEY", "fraud-indicators-test-secret-0123456789")

    import app as app_module

    importlib.reload(app_module)
    app_module.app.config["TESTING"] = True

    event_store = app_module.event_store
    event_store._ensure_ready()

    yield app_module.app

    event_store.engine.dispose()


@pytest.fixture()
def client(api_app):
    return api_app.test_client()


def _headers(tenant_id: str = "tenant-a") -> dict:
    return {"X-Tenant-ID": tenant_id}


def _suspicious_decision():
    history = [tx(f"d-{i}", amount="299000", when="20260914025959") for i in range(5)]
    return assess_transaction(tx("d-current", amount="300000", when="20260914030000"), history)


def test_emit_risk_events_is_explainable_and_idempotent(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    decision = _suspicious_decision()
    with store.Session() as session:
        created = emit_risk_events(
            session, tenant_id="tenant-a", decision=decision, transaction_id="tx-emit-1"
        )
        session.commit()
        assert created
        for event in created:
            evidence = event.evidence
            assert evidence["reason"]
            assert evidence["rule_code"] in set(decision.rules_triggered)
            assert evidence["priority"] in {"P1", "P2", "P3", "P4"}
            assert "254700000001" not in str(evidence)  # PII-minimised

    with store.Session() as session:
        again = emit_risk_events(
            session, tenant_id="tenant-a", decision=decision, transaction_id="tx-emit-1"
        )
        session.commit()
        assert again == []  # idempotent while events are still open
        from models import RiskEvent

        count = session.query(RiskEvent).filter_by(transaction_id="tx-emit-1").count()
        assert count == len(created)


def test_emit_risk_events_is_tenant_scoped(api_app):
    from event_store import EventStore
    from models import RiskEvent

    store = EventStore()
    store._ensure_ready()
    decision = _suspicious_decision()
    with store.Session() as session:
        emit_risk_events(session, tenant_id="tenant-a", decision=decision, transaction_id="tx-iso")
        session.commit()
    with store.Session() as session:
        a = session.query(RiskEvent).filter_by(tenant_id="tenant-a", transaction_id="tx-iso").count()
        b = session.query(RiskEvent).filter_by(tenant_id="tenant-b", transaction_id="tx-iso").count()
        assert a > 0
        assert b == 0


def test_investigation_workflow_and_outcome_auditability(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    decision = _suspicious_decision()
    with store.Session() as session:
        created = emit_risk_events(
            session, tenant_id="tenant-a", decision=decision, transaction_id="tx-inv-1"
        )
        session.commit()
        event_id = created[0].id

    with store.Session() as session:
        event = start_investigation(
            session, tenant_id="tenant-a", event_id=event_id, actor="analyst-1", note="triaging"
        )
        session.commit()
        assert event.status == "investigating"
        assert event.evidence["investigation"]["started_by"] == "analyst-1"

    with store.Session() as session:
        event = record_outcome(
            session,
            tenant_id="tenant-a",
            event_id=event_id,
            actor="analyst-1",
            outcome="confirmed_fraud",
            note="confirmed by provider",
        )
        session.commit()
        assert event.status == "resolved"
        assert event.resolved_at is not None
        assert event.evidence["investigation"]["outcome"] == "confirmed_fraud"

    with store.Session() as session:
        trail = investigation_audit_trail(session, tenant_id="tenant-a", event_id=event_id)
        assert [e["event_type"] for e in trail] == [
            "risk_event.investigation_started",
            "risk_event.outcome_recorded",
        ]
        assert all(e["actor"] == "analyst-1" for e in trail)

    # Terminal events are immutable.
    with store.Session() as session:
        with pytest.raises(InvalidInvestigationTransition):
            record_outcome(
                session,
                tenant_id="tenant-a",
                event_id=event_id,
                actor="analyst-2",
                outcome="false_positive",
            )
        with pytest.raises(InvalidInvestigationTransition):
            start_investigation(session, tenant_id="tenant-a", event_id=event_id, actor="analyst-2")


def test_investigation_is_tenant_scoped(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    decision = _suspicious_decision()
    with store.Session() as session:
        created = emit_risk_events(
            session, tenant_id="tenant-a", decision=decision, transaction_id="tx-iso-2"
        )
        session.commit()
        event_id = created[0].id

    with store.Session() as session:
        with pytest.raises(RiskEventNotFound):
            start_investigation(session, tenant_id="tenant-b", event_id=event_id, actor="analyst-x")
        with pytest.raises(RiskEventNotFound):
            record_outcome(
                session,
                tenant_id="tenant-b",
                event_id=event_id,
                actor="analyst-x",
                outcome="false_positive",
            )


def test_false_positive_tracking_metrics(api_app):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    decision = _suspicious_decision()
    with store.Session() as session:
        created = emit_risk_events(
            session, tenant_id="tenant-a", decision=decision, transaction_id="tx-fp-1"
        )
        session.commit()
        ids = [event.id for event in created]

    with store.Session() as session:
        record_outcome(
            session,
            tenant_id="tenant-a",
            event_id=ids[0],
            actor="analyst-1",
            outcome="false_positive",
            note="legit promotion",
        )
        session.commit()

    with store.Session() as session:
        metrics = false_positive_metrics(session, "tenant-a")
        assert metrics["total_events"] == len(ids)
        assert metrics["false_positives"] == 1
        assert metrics["by_outcome"]["false_positive"] == 1
        assert metrics["pending"] == len(ids) - 1
        assert 0.0 < metrics["false_positive_rate"] <= 1.0
        other = false_positive_metrics(session, "tenant-b")
        assert other["total_events"] == 0


def _seed_risk_event(client):
    from event_store import EventStore

    store = EventStore()
    store._ensure_ready()
    decision = _suspicious_decision()
    with store.Session() as session:
        created = emit_risk_events(
            session, tenant_id="tenant-a", decision=decision, transaction_id="tx-api-1"
        )
        session.commit()
        return created[0].id


def test_risk_event_investigation_api(client):
    event_id = _seed_risk_event(client)

    response = client.post(
        f"/api/v1/risk-events/{event_id}/investigation",
        headers=_headers(),
        json={"note": "analyst review"},
    )
    assert response.status_code == 200
    assert response.get_json()["status"] == "investigating"
    assert "X-Correlation-ID" in response.headers

    response = client.post(
        f"/api/v1/risk-events/{event_id}/resolution",
        headers=_headers(),
        json={"outcome": "not_a_real_outcome"},
    )
    assert response.status_code == 400
    assert response.get_json()["error"] == "invalid_outcome"

    response = client.post(
        f"/api/v1/risk-events/{event_id}/resolution",
        headers=_headers(),
        json={"outcome": "false_positive", "note": "verified clean"},
    )
    assert response.status_code == 200
    body = response.get_json()
    assert body["status"] == "dismissed"
    assert body["priority"] in {"P1", "P2", "P3", "P4"}

    # Terminal events reject further transitions.
    response = client.post(
        f"/api/v1/risk-events/{event_id}/resolution",
        headers=_headers(),
        json={"outcome": "confirmed_fraud"},
    )
    assert response.status_code == 409
    assert response.get_json()["error"] == "invalid_transition"

    response = client.get(f"/api/v1/risk-events/{event_id}/audit", headers=_headers())
    assert response.status_code == 200
    items = response.get_json()["items"]
    assert [entry["event_type"] for entry in items] == [
        "risk_event.investigation_started",
        "risk_event.outcome_recorded",
    ]

    response = client.get("/api/v1/risk-events/metrics", headers=_headers())
    assert response.status_code == 200
    metrics = response.get_json()
    assert metrics["false_positives"] == 1
    assert metrics["by_status"]["dismissed"] == 1

    # Tenant isolation: cross-tenant access yields 404, never another tenant's data.
    response = client.get(f"/api/v1/risk-events/{event_id}", headers=_headers("tenant-b"))
    assert response.status_code == 404
    response = client.post(
        f"/api/v1/risk-events/{event_id}/resolution",
        headers=_headers("tenant-b"),
        json={"outcome": "confirmed_fraud"},
    )
    assert response.status_code == 404
