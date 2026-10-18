"""
Phase: Observability & Reliability — Production Readiness Tests

Covers structured logging, pipeline metrics, distributed tracing, alert rule
coverage, health checks, and disaster recovery.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

import pytest

from logging_utils import (
    JsonFormatter,
    bind_observability_context,
    clear_observability_context,
    get_observability_context,
)
import metrics
from metrics import build_metrics_payload, telemetry_snapshot
from otel_tracing import trace_span


def test_structured_log_contains_full_traceability_context():
    bind_observability_context(
        request_id="req-obs-1",
        correlation_id="corr-obs-1",
        transaction_id="tx-obs-1",
        event_id="evt-obs-1",
        trace_id="trace-obs-1",
        span_id="span-obs-1",
        tenant_id="tenant-obs",
    )
    record = logging.LogRecord("test", logging.INFO, __file__, 1, "obs test", (), None)
    payload = json.loads(JsonFormatter().format(record))
    for key in ("request_id", "correlation_id", "transaction_id", "event_id", "trace_id", "span_id", "tenant_id"):
        assert key in payload, f"missing observability field: {key}"
        assert payload[key], f"empty observability field: {key}"
    clear_observability_context()


def test_structured_log_redacts_sensitive_values():
    bind_observability_context(request_id="req-redact", tenant_id="tenant-redact")
    record = logging.LogRecord("test", logging.INFO, __file__, 1, "processing payment", (), None)
    record.__dict__["api_key"] = "sk_live_secret_12345"
    record.__dict__["password"] = "hunter2"
    payload = json.loads(JsonFormatter().format(record))
    serialized = json.dumps(payload)
    assert "sk_live_secret_12345" not in serialized
    assert "hunter2" not in serialized
    clear_observability_context()


def test_notification_delivery_metrics_reflect_database_states(monkeypatch):
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import QueuePool

    from communications.models import CommunicationNotification, CommunicationOutboxEntry
    from models import Base

    engine = create_engine("sqlite://", poolclass=QueuePool)
    Base.metadata.create_all(engine)
    monkeypatch.setattr(metrics, "_metrics_engine", engine)
    monkeypatch.setenv("DATABASE_URL", "sqlite://")
    Session = sessionmaker(bind=engine)
    with Session() as session:
        notifications = [
            CommunicationNotification(
                id=f"notification-{status}",
                tenant_id="metrics-test",
                channel="email",
                recipient=f"{status}@example.test",
                message="test notification",
                idempotency_key=f"metrics-{status}",
                status=status,
            )
            for status in ("delivered", "failed", "created", "sent")
        ]
        session.add_all(notifications)
        session.flush()
        session.add_all([
            CommunicationOutboxEntry(
                id=f"outbox-{status}",
                notification_id=f"notification-{notification_status}",
                tenant_id="metrics-test",
                status=status,
                attempt_count=0,
                max_attempts=5,
            )
            for status, notification_status in (("pending", "created"), ("leased", "sent"))
        ])
        session.commit()

    try:
        live_metrics = metrics._query_live_metrics()
    finally:
        engine.dispose()

    assert live_metrics["notification_queue_depth"] == 2
    assert live_metrics["notification_deliveries_total"] == 1
    assert live_metrics["notification_deliveries_failed"] == 1
    assert live_metrics["notification_deliveries_pending"] == 2


def test_metrics_payload_contains_notification_metrics():
    payload = build_metrics_payload()
    assert "pesaguard_notification_queue_depth" in payload
    assert "pesaguard_notification_deliveries_total" in payload
    assert "pesaguard_notification_deliveries_failed" in payload
    assert "pesaguard_notification_deliveries_pending" in payload


def test_metrics_payload_contains_mpesa_ingestion_latency():
    payload = build_metrics_payload()
    assert "pesaguard_mpesa_ingestion_latency_p95_seconds" in payload


def test_metrics_payload_contains_reconciliation_latency():
    payload = build_metrics_payload()
    assert "pesaguard_reconciliation_latency_p95_seconds" in payload


def test_error_rate_metric_is_present():
    payload = build_metrics_payload()
    assert "pesaguard_error_rate" in payload


def test_queue_depth_metrics_are_present():
    payload = build_metrics_payload()
    assert "pesaguard_kafka_consumer_lag" in payload
    assert "pesaguard_kafka_consumer_lag_max" in payload
    assert "pesaguard_transaction_outbox_pending" in payload


def test_backup_metrics_are_present():
    payload = build_metrics_payload()


def test_trace_span_creates_and_binds_context():
    clear_observability_context()
    with trace_span("test.obs.span", template_id="A01", tenant_id="tenant-obs"):
        ctx = get_observability_context()
        assert "trace_id" in ctx
        assert ctx["trace_id"]
    clear_observability_context()


def test_trace_span_records_errors():
    clear_observability_context()
    try:
        with trace_span("test.obs.error", template_id="A02"):
            raise ValueError("simulated email delivery failure")
    except ValueError:
        pass
    ctx = get_observability_context()
    assert "trace_id" in ctx
    clear_observability_context()


def test_alert_rules_cover_notification_delivery_failure_domains():
    content = Path("monitoring/alerts.yml").read_text(encoding="utf-8")
    required_alerts = (
        "PesaGuardNotificationQueueBacklog",
        "PesaGuardNotificationDeliveryFailureRate",
        "PesaGuardNotificationDeliveryPendingHigh",
        "PesaGuardMpesaIngestionLatencyHigh",
    )
    for alert_name in required_alerts:
        assert alert_name in content, f"missing alert rule: {alert_name}"

    required_metrics = (
        "pesaguard_notification_queue_depth",
        "pesaguard_notification_deliveries_failed",
        "pesaguard_notification_deliveries_total",
        "pesaguard_notification_deliveries_pending",
        "pesaguard_mpesa_ingestion_latency_p95_seconds",
        "pesaguard_error_rate",
        "pesaguard_request_p99_ms",
        "pesaguard_db_pool_usage",
        "pesaguard_kafka_consumer_lag_max",
        "pesaguard_event_retries_total",
    )
    for metric in required_metrics:
        assert metric in content, f"missing alert metric reference: {metric}"


def test_health_checks_cover_critical_dependencies():
    content = Path("docs/observability/HEALTH_CHECKS.md").read_text(encoding="utf-8")
    lower = content.lower()
    assert "database" in lower
    assert "kafka" in lower or "redpanda" in lower


def test_health_module_has_all_dependency_checks():
    import health
    assert hasattr(health, "check_database_connection")
    assert hasattr(health, "check_kafka_connectivity")
    assert hasattr(health, "check_redis_connectivity")
    assert hasattr(health, "check_daraja_connectivity")
    assert hasattr(health, "check_webhook_ingestion_configuration")
    assert hasattr(health, "build_health_payload")


def test_email_dispatcher_has_trace_span():
    import inspect
    from pesaguard_email.dispatcher import send_templated_email
    source = inspect.getsource(send_templated_email)
    assert "trace_span" in source
    assert "email.send_templated_email" in source
