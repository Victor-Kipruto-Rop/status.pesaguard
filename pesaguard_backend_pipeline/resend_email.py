from __future__ import annotations

import os
import re
from email.utils import parseaddr
from typing import Any

import requests

from email_addresses import is_pesaguard_email_address
from email_branding import render_branded_email_html


class ResendAPIError(Exception):
    def __init__(self, message: str, *, code: str, retryable: bool):
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class ResendEmailClient:
    """Small, bounded Resend REST client; provider response bodies are never exposed."""

    endpoint = "https://api.resend.com/emails"
    timeout_seconds = 10
    max_idempotency_key_length = 256

    def __init__(self, api_key: str | None = None, *, http_client: Any | None = None):
        self.api_key = (api_key if api_key is not None else os.environ.get("RESEND_API_KEY", "")).strip()
        self.http_client = http_client or requests

    @staticmethod
    def _validate_address(value: Any, *, sender: bool = False) -> str:
        if not isinstance(value, str) or "\r" in value or "\n" in value:
            raise ResendAPIError("email address is invalid", code="INVALID_EMAIL", retryable=False)
        _, address = parseaddr(value)
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", address):
            raise ResendAPIError("email address is invalid", code="INVALID_EMAIL", retryable=False)
        if sender and not is_pesaguard_email_address(value):
            raise ResendAPIError("sender must use the verified PesaGuard domain", code="INVALID_SENDER", retryable=False)
        return value.strip()

    def send_message(self, message: dict[str, Any]) -> dict[str, str]:
        if not self.api_key:
            raise ResendAPIError("Resend is not configured", code="RESEND_NOT_CONFIGURED", retryable=False)

        recipient = self._validate_address(message.get("to"))
        sender = self._validate_address(message.get("from_email"), sender=True)
        subject = message.get("subject")
        if not isinstance(subject, str) or not subject.strip() or "\r" in subject or "\n" in subject:
            raise ResendAPIError("email subject is invalid", code="INVALID_EMAIL", retryable=False)

        payload: dict[str, Any] = {
            "from": sender,
            "to": [recipient],
            "subject": subject.strip(),
        }
        for source, destination in (("html", "html"), ("text", "text")):
            value = message.get(source)
            if isinstance(value, str) and value:
                payload[destination] = value
        if "html" not in payload and "text" not in payload:
            body = message.get("body")
            if not isinstance(body, str) or not body:
                raise ResendAPIError("email body is required", code="INVALID_EMAIL", retryable=False)
            payload["text"] = body
        payload["html"] = render_branded_email_html(
            subject.strip(),
            html_body=payload.get("html"),
            text_body=payload.get("text"),
            preheader=message.get("preheader") if isinstance(message.get("preheader"), str) else None,
        )

        reply_to = message.get("reply_to")
        if reply_to:
            payload["reply_to"] = self._validate_address(reply_to, sender=True)

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Accept": "application/json",
            "Content-Type": "application/json",
        }
        idempotency_key = message.get("idempotency_key")
        if idempotency_key is not None:
            if (
                not isinstance(idempotency_key, str)
                or not idempotency_key.strip()
                or len(idempotency_key) > self.max_idempotency_key_length
                or "\r" in idempotency_key
                or "\n" in idempotency_key
            ):
                raise ResendAPIError("email idempotency key is invalid", code="INVALID_EMAIL", retryable=False)
            headers["Idempotency-Key"] = idempotency_key

        try:
            response = self.http_client.post(
                self.endpoint,
                headers=headers,
                json=payload,
                timeout=self.timeout_seconds,
                allow_redirects=False,
                verify=True,
            )
        except requests.Timeout as exc:
            raise ResendAPIError("Resend request timed out", code="PROVIDER_TIMEOUT", retryable=True) from exc
        except requests.RequestException as exc:
            raise ResendAPIError("Resend request failed", code="PROVIDER_REQUEST_ERROR", retryable=True) from exc

        status = getattr(response, "status_code", None)
        if not isinstance(status, int) or status < 200 or status >= 300:
            retryable = status in {408, 429} or (isinstance(status, int) and status >= 500)
            code = "PROVIDER_RATE_LIMITED" if status == 429 else "PROVIDER_REJECTED"
            raise ResendAPIError("Resend rejected the email request", code=code, retryable=retryable)
        try:
            result = response.json()
        except ValueError as exc:
            raise ResendAPIError("Resend returned an invalid response", code="PROVIDER_INVALID_RESPONSE", retryable=True) from exc
        message_id = result.get("id") if isinstance(result, dict) else None
        if not isinstance(message_id, str) or not message_id.strip():
            raise ResendAPIError("Resend returned an invalid response", code="PROVIDER_INVALID_RESPONSE", retryable=True)
        return {"message_id": message_id}
