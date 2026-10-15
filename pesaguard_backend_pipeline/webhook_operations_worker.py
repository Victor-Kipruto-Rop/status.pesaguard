"""Run tenant-scoped webhook recovery and health checks once.

Invoke periodically from a supervised worker or scheduler. Tenant IDs are
enumerated only through the least-privilege tenant registry connection; all
business-table operations run inside transaction-local tenant scope.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
from typing import Any

from environment import required_env
from sqlalchemy.orm import sessionmaker

from database_service import create_database_engine
from escalation_engine import EscalationEngine
from tenant_context import tenant_scope
from tenant_registry import list_active_tenant_ids
from webhook_manager import WebhookManager

logger = logging.getLogger("pesaguard.webhook_operations")


def run_once(*, batch_size: int = 50, max_age_seconds: int = 900) -> list[dict[str, Any]]:
    if not 1 <= batch_size <= 500:
        raise ValueError("batch_size must be between 1 and 500.")
    if not 60 <= max_age_seconds <= 3600:
        raise ValueError("max_age_seconds must be between 60 and 3600.")

    database_url = required_env("DATABASE_URL")
    tenants = list_active_tenant_ids()
    engine = create_database_engine(database_url)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    outcomes = []

    try:
        for tenant_id in tenants:
            try:
                with tenant_scope(tenant_id):
                    with session_factory() as session:
                        recovered = WebhookManager(session).recover_stale_deliveries(
                            tenant_id,
                            max_age_seconds=max_age_seconds,
                            batch_size=batch_size,
                        )
                        health = EscalationEngine(session).check_webhook_health(tenant_id)
                        session.commit()

                        summary = {
                            "tenant_id": tenant_id,
                            "recovery_count": len(recovered),
                            "recovery_outcomes": recovered,
                            "health": health,
                        }
                        outcomes.append(summary)
                        logger.info(
                            "webhook_operations_tick tenant_id=%s recoveries=%d health_status=%s",
                            tenant_id,
                            len(recovered),
                            health.get("status"),
                        )
            except Exception:
                logger.exception("webhook_operations_tenant_failed tenant_id=%s", tenant_id)
                outcomes.append({"tenant_id": tenant_id, "status": "error"})
    finally:
        engine.dispose()
    return outcomes


def main() -> int:
    parser = argparse.ArgumentParser(description="Recover and monitor outbound webhook deliveries once.")
    parser.add_argument("--batch-size", type=int, default=50, help="Maximum stale deliveries per tenant.")
    parser.add_argument(
        "--max-age-seconds",
        type=int,
        default=int(os.getenv("WEBHOOK_RECOVERY_STALE_SECONDS", "900")),
        help="Minimum age before a pending delivery is eligible for recovery.",
    )
    args = parser.parse_args()
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    try:
        result = run_once(batch_size=args.batch_size, max_age_seconds=args.max_age_seconds)
    except Exception:
        logger.exception("webhook_operations_tick_failed")
        return 1
    print(json.dumps(result, sort_keys=True, default=str))
    return 1 if any(outcome.get("status") == "error" for outcome in result) else 0


if __name__ == "__main__":
    raise SystemExit(main())
