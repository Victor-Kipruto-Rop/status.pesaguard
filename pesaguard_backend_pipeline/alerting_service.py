"""Robust multi-channel alerting service module for PesaGuard discrepancy dispatch."""

from __future__ import annotations

import json
import logging
import os
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from sqlalchemy.orm import Session

from alert_center import (
    configured_alert_email_recipients,
    delivery_for,
    queue_alert_email_deliveries,
    record_alert,
)
from models import Alert, AlertDelivery
from models import Discrepancy
from notifier import is_alert_channel_configured, send_email_alert, send_slack_alert, send_sms_alert

try:
    from metrics import record_alert_delivery
except Exception:  # pragma: no cover - metrics must never break alerting
    def record_alert_delivery(channel: str, success: bool) -> None:  # type: ignore[misc]
        return None

logger = logging.getLogger("pesaguard.alerting.service")


class AlertingService:
    """Manages multi-channel discrepancy alert routing, severity filters, deduplication, and persistence."""

    def __init__(self, session: Optional[Session] = None, tenant_settings: Optional[Dict[str, Any]] = None):
        self.session = session
        self.tenant_settings = tenant_settings or {}
        # In-memory deduplication cache. For horizontal scaling across multiple containers,
        # this should be backed by Redis or a database constraint; kept thread-safe or local here.
        self._alert_ids: set[str] = set()
        try:
            self.max_delivery_attempts = max(1, min(20, int(os.getenv("PESAGUARD_ALERT_MAX_ATTEMPTS", "6"))))
        except ValueError:
            self.max_delivery_attempts = 6

    def handle_discrepancy(self, discrepancy: Dict[str, Any]) -> Dict[str, Any]:
        """Process an incoming discrepancy notification, route to configured channels, and log outcomes."""
        if not isinstance(discrepancy, dict):
            logger.error("Invalid discrepancy format encountered; expected dictionary, got %s", type(discrepancy))
            return {"status": "failed", "reason": "invalid_discrepancy_format"}

        alert_id = str(discrepancy.get("id") or discrepancy.get("trans_id") or uuid.uuid4())
        tenant_id = str(discrepancy.get("tenant_id") or "default")
        severity = str(discrepancy.get("severity") or "warning").lower()
        channels = self._resolve_channels(severity)
        locale = self._resolve_locale(discrepancy)
        delivery_mode = self._resolve_delivery_mode(severity, channels)
        deliveries: List[Dict[str, Any]] = []

        alert = None
        channel_delivery_rows: dict[str, AlertDelivery] = {}
        created = True
        replay = False
        if self.session is not None:
            alert, created, replay = record_alert(
                self.session,
                tenant_id=tenant_id,
                payload=discrepancy,
            )
            if not created and not replay:
                self.session.commit()
                logger.info(
                    "Duplicate alert event recorded and delivery suppressed: tenant_id=%s alert_id=%s replay=%s",
                    tenant_id,
                    alert.id,
                    replay,
                )
                return {
                    "status": "deduped",
                    "alert_id": alert.id,
                    "deliveries": [],
                    "delivery_mode": "deduped",
                }

            if delivery_mode == "realtime":
                for channel in channels:
                    if channel == "email":
                        if created:
                            email_rows = queue_alert_email_deliveries(
                                self.session,
                                alert=alert,
                                tenant_settings=self.tenant_settings,
                            )
                            if not email_rows:
                                email_rows = [delivery_for(
                                    self.session,
                                    alert=alert,
                                    channel="email",
                                    idempotency_key=f"{alert.id}:email:not-configured",
                                    status="not_configured",
                                )]
                            channel_delivery_rows[channel] = email_rows[0]
                        else:
                            # The outbox owns provider retries. This call only repairs a
                            # failed enqueue or adds recipients after email is configured.
                            email_rows = queue_alert_email_deliveries(
                                self.session,
                                alert=alert,
                                tenant_settings=self.tenant_settings,
                            )
                            email_row = email_rows[0] if email_rows else self.session.query(AlertDelivery).filter_by(
                                tenant_id=alert.tenant_id,
                                alert_id=alert.id,
                                channel="email",
                            ).order_by(AlertDelivery.created_at.asc()).first()
                            if email_row is not None:
                                channel_delivery_rows[channel] = email_row
                    else:
                        channel_delivery_rows[channel] = delivery_for(
                            self.session,
                            alert=alert,
                            channel=channel,
                            idempotency_key=f"{alert.id}:{channel}",
                        )
            self.session.commit()
        else:
            # Stateless callers are used by local tooling; production workers inject a DB session.
            dedup_key = f"{tenant_id}:{alert_id}"
            if dedup_key in self._alert_ids:
                return {"status": "deduped", "alert_id": alert_id, "deliveries": [], "delivery_mode": "deduped"}
            self._alert_ids.add(dedup_key)

        if delivery_mode == "digest":
            self._store_delivery_log(alert_id, discrepancy, deliveries)
            logger.info("Discrepancy queued for digest delivery mode: alert_id=%s", alert_id)
            return {"status": "queued", "alert_id": alert.id if alert else alert_id, "deliveries": deliveries, "delivery_mode": "digest"}

        # Dispatch real-time notifications across resolved channels
        for channel in channels:
            row = channel_delivery_rows.get(channel) if alert is not None else None
            attempts_before_send = int(row.attempt_count or 0) if row is not None else 0
            try:
                if channel == "email" and alert is not None:
                    if row is None:
                        continue
                    record_alert_delivery(
                        channel,
                        row.status in {"queued", "accepted", "sent", "delivered"},
                    )
                    deliveries.append({"channel": channel, "status": row.status})
                    continue
                if alert is not None and row is not None:
                    ready, reason = self._claim_delivery(row, replay=replay)
                    if not ready:
                        deliveries.append({
                            "channel": channel,
                            "status": row.status,
                            **({"error": reason} if reason else {}),
                        })
                        continue
                    if not is_alert_channel_configured(channel):
                        row.status = "not_configured"
                        row.error_code = "ALERT_CHANNEL_NOT_CONFIGURED"
                        row.last_error = None
                        row.updated_at = datetime.now(timezone.utc)
                        self._append_delivery_event(alert, row, "alert.delivery_not_configured")
                        self.session.commit()
                        deliveries.append({"channel": channel, "status": row.status, "error_code": row.error_code})
                        record_alert_delivery(channel, False)
                        continue
                    attempt_limit = self._attempt_limit(row)
                    remaining_attempts = attempt_limit - int(row.attempt_count or 0)
                    if remaining_attempts <= 0:
                        row.status = "dead_letter"
                        row.error_code = "ALERT_MAX_ATTEMPTS_EXHAUSTED"
                        row.updated_at = datetime.now(timezone.utc)
                        self._append_delivery_event(alert, row, "alert.delivery_dead_lettered")
                        self.session.commit()
                        deliveries.append({"channel": channel, "status": row.status, "error_code": row.error_code})
                        continue
                    attempt_observer = self._attempt_observer(row)
                    send_retries = min(
                        remaining_attempts - 1,
                        max(0, int(os.getenv("ALERT_SLACK_RETRIES" if channel == "slack" else "ALERT_SMS_RETRIES", "2"))),
                    )
                else:
                    attempt_observer = None
                    send_retries = None
                if channel == "slack":
                    sender_options = {"max_retries": send_retries} if send_retries is not None else {}
                    if attempt_observer:
                        sender_options["on_attempt"] = attempt_observer
                    delivered = bool(send_slack_alert(discrepancy, locale=locale, **sender_options))
                elif channel == "sms":
                    sender_options = {"max_retries": send_retries} if send_retries is not None else {}
                    if attempt_observer:
                        sender_options["on_attempt"] = attempt_observer
                    delivered = bool(send_sms_alert(discrepancy, locale=locale, **sender_options))
                elif channel == "email":
                    delivered = bool(send_email_alert(discrepancy, locale=locale))
                else:
                    logger.warning("Unrecognized notification channel requested: %s", channel)
                    record_alert_delivery(channel, False)
                    deliveries.append({"channel": channel, "status": "failed", "error": "unsupported_channel"})
                    continue

                record_alert_delivery(channel, delivered)
                delivery_status = "sent" if delivered else "failed"
                if alert is not None:
                    row = channel_delivery_rows[channel]
                    if int(row.attempt_count or 0) <= attempts_before_send:
                        row.attempt_count = attempts_before_send + 1
                    exhausted = not delivered and int(row.attempt_count or 0) >= self._attempt_limit(row)
                    row.status = "sent" if delivered else "dead_letter" if exhausted else "retrying"
                    row.error_code = None if delivered else "ALERT_MAX_ATTEMPTS_EXHAUSTED" if exhausted else "ALERT_DELIVERY_FAILED"
                    row.last_error = None if delivered else "Provider did not confirm delivery."
                    row.updated_at = datetime.now(timezone.utc)
                    delivery_status = row.status
                    if exhausted:
                        self._append_delivery_event(alert, row, "alert.delivery_dead_lettered")
                    else:
                        self._append_delivery_event(alert, row, "alert.delivery_sent" if delivered else "alert.delivery_retrying" if row.status == "retrying" else "alert.delivery_failed")
                deliveries.append({"channel": channel, "status": delivery_status, "timestamp": datetime.now(timezone.utc).isoformat()})
                if delivered:
                    logger.info("Alert delivered successfully via channel=%s alert_id=%s", channel, alert_id)
                else:
                    logger.warning("Alert delivery reported failure via channel=%s alert_id=%s", channel, alert_id)

            except Exception as exc:
                record_alert_delivery(channel, False)
                logger.exception("Alert delivery failed for channel=%s alert_id=%s: %s", channel, alert_id, exc)
                delivery_status = "failed"
                if alert is not None and channel in channel_delivery_rows:
                    row = channel_delivery_rows[channel]
                    if int(row.attempt_count or 0) <= attempts_before_send:
                        row.attempt_count = attempts_before_send + 1
                    exhausted = int(row.attempt_count or 0) >= self._attempt_limit(row)
                    row.status = "dead_letter" if exhausted else "retrying"
                    row.error_code = "ALERT_MAX_ATTEMPTS_EXHAUSTED" if exhausted else "ALERT_DELIVERY_FAILED"
                    row.last_error = str(exc)[:1000]
                    row.updated_at = datetime.now(timezone.utc)
                    delivery_status = row.status
                    self._append_delivery_event(alert, row, "alert.delivery_dead_lettered" if exhausted else "alert.delivery_retrying")
                deliveries.append({"channel": channel, "status": delivery_status, "error": str(exc)[:1000]})

        if self.session is not None:
            self.session.commit()
        self._store_delivery_log(alert_id, discrepancy, deliveries)
        failed = any(delivery.get("status") in {"failed", "retrying"} for delivery in deliveries)
        return {"status": "failed" if failed else "queued", "alert_id": alert.id if alert else alert_id, "deliveries": deliveries, "delivery_mode": delivery_mode}

    def _attempt_observer(self, row: AlertDelivery | None):
        """Persist a channel attempt before its network side effect starts."""
        if row is None or self.session is None:
            return None

        base_attempts = int(row.attempt_count or 0)

        def persist(attempt_number: int) -> None:
            row.status = "processing"
            row.attempt_count = base_attempts + attempt_number
            row.updated_at = datetime.now(timezone.utc)
            row.error_code = None
            row.last_error = None
            alert = self.session.query(Alert).filter_by(tenant_id=row.tenant_id, id=row.alert_id).one()
            self._append_delivery_event(
                alert,
                row,
                "alert.delivery_attempted",
                details={"attempt": row.attempt_count},
            )
            self.session.commit()

        return persist

    def _claim_delivery(self, row: AlertDelivery, *, replay: bool) -> tuple[bool, str | None]:
        """Claim a pending or failed channel delivery without resending successful work."""
        locked_row = self.session.query(AlertDelivery).filter_by(
            tenant_id=row.tenant_id,
            id=row.id,
        ).with_for_update().populate_existing().one()
        if locked_row.status in {"sent", "accepted", "delivered", "not_configured", "dead_letter", "available"}:
            return False, None
        if not replay and locked_row.status != "queued":
            return False, None
        if locked_row.status == "processing":
            updated_at = locked_row.updated_at
            if updated_at.tzinfo is None:
                updated_at = updated_at.replace(tzinfo=timezone.utc)
            if (datetime.now(timezone.utc) - updated_at).total_seconds() < 60:
                return False, "delivery_in_progress"
        if locked_row.status not in {"queued", "retrying", "failed", "processing"}:
            return False, None
        if int(locked_row.attempt_count or 0) >= self._attempt_limit(locked_row):
            locked_row.status = "dead_letter"
            locked_row.error_code = "ALERT_MAX_ATTEMPTS_EXHAUSTED"
            locked_row.updated_at = datetime.now(timezone.utc)
            alert = self.session.query(Alert).filter_by(tenant_id=locked_row.tenant_id, id=locked_row.alert_id).one()
            self._append_delivery_event(alert, locked_row, "alert.delivery_dead_lettered")
            self.session.commit()
            return False, None
        locked_row.status = "processing"
        locked_row.updated_at = datetime.now(timezone.utc)
        self.session.commit()
        return True, None

    def _attempt_limit(self, row: AlertDelivery) -> int:
        """Bound retries per generation while keeping the lifetime count monotonic."""
        return self.max_delivery_attempts * (int(row.retry_generation or 0) + 1)

    def _append_delivery_event(
        self,
        alert,
        row: AlertDelivery,
        event_type: str,
        *,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        from alert_center import append_alert_event

        append_alert_event(
            self.session,
            alert,
            event_type=event_type,
            actor="system",
            details={"channel": row.channel, "delivery_id": row.id, **(details or {})},
        )

    def _resolve_channels(self, severity: str) -> List[str]:
        """Determine valid notification channels based on severity level and tenant settings."""
        configured = self.tenant_settings.get("alert_channels")
        if configured is None:
            configured = ["slack"]
            if configured_alert_email_recipients(self.tenant_settings):
                configured.append("email")
        if not isinstance(configured, list):
            configured = ["slack"]

        if severity == "critical":
            return [channel for channel in configured if channel in {"slack", "sms", "email"}]
        if severity == "warning":
            return [channel for channel in configured if channel in {"slack", "email"}]
        if severity == "info":
            return [channel for channel in configured if channel == "slack"]
        return []

    def _resolve_delivery_mode(self, severity: str, channels: List[str]) -> str:
        """Determine whether alerts should be dispatched immediately or batched into a digest."""
        if severity == "info":
            return "digest"
        if channels:
            return "realtime"
        return "digest"

    def _resolve_locale(self, discrepancy: Dict[str, Any]) -> str:
        """Resolve localized string preference for templates per tenant/user context."""
        tenant_id = str(discrepancy.get("tenant_id", "default"))
        user_id = discrepancy.get("user_id")

        if hasattr(self.tenant_settings, "resolve_locale") and callable(self.tenant_settings.resolve_locale):
            try:
                return str(self.tenant_settings.resolve_locale(tenant_id, user_id, fallback_locale="en"))
            except Exception as e:
                logger.error("Custom resolve_locale method failed: %s", e)

        if isinstance(self.tenant_settings, dict):
            tenant_cfg = self.tenant_settings.get(tenant_id) or self.tenant_settings.get("default") or {}
            
            if user_id and isinstance(tenant_cfg.get("user_locale_overrides"), dict):
                override = tenant_cfg["user_locale_overrides"].get(user_id) or tenant_cfg["user_locale_overrides"].get(str(user_id))
                if override:
                    return str(override)

            preferred_locale = tenant_cfg.get("preferred_locale") or tenant_cfg.get("default_locale")
            if preferred_locale:
                return str(preferred_locale)

            default_settings = self.tenant_settings.get("default") or {}
            if default_settings.get("preferred_locale"):
                return str(default_settings["preferred_locale"])

        return "en"

    def _store_delivery_log(self, alert_id: str, discrepancy: Dict[str, Any], deliveries: List[Dict[str, Any]]) -> None:
        """Persist alert delivery metrics and event records to the database safely."""
        if self.session is None:
            return

        try:
            trans_id = str(discrepancy.get("trans_id", "unknown"))
            tenant_id = str(discrepancy.get("tenant_id", "default"))
            severity = str(discrepancy.get("severity") or "warning").lower()
            if severity not in {"info", "warning", "critical"}:
                severity = "warning"
            anomaly_type = str(discrepancy.get("status") or discrepancy.get("anomaly_type") or "alert")

            log_entry = Discrepancy(
                # Tenant-scoped primary key (DAO convention) so identical alert
                # ids from different tenants can never collide on one row.
                id=f"{tenant_id}:alert-{alert_id}",
                trans_id=trans_id,
                tenant_id=tenant_id,
                anomaly_type=anomaly_type,
                # ck_discrepancy_status allows only reviewed workflow states.
                status="needs_review",
                severity=severity,
                details=json.dumps({
                    "alert_id": alert_id,
                    "deliveries": deliveries,
                    "anomaly_type": anomaly_type[:128],
                    "transaction_id": trans_id[:255],
                    "logged_at": datetime.now(timezone.utc).isoformat(),
                }),
                resolved=False,
            )
            existing = self.session.query(Discrepancy).filter_by(id=log_entry.id, tenant_id=tenant_id).one_or_none()
            if existing is None:
                self.session.add(log_entry)
            else:
                existing.details = log_entry.details
                existing.severity = log_entry.severity
                existing.status = log_entry.status
                existing.resolved = log_entry.resolved
            self.session.commit()
            logger.info("Successfully stored delivery audit log for alert_id=%s", alert_id)
        except Exception as exc:
            logger.exception("Failed to store delivery log for alert_id=%s: %s", alert_id, exc)
            if self.session:
                self.session.rollback()
