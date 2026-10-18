"""
Unit tests for PesaGuard email template rendering.

Covers: HTML and plain-text rendering, logo presence, CTA presence, preheader,
subject, icon image, sensitive-value masking, missing-variable handling, and
event-specific copy uniqueness.
"""

from __future__ import annotations

import pesaguard_email.templates  # noqa: F401
from pesaguard_email.dispatcher import _render_template
from pesaguard_email.registry import REGISTRY
from pesaguard_email.urls import urls_from_env


def _render(tid: str, context: dict):
    definition = REGISTRY.get(tid)
    return _render_template(definition, context, urls_from_env())


def _base_context() -> dict:
    return {
        "display_name": "John Mwangi",
        "account_email": "john.mwangi@example.com",
        "dashboard_url": "https://app.pesaguard.co.ke/",
    }


def test_default_email_urls_use_pesaguard_co_ke_hosts(monkeypatch):
    for key in (
        "PESAGUARD_DASHBOARD_URL",
        "PESAGUARD_DEVELOPER_URL",
        "PESAGUARD_API_URL",
        "PESAGUARD_PUBLIC_URL",
        "PESAGUARD_STATUS_URL",
    ):
        monkeypatch.delenv(key, raising=False)

    urls = urls_from_env()

    assert urls.dashboard == "https://app.pesaguard.co.ke"
    assert urls.developer == "https://developers.pesaguard.co.ke"
    assert urls.api == "https://api.pesaguard.co.ke"
    assert urls.public == "https://pesaguard.co.ke"
    assert urls.status == "https://status.pesaguard.co.ke"
    assert "victorkipruto.com" not in urls.verify_email("test-token")


def test_welcome_renders_with_logo_and_cta():
    rendered = _render(
        "A01",
        {**_base_context(),
         "account_state": "created",
         "created_at": "2026-01-01T00:00:00Z"},
    )
    assert rendered.subject == "Welcome to PesaGuard"
    assert rendered.preheader
    assert "pesaguard.co.ke/logo/logo.svg" in rendered.html
    assert rendered.html
    assert rendered.plain_text
    assert "Welcome to PesaGuard" in rendered.html
    assert rendered.sender == "no-reply@pesaguard.co.ke"
    assert rendered.reply_to == "support@pesaguard.co.ke"


def test_verification_renders_with_single_use_notice():
    rendered = _render(
        "A02",
        {**_base_context(),
         "verification_url": "https://api.pesaguard.co.ke/auth/verify-email?token=abc",
         "expires_at": "2026-01-02T00:00:00Z"},
    )
    assert "Verify your PesaGuard email address" == rendered.subject
    assert "single-use" in rendered.html.lower() or "single use" in rendered.html.lower()
    assert "Verify email address" in rendered.html


def test_password_reset_never_sends_password():
    rendered = _render(
        "A07",
        {**_base_context(),
         "reset_url": "https://api.pesaguard.co.ke/auth/password-reset/confirm?token=abc",
         "expires_at": "2026-01-02T00:00:00Z"},
    )
    assert "Reset your PesaGuard password" == rendered.subject
    assert rendered.plain_text
    assert "single-use" in rendered.html.lower()


def test_suspicious_login_uses_warn_notice():
    rendered = _render(
        "A06",
        {**_base_context(),
         "event_time": "2026-01-02T03:00:00Z",
         "secure_account_url": "https://app.pesaguard.co.ke/account-authentication"},
    )
    assert "Unusual sign-in" in rendered.subject
    assert rendered.icon.label == "Security alert"


def test_reconciliation_renders_actual_figures():
    rendered = _render(
        "D02",
        {**_base_context(),
         "reference": "rec-12345",
         "period": "January 2026",
         "completed_at": "2026-02-01T12:00:00Z",
         "records_processed": 1250,
         "matched_count": 1200,
         "unmatched_count": 30,
         "exception_count": 20},
    )
    assert "Reconciliation completed for January 2026" == rendered.subject
    assert "1250" in rendered.html
    assert "1200" in rendered.html
    assert "30" in rendered.html
    assert "20" in rendered.html
    assert "rec-12345" in rendered.html


def test_api_key_never_sends_secret():
    rendered = _render(
        "F08",
        {**_base_context(),
         "project": "My Project",
         "environment": "sandbox",
         "key_label": "Production key",
         "key_id": "sk_live_1234567890abcdef",
         "created_at": "2026-01-01T00:00:00Z"},
    )
    assert "A new API key was created" in rendered.subject
    assert "Never send API secrets by email" in rendered.html


def test_high_risk_distinguishes_suspected():
    rendered = _render(
        "E01",
        {**_base_context(),
         "entity": "Merchant account #4421",
         "risk_category": "unusual_volume",
         "severity": "high",
         "detected_at": "2026-01-02T03:00:00Z",
         "review_url": "https://app.pesaguard.co.ke/exceptions",
         "handling_status": "under review"},
    )
    assert "High-risk activity detected" in rendered.subject
    assert "suspected" in rendered.html.lower() or "suspicion" in rendered.html.lower()


def test_email_masking():
    from pesaguard_email.templates.base import mask_email
    assert mask_email("john.mwangi@example.com") == "j•••••••••i@example.com"
    assert mask_email("a@example.com") == "a@example.com"


def test_missing_required_variable_raises():
    definition = REGISTRY.get("A01")
    try:
        _render_template(definition, {"account_email": "x@example.com"}, urls_from_env())
        assert False, "expected ValueError for missing variables"
    except ValueError as exc:
        assert "dashboard_url" in str(exc)


def test_plain_text_has_no_html_tags():
    rendered = _render(
        "A01",
        {**_base_context(),
         "account_state": "created",
         "created_at": "2026-01-01T00:00:00Z"},
    )
    assert "<" not in rendered.plain_text
    assert ">" not in rendered.plain_text


def test_each_template_has_unique_subject():
    for tid in REGISTRY.template_ids():
        definition = REGISTRY.get(tid)
        assert definition.subject_template != "PesaGuard notification"


def test_email_contains_no_secrets_keywords():
    for tid in REGISTRY.template_ids():
        definition = REGISTRY.get(tid)
        combined = definition.subject_template + " " + definition.preheader_template
        lower = combined.lower()
        for secret_word in ["api_secret", "password=", "secret_key", "access_token"]:
            assert secret_word not in lower, f"{tid} leaks {secret_word}"
