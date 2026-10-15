import notifier
import pytest


class FakeResponse:
    def __init__(self, status, headers=None):
        self.status_code = status
        self.headers = headers or {}

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False


@pytest.mark.parametrize(
    "webhook_url",
    [
        "http://hooks.slack.com/services/team/channel/token",
        "https://hooks.slack.com.attacker.invalid/services/team/channel/token",
        "https://example.invalid/services/team/channel/token",
        "https://user@hooks.slack.com/services/team/channel/token",
        "file:///tmp/webhook",
    ],
)
def test_slack_dispatch_rejects_noncanonical_webhook_endpoints(monkeypatch, webhook_url):
    calls = []
    monkeypatch.setattr(notifier, "SLACK_WEBHOOK_URL", webhook_url)
    monkeypatch.setattr(notifier.requests, "post", lambda *args, **kwargs: calls.append(args))

    assert notifier.send_slack_alert({"trans_id": "test-event"}) is False
    assert calls == []


def test_slack_dispatch_does_not_follow_redirects(monkeypatch):
    calls = []

    def fake_post(url, **kwargs):
        calls.append((url, kwargs))
        return FakeResponse(302)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fake_post)

    assert notifier.send_slack_alert({"trans_id": "test-event"}, max_retries=0) is False
    assert len(calls) == 1
    assert calls[0][1]["allow_redirects"] is False
    assert calls[0][1]["timeout"] == 5


def test_slack_dispatch_sends_to_validated_https_endpoint(monkeypatch):
    calls = []

    def fake_post(url, **kwargs):
        calls.append((url, kwargs))
        return FakeResponse(200)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fake_post)

    assert notifier.send_slack_alert({"trans_id": "test-event"}, max_retries=0) is True
    assert calls[0][0] == notifier.SLACK_WEBHOOK_URL
    assert calls[0][1]["timeout"] == 5
    assert calls[0][1]["allow_redirects"] is False


def test_slack_dispatch_retries_server_failures_with_backoff(monkeypatch):
    calls = []
    sleeps = []
    responses = iter((FakeResponse(503), FakeResponse(200)))

    def fake_post(url, **kwargs):
        calls.append(url)
        return next(responses)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fake_post)
    monkeypatch.setattr(notifier, "RETRY_BACKOFF_SECONDS", 0.25)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)

    assert notifier.send_slack_alert({"trans_id": "test-event"}, max_retries=1) is True
    assert len(calls) == 2
    assert sleeps == [0.25]


def test_slack_dispatch_does_not_retry_permanent_http_failures(monkeypatch):
    calls = []

    def fake_post(url, **kwargs):
        calls.append(url)
        return FakeResponse(400)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fake_post)

    assert notifier.send_slack_alert({"trans_id": "test-event"}, max_retries=2) is False
    assert len(calls) == 1


def test_slack_dispatch_honors_bounded_retry_after_for_rate_limits(monkeypatch):
    calls = []
    sleeps = []
    responses = iter((FakeResponse(429, {"Retry-After": "2"}), FakeResponse(200)))

    def fake_post(url, **kwargs):
        calls.append(url)
        return next(responses)

    monkeypatch.setattr(
        notifier,
        "SLACK_WEBHOOK_URL",
        "https://hooks.slack.com/services/team/channel/token",
    )
    monkeypatch.setattr(notifier.requests, "post", fake_post)
    monkeypatch.setattr(notifier.time, "sleep", sleeps.append)

    assert notifier.send_slack_alert({"trans_id": "test-event"}, max_retries=1) is True
    assert len(calls) == 2
    assert sleeps == [2.0]
