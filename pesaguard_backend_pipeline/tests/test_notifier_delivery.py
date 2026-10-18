import smtplib
from unittest.mock import Mock

import notifier
import pytest
from email_addresses import EMAIL_ADDRESSES


ALERT = {
    "trans_id": "TX-9281",
    "tenant_id": "tenant-test",
    "severity": "high",
    "status": "needs_review",
    "anomalies": ["amount_mismatch"],
    "amount": 1250,
}


class FakeResponse:
    def __init__(self, status_code, headers=None):
        self.status_code = status_code
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False


def test_sms_delivery_sends_formatted_alert_to_configured_recipient(monkeypatch):
    calls = []

    class SmsClient:
        def send_sms(self, recipient, message):
            calls.append((recipient, message))

    monkeypatch.setattr(notifier, "sms_client", SmsClient())
    monkeypatch.setenv("SMS_ALERT_RECIPIENT", "+254700000001")

    assert notifier.send_sms_alert(ALERT, max_retries=0) is True
    assert len(calls) == 1
    assert calls[0][0] == "+254700000001"
    assert "TX-9281" in calls[0][1]


def test_sms_delivery_retries_transient_failures_then_succeeds(monkeypatch):
    calls = []
    sleeps = []

    class SmsClient:
        def send_sms(self, recipient, message):
            calls.append(recipient)
            if len(calls) == 1:
                raise OSError("temporary provider failure")

    monkeypatch.setattr(notifier, "sms_client", SmsClient())
    monkeypatch.setenv("SMS_ALERT_RECIPIENT", "+254700000002")
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.25)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)

    assert notifier.send_sms_alert(ALERT, max_retries=1) is True
    assert calls == ["+254700000002", "+254700000002"]
    assert sleeps == [0.25]


def test_sms_delivery_reports_failure_after_retry_budget_is_exhausted(monkeypatch):
    attempts = []
    sleeps = []

    class SmsClient:
        def send_sms(self, recipient, message):
            attempts.append(recipient)
            raise OSError("provider unavailable")

    monkeypatch.setattr(notifier, "sms_client", SmsClient())
    monkeypatch.setenv("SMS_ALERT_RECIPIENT", "+254700000004")
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.125)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)

    assert notifier.send_sms_alert(ALERT, max_retries=1) is False
    assert attempts == ["+254700000004", "+254700000004"]
    assert sleeps == [0.125]


def test_sms_delivery_reports_missing_configuration_or_client(monkeypatch):
    monkeypatch.setattr(notifier, "sms_client", None)
    monkeypatch.delenv("SMS_ALERT_RECIPIENT", raising=False)
    assert notifier.send_sms_alert(ALERT, max_retries=0) is False

    monkeypatch.setenv("SMS_ALERT_RECIPIENT", "+254700000003")
    assert notifier.send_sms_alert(ALERT, max_retries=0) is False


def test_email_delivery_sends_message_and_uses_configured_smtp_authentication(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "smtp")
    sent_messages = []
    smtp_calls = []

    class FakeSMTP:
        def __init__(self, host, port, timeout):
            smtp_calls.append((host, port, timeout))

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def starttls(self):
            smtp_calls.append("starttls")

        def login(self, username, password):
            smtp_calls.append((username, password))

        def send_message(self, message):
            sent_messages.append(message)

    monkeypatch.setattr(notifier.smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(notifier, "SMTP_HOST", "smtp.test.invalid")
    monkeypatch.setattr(notifier, "SMTP_PORT", 2525)
    monkeypatch.setattr(notifier, "SMTP_USER", "alert-user")
    monkeypatch.setattr(notifier, "SMTP_PASS", "alert-password")
    monkeypatch.setattr(notifier, "EMAIL_FROM", "alerts@test.invalid")
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "finance@test.invalid")

    assert notifier.send_email_alert(ALERT, max_retries=0) is True
    assert smtp_calls == [
        ("smtp.test.invalid", 2525, 5),
        "starttls",
        ("alert-user", "alert-password"),
    ]
    assert len(sent_messages) == 1
    assert sent_messages[0]["To"] == "finance@test.invalid"
    assert sent_messages[0]["From"] == "alerts@test.invalid"
    message_parts = list(sent_messages[0].iter_parts())
    assert "TX-9281" in message_parts[0].get_content()
    assert "https://pesaguard.co.ke/logo/logo.svg" in message_parts[1].get_content()


def test_email_delivery_sends_without_authentication_when_credentials_are_absent(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "smtp")
    sent_messages = []

    class FakeSMTP:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def starttls(self):
            raise AssertionError("SMTP authentication must be disabled without credentials")

        def login(self, username, password):
            raise AssertionError("SMTP login must be disabled without credentials")

        def send_message(self, message):
            sent_messages.append(message)

    monkeypatch.setattr(notifier.smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(notifier, "SMTP_HOST", "smtp.test.invalid")
    monkeypatch.setattr(notifier, "SMTP_USER", None)
    monkeypatch.setattr(notifier, "SMTP_PASS", None)
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "finance@test.invalid")

    assert notifier.send_email_alert(ALERT, max_retries=0) is True
    assert len(sent_messages) == 1


def test_email_delivery_retries_smtp_failures_and_stops_after_success(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "smtp")
    attempts = []
    sleeps = []

    class FakeSMTP:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def send_message(self, message):
            attempts.append(message)
            if len(attempts) == 1:
                raise smtplib.SMTPServerDisconnected("temporary disconnect")

    monkeypatch.setattr(notifier.smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(notifier, "SMTP_HOST", "smtp.test.invalid")
    monkeypatch.setattr(notifier, "SMTP_USER", None)
    monkeypatch.setattr(notifier, "SMTP_PASS", None)
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.5)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "finance@test.invalid")

    assert notifier.send_email_alert(ALERT, max_retries=1) is True
    assert len(attempts) == 2
    assert sleeps == [0.5]


def test_email_delivery_reports_failure_after_retry_budget_is_exhausted(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "smtp")
    attempts = []
    sleeps = []

    class FakeSMTP:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_value, traceback):
            return False

        def send_message(self, message):
            attempts.append(message)
            raise smtplib.SMTPServerDisconnected("SMTP server unavailable")

    monkeypatch.setattr(notifier.smtplib, "SMTP", FakeSMTP)
    monkeypatch.setattr(notifier, "SMTP_HOST", "smtp.test.invalid")
    monkeypatch.setattr(notifier, "SMTP_USER", None)
    monkeypatch.setattr(notifier, "SMTP_PASS", None)
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.25)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "finance@test.invalid")

    assert notifier.send_email_alert(ALERT, max_retries=1) is False
    assert len(attempts) == 2
    assert sleeps == [0.25]


def test_email_delivery_skips_missing_configuration_and_stops_on_unexpected_errors(monkeypatch):
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "smtp")
    monkeypatch.setattr(notifier, "SMTP_HOST", None)
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "finance@test.invalid")
    assert notifier.send_email_alert(ALERT, max_retries=0) is False

    monkeypatch.setattr(notifier, "SMTP_HOST", "smtp.test.invalid")
    monkeypatch.delenv("ALERT_EMAIL_RECIPIENTS", raising=False)
    assert notifier.send_email_alert(ALERT, max_retries=0) is False

    def fail_to_connect(*args, **kwargs):
        raise RuntimeError("invalid local SMTP configuration")

    monkeypatch.setattr(notifier.smtplib, "SMTP", fail_to_connect)
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", "finance@test.invalid")
    assert notifier.send_email_alert(ALERT, max_retries=3) is False


def test_resend_alert_sends_to_each_recipient_with_stable_keys(monkeypatch):
    client = Mock()
    monkeypatch.setenv("PESAGUARD_EMAIL_PROVIDER", "resend")
    monkeypatch.setenv("ALERT_EMAIL_RECIPIENTS", " ops@example.test, finance@example.test ")
    monkeypatch.setattr(notifier, "ResendEmailClient", lambda: client)

    assert notifier.send_email_alert(ALERT, max_retries=0) is True

    messages = [call.args[0] for call in client.send_message.call_args_list]
    assert [message["to"] for message in messages] == ["ops@example.test", "finance@example.test"]
    assert all(message["from_email"] == EMAIL_ADDRESSES.alerts for message in messages)
    assert all(message["reply_to"] == EMAIL_ADDRESSES.incidents for message in messages)
    assert messages[0]["idempotency_key"] != messages[1]["idempotency_key"]
    assert messages[0]["preheader"] == "Review the discrepancy alert and its current status."
    assert "TX-9281" in messages[0]["text"]


def test_slack_delivery_reports_missing_configuration_and_rejects_negative_retry_count(monkeypatch):
    monkeypatch.setattr(notifier, "SLACK_WEBHOOK_URL", "")
    assert notifier.send_slack_alert(ALERT, max_retries=0) is False

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    with pytest.raises(ValueError, match="max_retries must not be negative"):
        notifier.send_slack_alert(ALERT, max_retries=-1)


def test_slack_delivery_retries_network_failures_and_stops_when_retries_are_exhausted(monkeypatch):
    calls = []
    sleeps = []

    def fail_once_then_deliver(*args, **kwargs):
        calls.append(args)
        if len(calls) == 1:
            raise notifier.requests.ConnectionError("temporary network failure")
        return FakeResponse(200)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fail_once_then_deliver)
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.125)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)
    assert notifier.send_slack_alert(ALERT, max_retries=1) is True
    assert len(calls) == 2
    assert sleeps == [0.125]

    calls.clear()
    sleeps.clear()

    def always_fail(*args, **kwargs):
        calls.append(args)
        raise notifier.requests.Timeout("provider timeout")

    monkeypatch.setattr(notifier.requests, "post", always_fail)
    assert notifier.send_slack_alert(ALERT, max_retries=1) is False
    assert len(calls) == 2
    assert sleeps == [0.125]


def test_slack_delivery_bounds_invalid_and_excessive_retry_after_values(monkeypatch):
    calls = []
    sleeps = []
    responses = iter(
        [
            FakeResponse(429, {"Retry-After": "not-a-number"}),
            FakeResponse(429, {"Retry-After": "999"}),
            FakeResponse(200),
        ]
    )

    def fake_post(*args, **kwargs):
        calls.append(args)
        return next(responses)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fake_post)
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.25)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)

    assert notifier.send_slack_alert(ALERT, max_retries=2) is True
    assert len(calls) == 3
    assert sleeps == [0.25, 300]


def test_slack_delivery_reports_server_failure_after_retry_budget_is_exhausted(monkeypatch):
    calls = []

    def return_server_error(*args, **kwargs):
        calls.append(args)
        return FakeResponse(503)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", return_server_error)

    assert notifier.send_slack_alert(ALERT, max_retries=0) is False
    assert len(calls) == 1
