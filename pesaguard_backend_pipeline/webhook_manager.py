"""
Webhook delivery manager for event-driven notifications in PesaGuard.

Provides secure webhook registration, SSRF defense, HMAC SHA-256 payload signing,
and exponential backoff retries.
"""

from __future__ import annotations

import hashlib
import hmac
import ipaddress
import json
import logging
import re
import secrets
import socket
import ssl
import time
import uuid
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import requests
import urllib3
import dns.exception
import dns.resolver
from urllib3.connection import HTTPSConnection
from urllib3.connectionpool import HTTPSConnectionPool
from urllib3.exceptions import HTTPError as Urllib3HTTPError
from urllib3.util import Timeout
from urllib3.util import connection as urllib3_connection
from sqlalchemy.orm import Session

from models import WebhookConfig, WebhookDelivery

logger = logging.getLogger("pesaguard.webhooks")
_MAX_RESPONSE_BODY_BYTES = 500
_MAX_WEBHOOK_BODY_BYTES = 1_048_576
_MAX_RETRY_ATTEMPTS = 5
_MAX_TIMEOUT_SECONDS = 30
_MAX_RETRY_DELAY_SECONDS = 8
_DNS_RESOLUTION_TIMEOUT_SECONDS = 3.0
_REDIRECT_STATUSES = set(range(300, 400))
_EVENT_TOKEN = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
_EVENT_ID_TOKEN = re.compile(r"^[A-Za-z0-9_.:-]{1,256}$")


def _is_private_or_reserved(ip_str: str) -> bool:
    """Only allow globally routable unicast destinations."""
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True  # Treat unparseable IP as unsafe
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return (
        not ip.is_global
        or ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    )


def _resolve_host_addresses(hostname: str) -> tuple[str, ...]:
    """Resolve all A/AAAA records within one bounded system-DNS deadline."""
    resolver = dns.resolver.Resolver(configure=True)
    deadline = time.monotonic() + _DNS_RESOLUTION_TIMEOUT_SECONDS
    addresses = set()
    for record_type in ("A", "AAAA"):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise dns.exception.Timeout()
        try:
            answers = resolver.resolve(hostname + ".", record_type, lifetime=remaining, search=False)
        except dns.resolver.NoAnswer:
            continue
        for answer in answers:
            addresses.add(answer.address)
    return tuple(sorted(addresses))


def _resolve_webhook_destination(url: str) -> tuple[Optional[str], tuple[str, ...]]:
    """Resolve once and return only addresses safe to pin at connect time."""
    try:
        if not isinstance(url, str) or not url or any(ord(char) <= 32 or ord(char) == 127 for char in url):
            return "url_contains_invalid_characters", ()
        parsed = urlparse(url)
        hostname = parsed.hostname
        parsed_port = parsed.port
        port = 443 if parsed_port is None else parsed_port
    except (TypeError, ValueError):
        return "url_could_not_be_parsed", ()
    if parsed.scheme != "https":
        return "url_must_use_https", ()
    if not hostname:
        return "url_missing_hostname", ()
    if parsed.username is not None or parsed.password is not None:
        return "url_must_not_contain_credentials", ()
    if parsed.fragment:
        return "url_must_not_contain_fragment", ()
    if not 1 <= port <= 65535:
        return "url_has_invalid_port", ()

    hostname = hostname.rstrip(".").lower()
    if (
        hostname in {"localhost", "metadata.google.internal"}
        or hostname.endswith((".localhost", ".local", ".internal"))
    ):
        return "url_targets_reserved_hostname", ()

    try:
        try:
            literal_address = ipaddress.ip_address(hostname)
        except ValueError:
            resolved_addresses = _resolve_host_addresses(hostname)
        else:
            resolved_addresses = (literal_address.compressed,)
    except (dns.exception.DNSException, OSError, UnicodeError, ValueError):
        return "url_hostname_cannot_be_resolved", ()

    addresses = set()
    for resolved_address in resolved_addresses:
        try:
            address = ipaddress.ip_address(resolved_address).compressed
        except (TypeError, ValueError):
            return "url_has_invalid_dns_response", ()
        if _is_private_or_reserved(address):
            return "url_resolves_to_disallowed_address", ()
        addresses.add(address)
    if not addresses:
        return "url_hostname_cannot_be_resolved", ()
    return None, tuple(sorted(addresses))


def _validate_webhook_url(url: str) -> Optional[str]:
    """Return a refusal reason when URL scheme, hostname, or any DNS answer is unsafe."""
    reason, _ = _resolve_webhook_destination(url)
    return reason


class _PinnedHTTPSConnection(HTTPSConnection):
    def __init__(self, *args, pinned_ip: str, **kwargs):
        self._pinned_ip = pinned_ip
        super().__init__(*args, **kwargs)

    def _new_conn(self):
        try:
            return urllib3_connection.create_connection(
                (self._pinned_ip, self.port),
                self.timeout,
                source_address=self.source_address,
                socket_options=self.socket_options,
            )
        except socket.gaierror as exc:
            raise urllib3.exceptions.NameResolutionError(self.host, self, exc) from exc
        except (socket.timeout, TimeoutError) as exc:
            raise urllib3.exceptions.ConnectTimeoutError(
                self, f"Connection to pinned webhook destination timed out."
            ) from exc
        except OSError as exc:
            raise urllib3.exceptions.NewConnectionError(
                self, f"Could not connect to pinned webhook destination: {exc}"
            ) from exc


class _PinnedHTTPSConnectionPool(HTTPSConnectionPool):
    ConnectionCls = _PinnedHTTPSConnection

    def __init__(self, *args, pinned_ip: str, **kwargs):
        super().__init__(*args, pinned_ip=pinned_ip, **kwargs)


class WebhookTransportError(RuntimeError):
    """Outbound connection failure; callers may retry within the configured bound."""


def _post_pinned_webhook(
    url: str,
    body: bytes,
    headers: Dict[str, str],
    pinned_ips: tuple[str, ...],
    timeout_seconds: int,
):
    """POST directly to one vetted IP while retaining the URL hostname for TLS/Host."""
    parsed = urlparse(url)
    hostname = parsed.hostname.rstrip(".").lower() if parsed.hostname else None
    port = 443 if parsed.port is None else parsed.port
    if (
        parsed.scheme != "https"
        or not hostname
        or parsed.username is not None
        or parsed.password is not None
        or not pinned_ips
        or any(_is_private_or_reserved(address) for address in pinned_ips)
    ):
        raise ValueError("Webhook destination was not validated for direct HTTPS.")

    pinned_ip = secrets.choice(pinned_ips)
    pool = _PinnedHTTPSConnectionPool(
        host=hostname,
        port=port,
        pinned_ip=pinned_ip,
        cert_reqs=ssl.CERT_REQUIRED,
        assert_hostname=hostname,
        server_hostname=hostname,
        timeout=timeout_seconds,
        maxsize=1,
        block=True,
    )
    response = None
    try:
        path = parsed.path or "/"
        if parsed.query:
            path = f"{path}?{parsed.query}"
        response = pool.urlopen(
            "POST",
            path,
            body=body,
            headers=headers,
            redirect=False,
            retries=False,
            preload_content=False,
            decode_content=True,
            timeout=Timeout(
                total=timeout_seconds,
                connect=timeout_seconds,
                read=timeout_seconds,
            ),
        )
        response_body = response.read(_MAX_RESPONSE_BODY_BYTES + 1)
        return SimpleNamespace(
            status_code=response.status,
            body=response_body[:_MAX_RESPONSE_BODY_BYTES],
            is_redirect=response.status in _REDIRECT_STATUSES,
        )
    except (urllib3.exceptions.TimeoutError, socket.timeout) as exc:
        raise requests.Timeout("Webhook request exceeded its bounded timeout.") from exc
    except urllib3.exceptions.SSLError:
        raise
    except (Urllib3HTTPError, OSError) as exc:
        raise WebhookTransportError("Webhook connection failed.") from exc
    finally:
        if response is not None:
            response.close()
        pool.close()


def _signature_for_body(secret: str, body: bytes, timestamp: str) -> str:
    digest = hmac.new(
        secret.encode("utf-8"),
        timestamp.encode("ascii") + b"." + body,
        hashlib.sha256,
    ).hexdigest()
    return f"t={timestamp},v1={digest}"


def _reject_nonfinite_json(value: str):
    raise ValueError(f"Non-finite JSON value is not permitted: {value}")


def verify_webhook_signature(
    body: bytes,
    signature_header: str,
    secret: str,
    *,
    now: Optional[int] = None,
    tolerance_seconds: int = 300,
) -> bool:
    """Verify the documented HMAC over exact raw JSON bytes and reject stale or malformed events."""
    if (
        not isinstance(body, bytes)
        or len(body) > _MAX_WEBHOOK_BODY_BYTES
        or not isinstance(signature_header, str)
        or not isinstance(secret, str)
        or not secret
        or not isinstance(tolerance_seconds, int)
        or isinstance(tolerance_seconds, bool)
        or tolerance_seconds < 0
        or tolerance_seconds > 3600
    ):
        return False
    try:
        timestamp_part, signature_part = signature_header.split(",")
        key, timestamp = timestamp_part.split("=", 1)
        signature_key, supplied_digest = signature_part.split("=", 1)
        if key != "t" or signature_key != "v1" or not timestamp.isascii() or not timestamp.isdigit():
            return False
        if len(supplied_digest) != 64 or any(ch not in "0123456789abcdef" for ch in supplied_digest):
            return False
        signed_at = int(timestamp)
        current_time = int(time.time()) if now is None else int(now)
        if abs(current_time - signed_at) > tolerance_seconds:
            return False
        json_value = json.loads(
            body.decode("utf-8"),
            parse_constant=_reject_nonfinite_json,
        )
        if not isinstance(json_value, dict):
            return False
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
        return False
    expected = _signature_for_body(secret, body, timestamp).split("v1=", 1)[1]
    return hmac.compare_digest(supplied_digest, expected)


def _record_retry_metric() -> None:
    try:
        from metrics import record_business_metric

        record_business_metric("webhook_delivery_retry")
    except Exception:
        logger.warning("Unable to record webhook retry metric", exc_info=True)


def _record_delivery_metrics(
    tenant_id: str,
    status: str,
    duration_ms: float,
    retryable_failures: int,
) -> None:
    try:
        from metrics import record_business_metric, record_pipeline_event

        record_business_metric(f"webhook_delivery_{status}")
        record_pipeline_event(
            "succeeded" if status == "success" else "failed",
            tenant_id=tenant_id,
            duration_ms=duration_ms,
        )
        if retryable_failures:
            record_business_metric("webhook_delivery_retryable_failure")
    except Exception:
        logger.warning("Unable to record webhook delivery metrics", exc_info=True)


class WebhookManager:
    """Manages webhook registrations, authorization checks, and payload deliveries."""

    def __init__(self, session: Session, *, lock_service=None):
        self.session = session
        self._lock_service = lock_service
        self.default_timeout = 10
        self.default_max_retries = 3

    def register_webhook(
        self,
        tenant_id: str,
        url: str,
        event_types: List[str],
        retry_attempts: int = 3,
        timeout_seconds: int = 10,
    ) -> Dict[str, Any]:
        """Register a new webhook endpoint for a tenant."""
        if not isinstance(tenant_id, str) or not tenant_id.strip():
            return {"error": "invalid_tenant_id"}
        if not isinstance(event_types, list) or not event_types or any(
            not isinstance(value, str) or not _EVENT_TOKEN.fullmatch(value)
            for value in event_types
        ):
            return {"error": "invalid_event_types"}
        if not isinstance(retry_attempts, int) or isinstance(retry_attempts, bool):
            return {"error": "invalid_retry_attempts"}
        if not isinstance(timeout_seconds, int) or isinstance(timeout_seconds, bool):
            return {"error": "invalid_timeout_seconds"}
        retry_attempts = min(max(retry_attempts, 1), _MAX_RETRY_ATTEMPTS)
        timeout_seconds = min(max(timeout_seconds, 1), _MAX_TIMEOUT_SECONDS)
        rejection_reason = _validate_webhook_url(url)
        if rejection_reason:
            logger.warning("Rejected webhook registration for tenant=%s: %s", tenant_id, rejection_reason)
            return {"error": "invalid_webhook_url", "reason": rejection_reason}

        webhook_id = f"webhook_{uuid.uuid4().hex}"
        signing_secret = f"whsec_{secrets.token_hex(32)}"

        webhook = WebhookConfig(
            id=webhook_id,
            tenant_id=tenant_id,
            url=url,
            event_types=event_types,
            retry_attempts=min(retry_attempts, 5),
            timeout_seconds=min(timeout_seconds, 30),
            active=True,
            signing_secret=signing_secret,
        )
        self.session.add(webhook)
        self.session.commit()
        logger.info("Registered webhook_id=%s for tenant_id=%s", webhook_id, tenant_id)

        return {
            "id": webhook_id,
            "tenant_id": tenant_id,
            "url": url,
            "event_types": event_types,
            "active": True,
            "signing_secret": signing_secret,
        }

    def get_webhooks(self, tenant_id: str, event_type: Optional[str] = None) -> List[WebhookConfig]:
        """Retrieve active webhooks for a tenant, optionally filtered by event_type."""
        query = self.session.query(WebhookConfig).filter(
            WebhookConfig.tenant_id == tenant_id,
            WebhookConfig.active == True,
        )
        webhooks = query.all()
        if event_type:
            webhooks = [w for w in webhooks if event_type in (w.event_types or [])]
        return webhooks

    def trigger_event(
        self,
        tenant_id: str,
        event_type: str,
        payload: Dict[str, Any],
    ) -> Dict[str, Any]:
        """Trigger an event and dispatch to subscribed webhooks."""
        webhooks = self.get_webhooks(tenant_id, event_type)
        results = []

        for webhook in webhooks:
            delivery = self._deliver_webhook(webhook, event_type, payload)
            results.append(delivery)

        return {
            "event_type": event_type,
            "webhooks_triggered": len(webhooks),
            "deliveries": results,
        }

    def _deliver_webhook(
        self,
        webhook: WebhookConfig,
        event_type: str,
        payload: Dict[str, Any],
        *,
        delivery: Optional[WebhookDelivery] = None,
    ) -> Dict[str, Any]:
        """Persist each attempt before I/O and deliver using bounded pinned HTTPS."""
        if not isinstance(payload, dict):
            raise ValueError("Webhook payload must be a JSON object.")
        if not isinstance(event_type, str) or not _EVENT_TOKEN.fullmatch(event_type):
            raise ValueError("Webhook event type has an invalid format.")
        raw_body = json.dumps(
            payload,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
        if len(raw_body) > _MAX_WEBHOOK_BODY_BYTES:
            raise ValueError("Webhook payload exceeds the configured size limit.")
        if delivery is None:
            delivery = WebhookDelivery(
                id=f"delivery_{uuid.uuid4().hex}",
                tenant_id=webhook.tenant_id,
                webhook_id=webhook.id,
                event_type=event_type,
                payload=payload,
                status="pending",
                attempt_count=0,
            )
            self.session.add(delivery)
            self.session.commit()
        elif (
            delivery.tenant_id != webhook.tenant_id
            or delivery.webhook_id != webhook.id
            or delivery.status not in {"pending", "processing"}
        ):
            raise ValueError("Webhook recovery record does not match the active tenant-owned webhook.")

        delivery_id = delivery.id

        started_at = time.monotonic()
        max_attempts = min(max(int(webhook.retry_attempts or self.default_max_retries), 1), _MAX_RETRY_ATTEMPTS)
        timeout = min(max(int(webhook.timeout_seconds or self.default_timeout), 1), _MAX_TIMEOUT_SECONDS)
        rejection_reason, pinned_ips = _resolve_webhook_destination(webhook.url)
        if rejection_reason:
            delivery.status = "failed"
            delivery.response_body = rejection_reason
            self.session.commit()
            _record_delivery_metrics(
                webhook.tenant_id,
                "failed",
                (time.monotonic() - started_at) * 1000,
                0,
            )
            logger.warning(
                "webhook_delivery_blocked webhook_id=%s reason=%s attempts=0",
                webhook.id,
                rejection_reason,
            )
            return {
                "id": delivery_id,
                "status": "failed",
                "attempts": 0,
                "http_requests": 0,
                "retryable_failures": 0,
                "configured_attempts": max_attempts,
                "retryable": False,
                "retries_exhausted": False,
                "response_code": None,
                "reason": rejection_reason,
            }

        if not webhook.signing_secret:
            delivery.status = "failed"
            delivery.response_body = "signing_secret_missing"
            self.session.commit()
            _record_delivery_metrics(
                webhook.tenant_id,
                "failed",
                (time.monotonic() - started_at) * 1000,
                0,
            )
            logger.error("webhook_delivery_blocked webhook_id=%s reason=signing_secret_missing", webhook.id)
            return {
                "id": delivery_id,
                "status": "failed",
                "attempts": 0,
                "http_requests": 0,
                "retryable_failures": 0,
                "configured_attempts": max_attempts,
                "retryable": False,
                "retries_exhausted": False,
                "response_code": None,
                "reason": "signing_secret_missing",
            }

        timestamp_str = str(int(datetime.now(timezone.utc).timestamp()))
        signature_header = _signature_for_body(webhook.signing_secret, raw_body, timestamp_str)
        event_id = payload.get("event_id")
        event_id_text = str(event_id) if isinstance(event_id, (str, int)) and not isinstance(event_id, bool) else ""
        if not _EVENT_ID_TOKEN.fullmatch(event_id_text):
            identity_material = (
                f"{webhook.tenant_id}\0{webhook.id}\0{event_type}\0".encode("utf-8") + raw_body
            )
            event_id_text = hashlib.sha256(identity_material).hexdigest()

        headers = {
            "Content-Type": "application/json",
            "User-Agent": "PesaGuard-Webhook-Dispatcher/2.0",
            "X-Webhook-Event": event_type,
            "X-Webhook-Event-Id": event_id_text,
            "X-Webhook-Timestamp": timestamp_str,
            "X-Webhook-Signature": signature_header,
        }

        http_requests = 0
        retryable_failures = 0
        failure_kind = "request_failed"
        retryable = False
        last_response_code = None
        for attempt in range(delivery.attempt_count, max_attempts):
            delivery.attempt_count = attempt + 1
            delivery.status = "processing"
            delivery.updated_at = datetime.now(timezone.utc)
            self.session.commit()
            http_requests += 1
            try:
                response = _post_pinned_webhook(
                    webhook.url,
                    raw_body,
                    headers,
                    pinned_ips,
                    timeout,
                )
                last_response_code = response.status_code
                delivery.response_status = response.status_code
                delivery.response_body = response.body.decode("utf-8", errors="replace")[:_MAX_RESPONSE_BODY_BYTES]

                if 200 <= response.status_code < 300:
                    delivery.status = "success"
                    delivery.delivered_at = datetime.now(timezone.utc)
                    self.session.commit()
                    duration_ms = (time.monotonic() - started_at) * 1000
                    _record_delivery_metrics(webhook.tenant_id, "success", duration_ms, retryable_failures)
                    logger.info(
                        "webhook_delivery_succeeded webhook_id=%s delivery_id=%s attempts=%d duration_ms=%.2f",
                        webhook.id,
                        delivery_id,
                        attempt + 1,
                        duration_ms,
                    )
                    return {
                        "id": delivery_id,
                        "status": "success",
                        "attempt": attempt + 1,
                        "attempts": attempt + 1,
                        "http_requests": http_requests,
                        "retryable_failures": retryable_failures,
                        "configured_attempts": max_attempts,
                        "retryable": False,
                        "retries_exhausted": False,
                        "response_code": response.status_code,
                    }

                self.session.commit()
                if response.is_redirect:
                    failure_kind = "redirect_rejected"
                    retryable = False
                    break
                if 400 <= response.status_code < 500 and response.status_code != 429:
                    failure_kind = "http_client_error"
                    retryable = False
                    break
                failure_kind = "http_retryable_error"
                retryable = True
                retryable_failures += 1
            except requests.Timeout:
                failure_kind = "timeout"
                retryable = True
                retryable_failures += 1
                delivery.response_status = None
                delivery.response_body = failure_kind
                self.session.commit()
                logger.warning(
                    "webhook_delivery_retryable_failure webhook_id=%s delivery_id=%s attempt=%d kind=timeout",
                    webhook.id,
                    delivery_id,
                    attempt + 1,
                )
            except urllib3.exceptions.SSLError:
                failure_kind = "tls_verification_error"
                retryable = False
                delivery.response_status = None
                delivery.response_body = failure_kind
                self.session.commit()
                logger.error(
                    "webhook_delivery_terminal_failure webhook_id=%s delivery_id=%s "
                    "attempt=%d kind=tls_verification_error",
                    webhook.id,
                    delivery_id,
                    attempt + 1,
                )
            except (WebhookTransportError, Urllib3HTTPError, OSError) as exc:
                failure_kind = "network_error"
                retryable = True
                retryable_failures += 1
                delivery.response_status = None
                delivery.response_body = failure_kind
                self.session.commit()
                logger.warning(
                    "webhook_delivery_retryable_failure webhook_id=%s delivery_id=%s attempt=%d kind=%s",
                    webhook.id,
                    delivery_id,
                    attempt + 1,
                    type(exc).__name__,
                )

            if retryable and attempt < max_attempts - 1:
                wait_seconds = secrets.SystemRandom().uniform(
                    0,
                    min(2 ** attempt, _MAX_RETRY_DELAY_SECONDS),
                )
                _record_retry_metric()
                time.sleep(wait_seconds)
                continue
            break

        delivery.status = "failed"
        delivery.updated_at = datetime.now(timezone.utc)
        self.session.commit()
        duration_ms = (time.monotonic() - started_at) * 1000
        _record_delivery_metrics(webhook.tenant_id, "failed", duration_ms, retryable_failures)
        logger.error(
            "webhook_delivery_terminal_failure webhook_id=%s delivery_id=%s attempts=%d "
            "configured_attempts=%d http_requests=%d retryable_failures=%d kind=%s "
            "response_code=%s duration_ms=%.2f",
            webhook.id,
            delivery_id,
            delivery.attempt_count,
            max_attempts,
            http_requests,
            retryable_failures,
            failure_kind,
            last_response_code,
            duration_ms,
        )

        return {
            "id": delivery_id,
            "status": "failed",
            "attempts": delivery.attempt_count,
            "http_requests": http_requests,
            "retryable_failures": retryable_failures,
            "configured_attempts": max_attempts,
            "retryable": retryable,
            "retries_exhausted": retryable and delivery.attempt_count >= max_attempts,
            "reason": failure_kind,
            "response_code": last_response_code,
        }

    def recover_stale_deliveries(
        self,
        tenant_id: str,
        *,
        max_age_seconds: int = 900,
        batch_size: int = 50,
        now: Optional[datetime] = None,
    ) -> List[Dict[str, Any]]:
        """Resume abandoned durable attempts without exceeding their attempt budget.

        A Redis-backed lock serializes recovery across workers. Records whose
        last persisted attempt consumed the configured budget are moved to a
        terminal, manually reviewable state instead of being replayed blindly.
        """
        if isinstance(max_age_seconds, bool) or not isinstance(max_age_seconds, int) or max_age_seconds < 60:
            raise ValueError("max_age_seconds must be an integer of at least 60 seconds.")
        if isinstance(batch_size, bool) or not isinstance(batch_size, int) or not 1 <= batch_size <= 500:
            raise ValueError("batch_size must be an integer between 1 and 500.")
        if not isinstance(tenant_id, str) or not tenant_id.strip():
            raise ValueError("tenant_id is required for webhook delivery recovery.")

        from distributed_lock import get_distributed_lock

        lock_service = self._lock_service or get_distributed_lock()
        reference = now or datetime.now(timezone.utc)
        cutoff = reference - timedelta(seconds=max_age_seconds)
        deliveries = (
            self.session.query(WebhookDelivery)
            .filter(
                WebhookDelivery.tenant_id == tenant_id,
                WebhookDelivery.status.in_(("pending", "processing")),
                WebhookDelivery.updated_at <= cutoff,
            )
            .order_by(WebhookDelivery.updated_at.asc())
            .limit(batch_size)
            .all()
        )

        outcomes: List[Dict[str, Any]] = []
        for candidate in deliveries:
            lock_name = f"webhook-delivery-recovery:{tenant_id}:{candidate.id}"
            token = lock_service.acquire(lock_name, ttl_seconds=900)
            if token is None:
                outcomes.append({"id": candidate.id, "status": "skipped_locked"})
                continue
            try:
                delivery = (
                    self.session.query(WebhookDelivery)
                    .filter(
                        WebhookDelivery.id == candidate.id,
                        WebhookDelivery.tenant_id == tenant_id,
                        WebhookDelivery.status.in_(("pending", "processing")),
                        WebhookDelivery.updated_at <= cutoff,
                    )
                    .one_or_none()
                )
                if delivery is None:
                    outcomes.append({"id": candidate.id, "status": "skipped_changed"})
                    continue

                webhook = (
                    self.session.query(WebhookConfig)
                    .filter(
                        WebhookConfig.id == delivery.webhook_id,
                        WebhookConfig.tenant_id == delivery.tenant_id,
                    )
                    .one_or_none()
                )
                if webhook is None or not webhook.active or not webhook.signing_secret:
                    reason = "webhook_unavailable_during_recovery"
                    delivery.status = "failed"
                    delivery.response_body = reason
                    delivery.updated_at = datetime.now(timezone.utc)
                    self.session.commit()
                    outcomes.append({"id": delivery.id, "status": "failed", "reason": reason})
                    continue

                max_attempts = min(
                    max(int(webhook.retry_attempts or self.default_max_retries), 1),
                    _MAX_RETRY_ATTEMPTS,
                )
                if delivery.attempt_count >= max_attempts:
                    reason = "recovery_attempt_budget_exhausted"
                    delivery.status = "failed"
                    delivery.response_body = reason
                    delivery.updated_at = datetime.now(timezone.utc)
                    self.session.commit()
                    logger.error(
                        "webhook_delivery_recovery_exhausted tenant_id=%s webhook_id=%s "
                        "delivery_id=%s attempts=%d",
                        delivery.tenant_id,
                        delivery.webhook_id,
                        delivery.id,
                        delivery.attempt_count,
                    )
                    outcomes.append({"id": delivery.id, "status": "failed", "reason": reason})
                    continue

                outcomes.append(
                    self._deliver_webhook(
                        webhook,
                        delivery.event_type,
                        delivery.payload,
                        delivery=delivery,
                    )
                )
            finally:
                lock_service.release(lock_name, token)

        return outcomes

    def _generate_signature(self, webhook: WebhookConfig, payload: Dict[str, Any], timestamp: str) -> str:
        """Generate HMAC SHA-256 signature using the webhook signing secret."""
        secret = getattr(webhook, "signing_secret", None)
        if not secret:
            raise ValueError("Webhook signing secret is missing.")
        raw_body = json.dumps(
            payload,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
        return _signature_for_body(secret, raw_body, timestamp)

    def update_webhook(self, webhook_id: str, tenant_id: str, **kwargs: Any) -> Dict[str, Any]:
        """Update active configuration parameters for a tenant's webhook."""
        webhook = self._get_tenant_webhook(webhook_id, tenant_id)
        if not webhook:
            return {"error": "webhook_not_found"}

        if "url" in kwargs:
            if not isinstance(kwargs["url"], str):
                return {"error": "invalid_webhook_url", "reason": "url_must_be_a_string"}
            rejection_reason = _validate_webhook_url(kwargs["url"])
            if rejection_reason:
                return {"error": "invalid_webhook_url", "reason": rejection_reason}
        if "event_types" in kwargs and (
            not isinstance(kwargs["event_types"], list)
            or not kwargs["event_types"]
            or any(
                not isinstance(value, str) or not _EVENT_TOKEN.fullmatch(value)
                for value in kwargs["event_types"]
            )
        ):
            return {"error": "invalid_event_types"}
        if "retry_attempts" in kwargs:
            value = kwargs["retry_attempts"]
            if not isinstance(value, int) or isinstance(value, bool):
                return {"error": "invalid_retry_attempts"}
            kwargs["retry_attempts"] = min(max(value, 1), _MAX_RETRY_ATTEMPTS)
        if "timeout_seconds" in kwargs:
            value = kwargs["timeout_seconds"]
            if not isinstance(value, int) or isinstance(value, bool):
                return {"error": "invalid_timeout_seconds"}
            kwargs["timeout_seconds"] = min(max(value, 1), _MAX_TIMEOUT_SECONDS)
        if "active" in kwargs and not isinstance(kwargs["active"], bool):
            return {"error": "invalid_active"}
        if kwargs.get("active") is True and not webhook.signing_secret:
            return {"error": "signing_secret_required"}

        for key in {"url", "event_types", "active", "retry_attempts", "timeout_seconds"}:
            if key in kwargs:
                setattr(webhook, key, kwargs[key])

        self.session.commit()
        return {
            "id": webhook_id,
            "url": webhook.url,
            "active": webhook.active,
            "event_types": webhook.event_types,
        }

    def rotate_signing_secret(self, webhook_id: str, tenant_id: str) -> Dict[str, Any]:
        """Rotate a tenant-owned webhook secret and disclose the replacement once."""
        webhook = self._get_tenant_webhook(webhook_id, tenant_id)
        if webhook is None:
            return {"error": "webhook_not_found"}
        replacement = f"whsec_{secrets.token_hex(32)}"
        webhook.signing_secret = replacement
        webhook.active = True
        self.session.commit()
        return {"status": "rotated", "id": webhook_id, "signing_secret": replacement}

    def revoke_signing_secret(self, webhook_id: str, tenant_id: str) -> Dict[str, Any]:
        """Revoke the secret and disable the webhook until an authorized rotation."""
        webhook = self._get_tenant_webhook(webhook_id, tenant_id)
        if webhook is None:
            return {"error": "webhook_not_found"}
        webhook.signing_secret = None
        webhook.active = False
        self.session.commit()
        return {"status": "revoked", "id": webhook_id}

    def _get_tenant_webhook(self, webhook_id: str, tenant_id: str) -> Optional[WebhookConfig]:
        return (
            self.session.query(WebhookConfig)
            .filter(WebhookConfig.id == webhook_id, WebhookConfig.tenant_id == tenant_id)
            .first()
        )

    def delete_webhook(self, webhook_id: str, tenant_id: str) -> Dict[str, Any]:
        """Delete a registered webhook for a tenant."""
        webhook = (
            self.session.query(WebhookConfig)
            .filter(WebhookConfig.id == webhook_id, WebhookConfig.tenant_id == tenant_id)
            .first()
        )
        if not webhook:
            return {"error": "webhook_not_found"}

        self.session.delete(webhook)
        self.session.commit()
        return {"status": "deleted", "id": webhook_id}

    def get_delivery_history(self, webhook_id: str, tenant_id: str, limit: int = 50) -> List[Dict[str, Any]]:
        """Retrieve recent delivery logs for a tenant's webhook."""
        owner_check = (
            self.session.query(WebhookConfig)
            .filter(WebhookConfig.id == webhook_id, WebhookConfig.tenant_id == tenant_id)
            .first()
        )
        if not owner_check:
            return []

        deliveries = (
            self.session.query(WebhookDelivery)
            .filter(
                WebhookDelivery.webhook_id == webhook_id,
                WebhookDelivery.tenant_id == tenant_id,
            )
            .order_by(WebhookDelivery.created_at.desc())
            .limit(limit)
            .all()
        )
        return [
            {
                "id": d.id,
                "event_type": d.event_type,
                "status": d.status,
                "attempt_count": d.attempt_count,
                "response_status": d.response_status,
                "created_at": d.created_at.isoformat() if d.created_at else None,
                "delivered_at": d.delivered_at.isoformat() if d.delivered_at else None,
            }
            for d in deliveries
        ]
