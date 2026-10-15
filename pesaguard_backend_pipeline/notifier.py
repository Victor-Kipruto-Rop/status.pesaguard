"""
Multi-channel alerting engine for PesaGuard discrepancy notifications.

Supports Slack webhooks, Africa's Talking SMS, and SMTP Email dispatch with configurable
exponential backoff retries, localized templates (English/Swahili), and structured logs.
"""

from __future__ import annotations

import json
import logging
import math
import os
import smtplib
import time
from email.message import EmailMessage
from typing import Any, Dict, Optional
from urllib.parse import urlsplit

import requests

try:
    from africas_talking import AfricasTalkingClient
    HAS_AFRICAS_TALKING = True
except ImportError:
    HAS_AFRICAS_TALKING = False

from alert_template_loader import load_alert_fields, render_message_template
from localization_utils import format_ke_currency, format_ke_datetime, normalise_locale

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


def send_slack_alert(discrepancy: Dict[str, Any], locale: str = "en", max_retries: int = SLACK_RETRIES) -> bool:
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


def send_sms_alert(discrepancy: Dict[str, Any], locale: str = "en", max_retries: int = SMS_RETRIES) -> bool:
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
    """Send alert via SMTP with retries on network failures.

    Args:
        discrepancy: Discrepancy anomaly dictionary
        locale: Message locale
        max_retries: Maximum retry attempts

    Returns:
        True if delivered successfully, False otherwise
    """
    recipients = os.getenv("ALERT_EMAIL_RECIPIENTS")
    trans_id = discrepancy.get("trans_id", "unknown")
    tenant_id = discrepancy.get("tenant_id", "default")

    if not recipients or not SMTP_HOST:
        logger.debug("Email SMTP host or recipient configuration missing; skipping email dispatch.")
        return False

    message_text = _format_alert_text(discrepancy, locale=locale)
    msg = EmailMessage()
    msg["Subject"] = f"PesaGuard Alert: {str(discrepancy.get('severity', 'alert')).upper()} - {trans_id}"
    msg["From"] = EMAIL_FROM
    msg["To"] = recipients
    msg.set_content(message_text)

    for attempt in range(max_retries + 1):
        try:
            with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=5) as s:
                if SMTP_USER and SMTP_PASS:
                    s.starttls()
                    s.login(SMTP_USER, SMTP_PASS)
                s.send_message(msg)
            logger.info("Email alert sent successfully trans_id=%s recipients=%s", trans_id, recipients)
            return True
        except smtplib.SMTPException as e:
            if attempt < max_retries:
                backoff = RETRY_BACKOFF_SECONDS * (2 ** attempt)
                logger.warning(
                    "Email alert delivery failed (attempt %d/%d): %s. Retrying in %.1fs...",
                    attempt + 1, max_retries + 1, e, backoff
                )
                time.sleep(backoff)
            else:
                logger.error("Email alert delivery failed after %d attempts: %s", max_retries + 1, e)
                return False
        except Exception as e:
            logger.exception("Unexpected error sending email alert trans_id=%s: %s", trans_id, e)
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
