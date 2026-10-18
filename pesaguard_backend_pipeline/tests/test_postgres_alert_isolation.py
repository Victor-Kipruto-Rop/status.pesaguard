"""PostgreSQL checks for alert tenant isolation and runtime grants."""

from __future__ import annotations

import os
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError


POSTGRES_TEST_URL = os.getenv("PESAGUARD_POSTGRES_TEST_URL", "")
pytestmark = pytest.mark.skipif(
    os.getenv("PESAGUARD_RUN_POSTGRES_INTEGRATION") != "1"
    or not POSTGRES_TEST_URL.startswith(("postgresql://", "postgres://")),
    reason="set PESAGUARD_RUN_POSTGRES_INTEGRATION=1 and PESAGUARD_POSTGRES_TEST_URL",
)


def _set_tenant(connection, tenant_id: str) -> None:
    connection.execute(
        text("SELECT set_config('app.tenant_id', :tenant_id, true)"),
        {"tenant_id": tenant_id},
    )


def test_alert_runtime_role_is_tenant_scoped_and_cannot_rewrite_history():
    engine = create_engine(POSTGRES_TEST_URL, pool_pre_ping=True)
    tenant_a = f"alert-a-{uuid.uuid4().hex}"
    tenant_b = f"alert-b-{uuid.uuid4().hex}"
    alert_id = f"alert_{uuid.uuid4().hex}"
    event_id = f"event_{uuid.uuid4().hex}"
    delivery_id = f"delivery_{uuid.uuid4().hex}"
    fingerprint = uuid.uuid4().hex * 2
    try:
        with engine.begin() as connection:
            _set_tenant(connection, tenant_a)
            privileges = connection.execute(
                text(
                    "SELECT has_table_privilege(current_user, 'public.alerts', 'DELETE'), "
                    "has_table_privilege(current_user, 'public.alert_events', 'UPDATE'), "
                    "has_table_privilege(current_user, 'public.alert_events', 'DELETE'), "
                    "has_table_privilege(current_user, 'public.alert_deliveries', 'DELETE')"
                )
            ).one()
            assert privileges == (False, False, False, False)

            connection.execute(
                text(
                    "INSERT INTO alerts "
                    "(id, tenant_id, alert_type, severity, status, title, fingerprint) "
                    "VALUES (:id, :tenant_id, 'fraud', 'critical', 'open', 'Test alert', :fingerprint)"
                ),
                {"id": alert_id, "tenant_id": tenant_a, "fingerprint": fingerprint},
            )
            connection.execute(
                text(
                    "INSERT INTO alert_events "
                    "(id, tenant_id, alert_id, event_key, event_type, actor) "
                    "VALUES (:id, :tenant_id, :alert_id, :event_key, 'created', 'integration-test')"
                ),
                {
                    "id": event_id,
                    "tenant_id": tenant_a,
                    "alert_id": alert_id,
                    "event_key": f"create:{event_id}",
                },
            )
            connection.execute(
                text(
                    "INSERT INTO alert_deliveries "
                    "(id, tenant_id, alert_id, channel, idempotency_key) "
                    "VALUES (:id, :tenant_id, :alert_id, 'email', :idempotency_key)"
                ),
                {
                    "id": delivery_id,
                    "tenant_id": tenant_a,
                    "alert_id": alert_id,
                    "idempotency_key": f"delivery:{delivery_id}",
                },
            )
            connection.execute(
                text("UPDATE alerts SET status = 'acknowledged' WHERE id = :id"),
                {"id": alert_id},
            )
            connection.execute(
                text("UPDATE alert_deliveries SET status = 'sent' WHERE id = :id"),
                {"id": delivery_id},
            )

        with engine.begin() as connection:
            _set_tenant(connection, tenant_b)
            assert connection.execute(
                text("SELECT count(*) FROM alerts WHERE id = :id"), {"id": alert_id}
            ).scalar_one() == 0
            assert connection.execute(
                text("SELECT count(*) FROM alert_events WHERE id = :id"), {"id": event_id}
            ).scalar_one() == 0
            assert connection.execute(
                text("SELECT count(*) FROM alert_deliveries WHERE id = :id"),
                {"id": delivery_id},
            ).scalar_one() == 0

        with pytest.raises(DBAPIError):
            with engine.begin() as connection:
                _set_tenant(connection, tenant_b)
                connection.execute(
                    text(
                        "INSERT INTO alerts "
                        "(id, tenant_id, alert_type, severity, status, title, fingerprint) "
                        "VALUES (:id, :tenant_id, 'fraud', 'critical', 'open', 'Cross-tenant', :fingerprint)"
                    ),
                    {
                        "id": f"alert_{uuid.uuid4().hex}",
                        "tenant_id": tenant_a,
                        "fingerprint": uuid.uuid4().hex * 2,
                    },
                )

        with pytest.raises(DBAPIError):
            with engine.begin() as connection:
                _set_tenant(connection, tenant_a)
                connection.execute(
                    text("UPDATE alert_events SET actor = 'mutated' WHERE id = :id"),
                    {"id": event_id},
                )
    finally:
        engine.dispose()
