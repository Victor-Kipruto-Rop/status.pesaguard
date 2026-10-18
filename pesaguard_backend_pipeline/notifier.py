"""
Multi-channel alerting engine for PesaGuard discrepancy notifications.

Supports Slack webhooks, Africa's Talking SMS, and SMTP Email dispatch with configurable
exponential backoff retries, localized templates (English/Swahili), and structured logs.
"""

from __future__ import annotations

import json
import hashlib
import logging
import math
import os
import smtplib
import time
from email.message import EmailMessage
from typing import Any, Callable, Dict, Optional
from urllib.parse import urlsplit

import requests

try:
    from africas_talking import AfricasTalkingClient
    HAS_AFRICAS_TALKING = True
except ImportError:
    HAS_AFRICAS_TALKING = False

from alert_template_loader import load_alert_fields, render_message_template
from email_addresses import EMAIL_ADDRESSES
from email_branding import render_branded_email_html
from localization_utils import format_ke_currency, format_ke_datetime, normalise_locale
from resend_email import ResendAPIError, ResendEmailClient

logger = logging.getLogger("pesaguard.alerting")

# Environment configurations
SLACK_WEBHOOK_URL = os.getenv("SLACK_WEBHOOK_URL", "")
SMS_RECIPIENT = os.getenv("SMS_ALERT_RECIPIENT", "")
EMAIL_FROM = os.getenv("ALERT_EMAIL_FROM", "no-reply@pesaguard.co.ke")
SMTP_HOST = os.getenv("ALERT_SMTP_HOST")
SMTP_PORT = int(os.getenv("ALERT_SMTP_PORT", "25"))
SMTP_USER = os.getenv("ALERT_SMTP_USER")
SMTP_PASS = os.getenv("ALERT_SMTP_PASS")

# Retry configuration defaults
SLACK_RETRIES = int(os.getenv("ALERT_SLACK_RETRIES", "2"))
SMS_RETRIES = int(os.getenv("ALERT_SMS_RETRIES", "2"))
EMAIL_RETRIES = int(os.getenv("ALERT_EMAIL_RETRIES", "3"))
RETRY_BACKOFF_SECONDS = float(os.getenv("ALERT_RETRY_BACKOFF_SECONDS", "1.0"))

sms_client = AfricasTalkingClient() if HAS_AFRICAS_TALKING else None


def _is_allowed_slack_webhook_url(value: str) -> bool:
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        return False
    return (
        parsed.scheme == "https"
        and parsed.hostname is not None
        and parsed.hostname.lower() == "hooks.slack.com"
        and port in {None, 443}
        and parsed.username is None
        and parsed.password is None
        and parsed.path.startswith("/services/")
        and not parsed.query
        and not parsed.fragment
    )


def is_alert_channel_configured(channel: str) -> bool:
    """Return whether a direct alert channel can make a delivery attempt."""
    if channel == "slack":
        return bool(SLACK_WEBHOOK_URL and _is_allowed_slack_webhook_url(SLACK_WEBHOOK_URL))
    if channel == "sms":
        return bool(SMS_RECIPIENT and sms_client)
    return False


def _slack_retry_delay_seconds(
    attempt: int,
    status_code: Optional[int] = None,
    retry_after: Optional[str] = None,
) -> float:
    if status_code == 429 and retry_after:
        try:
            requested_delay = float(retry_after)
        except ValueError:
            requested_delay = -1
        if math.isfinite(requested_delay) and requested_delay >= 0:
            return min(requested_delay, 300)
    configured_delay = RETRY_BACKOFF_SECONDS * (2 ** min(attempt, 10))
    return max(0, min(configured_delay, 300))


def send_slack_alert(
    discrepancy: Dict[str, Any],
    locale: str = "en",
    max_retries: int = SLACK_RETRIES,
    on_attempt: Callable[[int], None] | None = None,
) -> bool:
    """Send alert payload to Slack via webhook with exponential retries.

    Args:
        discrepancy: Discrepancy anomaly dictionary
        locale: Message locale code ('en' or 'sw')
        max_retries: Maximum retry attempts on network or retryable HTTP errors

    Returns:
        True if delivered successfully, False otherwise
    """
    if max_retries < 0:
        raise ValueError("max_retries must not be negative")
    if not SLACK_WEBHOOK_URL:
        logger.warning("SLACK_WEBHOOK_URL is not set; skipping Slack alert dispatch for trans_id=%s", discrepancy.get("trans_id"))
        return False
    if not _is_allowed_slack_webhook_url(SLACK_WEBHOOK_URL):
        logger.error("SLACK_WEBHOOK_URL must use the approved HTTPS Slack webhook endpoint")
        return False

    trans_id = discrepancy.get("trans_id", "unknown")
    tenant_id = discrepancy.get("tenant_id", "default")

    text = _format_slack_alert_text(discrepancy, locale=locale)
    body = json.dumps({"text": text}).encode("utf-8")

    for attempt in range(max_retries + 1):
        if on_attempt is not None:
            on_attempt(attempt + 1)
        try:
            with requests.post(
                SLACK_WEBHOOK_URL,
                data=body,
                headers={"Content-Type": "application/json"},
                timeout=5,
                allow_redirects=False,
            ) as resp:
                if resp.status_code == 200:
                    logger.info("Slack alert sent successfully trans_id=%s tenant_id=%s", trans_id, tenant_id)
                    return True
                if resp.status_code != 429 and resp.status_code < 500:
                    logger.error("Slack alert rejected with status %d", resp.status_code)
                    return False
                if attempt >= max_retries:
                    logger.error(
                        "Slack alert delivery failed after %d attempts with status %d",
                        max_retries + 1,
                        resp.status_code,
                    )
                    return False
                backoff = _slack_retry_delay_seconds(
                    attempt,
                    resp.status_code,
                    resp.headers.get("Retry-After"),
                )
                logger.warning(
                    "Slack alert delivery failed with status %d (attempt %d/%d). Retrying in %.1fs.",
                    resp.status_code,
                    attempt + 1,
                    max_retries + 1,
                    backoff,
                )
            time.sleep(backoff)
        except requests.RequestException as e:
            if attempt >= max_retries:
                logger.error("Slack alert delivery failed after %d attempts: %s", max_retries + 1, e)
                return False
            backoff = _slack_retry_delay_seconds(attempt)
            logger.warning(
                "Slack alert delivery failed (attempt %d/%d): %s. Retrying in %.1fs...",
                attempt + 1, max_retries + 1, e, backoff
            )
            time.sleep(backoff)

    return False


def send_sms_alert(
    discrepancy: Dict[str, Any],
    locale: str = "en",
    max_retries: int = SMS_RETRIES,
    on_attempt: Callable[[int], None] | None = None,
) -> bool:
    """Send alert via Africa's Talking SMS API with exponential retries.

    Args:
        discrepancy: Discrepancy anomaly dictionary
        locale: Message locale
        max_retries: Maximum retry attempts

    Returns:
        True if delivered successfully, False otherwise
    """
    recipient = os.getenv("SMS_ALERT_RECIPIENT", SMS_RECIPIENT)
    trans_id = discrepancy.get("trans_id", "unknown")
    tenant_id = discrepancy.get("tenant_id", "default")

    if not recipient:
        logger.debug("SMS_ALERT_RECIPIENT not configured; skipping SMS dispatch.")
        return False

    if not sms_client:
        logger.error("Africa's Talking client unavailable (package not installed or uninitialized).")
        return False

    message = _format_sms_alert_text(discrepancy, locale=locale)

    for attempt in range(max_retries + 1):
        if on_attempt is not None:
            on_attempt(attempt + 1)
        try:
            sms_client.send_sms(recipient, message)
            logger.info("SMS alert sent successfully trans_id=%s recipient=%s", trans_id, recipient)
            return True
        except Exception as e:
            if attempt < max_retries:
                backoff = RETRY_BACKOFF_SECONDS * (2 ** attempt)
                logger.warning(
                    "SMS alert delivery failed (attempt %d/%d): %s. Retrying in %.1fs...",
                    attempt + 1, max_retries + 1, e, backoff
                )
                time.sleep(backoff)
            else:
                logger.error("SMS alert delivery failed after %d attempts: %s", max_retries + 1, e)
                return False

    return False


def send_email_alert(discrepancy: Dict[str, Any], locale: str = "en", max_retries: int = EMAIL_RETRIES) -> bool:
    """Send an alert through configured Resend or explicitly enabled SMTP.

    Args:
        discrepancy: Discrepancy anomaly dictionary
        locale: Message locale
        max_retries: Maximum retry attempts

    Returns:
        True if delivered successfully, False otherwise
    """
    if max_retries < 0:
        raise ValueError("max_retries must not be negative")
    recipients = os.getenv("ALERT_EMAIL_RECIPIENTS")
    trans_id = discrepancy.get("trans_id", "unknown")
    tenant_id = discrepancy.get("tenant_id", "default")

    if not recipients:
        logger.debug("Alert email recipients are not configured; skipping email dispatch.")
        return False

    message_text = _format_alert_text(discrepancy, locale=locale)
    subject = f"PesaGuard Alert: {str(discrepancy.get('severity', 'alert')).upper()} - {trans_id}"
    email_provider = os.getenv("PESAGUARD_EMAIL_PROVIDER", "resend").strip().lower()
    recipient_list = [value.strip() for value in recipients.split(",") if value.strip()]
    if not recipient_list:
        logger.warning("Alert email recipient configuration is empty")
        return False

    if email_provider == "resend":
        alert_fingerprint = json.dumps(
            {
                "id": discrepancy.get("id"),
                "trans_id": trans_id,
                "severity": discrepancy.get("severity"),
                "status": discrepancy.get("status"),
                "anomalies": discrepancy.get("anomalies"),
                "anomaly_type": discrepancy.get("anomaly_type"),
                "detected_at": discrepancy.get("detected_at") or discrepancy.get("checked_at"),
                "amount": discrepancy.get("amount") or discrepancy.get("amount_value"),
            },
            sort_keys=True,
            default=str,
            separators=(",", ":"),
        )
        client = ResendEmailClient()
        for attempt in range(max_retries + 1):
            try:
                for recipient in recipient_list:
                    idempotency_key = hashlib.sha256(
                        f"{tenant_id}:{alert_fingerprint}:{recipient.casefold()}".encode("utf-8")
                    ).hexdigest()
                    client.send_message({
                        "to": recipient,
                        "from_email": EMAIL_ADDRESSES.alerts,
                        "reply_to": EMAIL_ADDRESSES.incidents,
                        "subject": subject,
                        "text": message_text,
                        "preheader": "Review the discrepancy alert and its current status.",
                        "idempotency_key": idempotency_key,
                    })
                logger.info(
                    "Resend alert accepted trans_id=%s tenant_id=%s recipient_count=%d",
                    trans_id,
                    tenant_id,
                    len(recipient_list),
                )
                return True
            except ResendAPIError as exc:
                if not exc.retryable or attempt >= max_retries:
                    logger.error(
                        "Resend alert delivery failed code=%s retryable=%s",
                        exc.code,
                        exc.retryable,
                    )
                    return False
            except Exception as exc:
                if attempt >= max_retries:
                    logger.error("Resend alert delivery failed error_type=%s", type(exc).__name__)
                    return False
            backoff = RETRY_BACKOFF_SECONDS * (2 ** attempt)
            logger.warning(
                "Resend alert delivery retry attempt=%d/%d delay_seconds=%.1f",
                attempt + 1,
                max_retries + 1,
                backoff,
            )
            time.sleep(backoff)
        return False

    if email_provider != "smtp":
        logger.error("Unsupported PESAGUARD_EMAIL_PROVIDER for alert email")
        return False
    if not SMTP_HOST:
        logger.debug("SMTP host is not configured; skipping email dispatch.")
        return False

    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = EMAIL_FROM
    msg["To"] = recipients
    msg.set_content(message_text)
    msg.add_alternative(
        render_branded_email_html(
            subject,
            text_body=message_text,
            preheader="Review the discrepancy alert and its current status.",
        ),
        subtype="html",
    )

    for attempt in range(max_retries + 1):
        try:
            with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=5) as s:
                if SMTP_USER and SMTP_PASS:
                    s.starttls()
                    s.login(SMTP_USER, SMTP_PASS)
                s.send_message(msg)
            logger.info("Email alert accepted recipient_count=%d", len(recipient_list))
            return True
        except smtplib.SMTPException as exc:
            if attempt < max_retries:
                backoff = RETRY_BACKOFF_SECONDS * (2 ** attempt)
                logger.warning(
                    "Email alert delivery failed (attempt %d/%d, error_type=%s). Retrying in %.1fs...",
                    attempt + 1, max_retries + 1, type(exc).__name__, backoff
                )
                time.sleep(backoff)
            else:
                logger.error(
                    "Email alert delivery failed after %d attempts (error_type=%s)",
                    max_retries + 1,
                    type(exc).__name__,
                )
                return False
        except Exception as exc:
            logger.error(
                "Unexpected email alert failure trans_id=%s error_type=%s",
                trans_id,
                type(exc).__name__,
            )
            return False

    return False


def _template_context(discrepancy: Dict[str, Any]) -> Dict[str, Any]:
    anomalies = discrepancy.get("anomalies", [])
    amount = discrepancy.get("amount") or discrepancy.get("amount_value")
    return {
        "tenant_name": discrepancy.get("tenant_name") or discrepancy.get("tenant_id") or "default",
        "anomaly_type": ", ".join(anomalies) if isinstance(anomalies, list) else discrepancy.get("anomaly_type") or "discrepancy",
        "amount": format_ke_currency(amount) if amount is not None else "N/A",
        "summary": ", ".join(anomalies) if isinstance(anomalies, list) else discrepancy.get("status") or "needs_review",
        "trans_id": discrepancy.get("trans_id", discrepancy.get("TransID", "unknown")),
        "severity": discrepancy.get("severity", "warning"),
        "status": discrepancy.get("status", "needs_review"),
    }


def _format_sms_alert_text(discrepancy: Dict[str, Any], locale: str = "en") -> str:
    locale_code = normalise_locale(locale)
    template_name = f"sms_template_{locale_code}.md"
    rendered = render_message_template(template_name, _template_context(discrepancy))
    if rendered:
        return rendered
    return _format_alert_text(discrepancy, locale=locale)


def _format_slack_alert_text(discrepancy: Dict[str, Any], locale: str = "en") -> str:
    locale_code = normalise_locale(locale)
    template_name = f"slack_template_{locale_code}.md"
    rendered = render_message_template(template_name, _template_context(discrepancy))
    if rendered:
        return rendered
    return _format_alert_text(discrepancy, locale=locale)


def _format_alert_text(discrepancy: Dict[str, Any], locale: str = "en") -> str:
    trans_id = discrepancy.get("trans_id", discrepancy.get("TransID", "unknown"))
    anomalies = discrepancy.get("anomalies", [])
    severity = discrepancy.get("severity", "warning")
    status = discrepancy.get("status", "needs_review")
    locale_code = normalise_locale(locale)
    template = load_alert_fields(locale_code)
    
    issues = ", ".join(anomalies) if isinstance(anomalies, list) and anomalies else (discrepancy.get("anomaly_type") or template.get("no_issues", "None"))
    detected_at = discrepancy.get("checked_at") or discrepancy.get("detected_at") or discrepancy.get("timestamp") or discrepancy.get("created_at") or "unknown"
    detected_at_display = format_ke_datetime(detected_at) or str(detected_at)
    
    amount = discrepancy.get("amount") or discrepancy.get("amount_value")
    amount_line = ""
    if amount is not None:
        amount_line = f"\n{template.get('amount', 'Amount')}: {format_ke_currency(amount)}"

    return (
        f":rotating_light: {template.get('title', 'PesaGuard Alert')}\n"
        f"{template.get('transaction', 'Transaction')}: `{trans_id}`\n"
        f"{template.get('severity', 'Severity')}: `{severity}`\n"
        f"{template.get('status', 'Status')}: `{status}`\n"
        f"{template.get('issues', 'Issues')}: {issues}\n"
        f"{template.get('detected_at', 'Detected At')}: {detected_at_display}"
        f"{amount_line}"
    )
