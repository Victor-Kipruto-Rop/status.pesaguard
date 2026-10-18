# PesaGuard Premium Email System

## Overview

A complete enterprise-grade transactional email system for PesaGuard, built on top of
the existing `pesaguard_backend_pipeline/communications` infrastructure.

The system provides:

* A **template registry** with stable template IDs (A01-A23 for authentication, plus
  representative templates from onboarding, M-Pesa, reconciliation, risk, developer,
  team, reports, and operations).
* A **premium email shell** with the PesaGuard brand identity (logo, mint #2ECC87,
  deep forest #0B2E24, soft green #F2F7F4, white #FFFFFF), table-based layout,
  inline CSS, system fonts, and responsive mobile behaviour.
* A **central icon registry** mapping each email event category to an existing project
  icon asset, a semantic colour, and an accessible label.
* An **event-to-template dispatcher** that connects verified application events to
  templates, enforces notification preferences and tenant isolation, and renders
  HTML + plain-text email.
* **Idempotent delivery** through the existing communications outbox (lease-based
  claiming, bounded retries with exponential backoff + jitter, dead-letter handling).
* A **preview system** for inspecting every template without sending real email.
* **Security controls**: no secrets in email, masked PII, single-use time-limited
  tokens for sensitive actions, provider webhook signature verification (existing).

## Repository structure

```
pesaguard_backend_pipeline/
├── email/
│   ├── brand.py            # Brand palette, logo URLs, sender identities
│   ├── icons.py            # Central icon registry
│   ├── shell.py            # Premium email shell (HTML + plain text)
│   ├── urls.py             # CTA URLs from trusted environment configuration
│   ├── registry.py         # Template registry + TemplateDefinition
│   ├── dispatcher.py       # Event-to-template dispatcher + preference enforcement
│   ├── previews.py         # Template preview system (no real email sent)
│   ├── DOCUMENTATION.md    # This file
│   ├── templates/
│   │   ├── base.py         # Shared rendering helpers
│   │   ├── auth/           # A01-A23 authentication & account lifecycle
│   │   ├── onboarding/     # B08 (representative)
│   │   ├── mpesa/          # C01 (representative)
│   │   ├── reconciliation/ # D02 (representative)
│   │   ├── risk/           # E01 (representative)
│   │   ├── developer/      # F08 (representative)
│   │   ├── team/           # G01 (representative)
│   │   ├── reports/        # H01 (representative)
│   │   └── operations/     # K22 (representative)
│   └── tests/
│       ├── test_registry.py
│       └── test_rendering.py
```
## Reused existing infrastructure

The email system extends (does not replace) the following existing, verified
components:

| Component | File | Role |
|-----------|------|------|
| Provider | `resend_email.py` | Resend REST client with idempotency keys + retry classification |
| Branding | `email_branding.py` | `render_branded_email_html()` for simple branded text emails |
| Sender directory | `email_addresses.py` | Verified sender identities + purpose routing |
| Notification model | `communications/models.py` | `CommunicationNotification` (status, idempotency, tenant isolation) |
| Outbox | `communications/outbox.py` | Lease-based claiming, retries, dead letters |
| Templates (DB) | `communications/models.py` | `CommunicationTemplate` (tenant-scoped, approval workflow) |
| Preferences | `communications/models.py` | `CommunicationPreference`, `CommunicationConsent` |
| Delivery service | `communications/application/notification_service.py` | Persist + submit via provider |
| Email service facade | `communications/application/email_service.py` | Provider-agnostic send/enqueue |
| Provider factory | `communications/providers/email.py` | `ResendEmailProvider`, `EmailProviderFactory`, `EmailRouter` |
| Worker | `communications/worker.py` | Durable outbox polling loop (5s default) |
| Webhooks | `communications/webhooks.py` | HMAC signature verification + delivery reports |
| Events | `communications/events.py` | `build_notification_event()` for domain events |
| Domain | `communications/domain.py` | `render_template()`, OTP, consent |
| Localization | `localization_utils.py` | KES currency, EAT datetime |
| Routes | `communications/routes.py` | `/api/v1/communications/email`, templates, preferences, webhooks |

## Template catalogue status

### Implemented and tested (31 templates)

**Authentication (A01-A23 — all 23):**
A01 Account welcome, A02 Email verification, A03 Verification reminder,
A04 Verification link expired, A05 Login notification, A06 Suspicious login,
A07 Password reset requested, A08 Password changed, A09 Password reset completed,
A10 Password change blocked, A11 MFA enabled, A12 MFA disabled,
A13 MFA challenge/recovery, A14 New device/session, A15 Session revoked,
A16 Email change requested, A17 Email address changed, A18 Account locked,
A19 Account unlocked, A20 Account recovery requested,
A21 Account recovery completed/denied, A22 Account deletion requested,
A23 Account deletion completed

**Representative templates (8):**
B08 Onboarding approved, C01 M-Pesa integration setup started,
D02 Reconciliation completed successfully, E01 High-risk activity detected,
F08 API key created, G01 Team invitation sent, H01 Daily activity digest,
K22 Incident opened

### Not yet implemented
Templates B02-B07, B09-B20, C02-C17, D01, D03-D26, E02-E19, F01-F07, F09-F38,
G02-G12, H02-H15, J01-J20, K01-K21, K23-K25 are documented in the catalogue but
not yet implemented as rendered templates. The registry structure supports adding
them by creating a renderer and `TemplateDefinition` and registering both.

### Not applicable
Billing templates (I01-I12): PesaGuard has previously been described as free.
Billing templates are not activated unless real billing/pricing/subscriptions
are verified in the backend. Do not create fictitious prices or invoices.

## Brand identity

* **Logo**: `https://pesaguard.co.ke/logo/logo.svg` (light), `logo-dark.svg` (dark)
  — the actual committed PesaGuard logo assets. No redrawn or generic logos.
* **Palette**: Mint `#2ECC87`, White `#FFFFFF`, Deep forest `#0B2E24`,
  Soft green `#F2F7F4`, plus neutral grays, red (`#C0392B`), amber (`#E67E22`).
* **Icons**: Existing project icon assets under `https://pesaguard.co.ke/logo/`
  (reusing `icon.svg` as the brand mark for categories). Every category has a
  semantic colour and accessible text label; colour is never the only signal.
* **Fonts**: System stack `Arial, Helvetica, sans-serif` — no remote web fonts.

## Sender identity

All sender addresses use `pesaguard.co.ke` and come from `email_addresses.py`:

* `no-reply@pesaguard.co.ke` — automated / transactional notifications
* `support@pesaguard.co.ke` — reply-to for most notifications
* `security@pesaguard.co.ke` — security notifications
* `onboarding@pesaguard.co.ke` — onboarding notifications
* `operations@pesaguard.co.ke` — operational alerts
* `incidents@pesaguard.co.ke` — incident notifications
* `developers@pesaguard.co.ke` — developer platform notifications

Configure `PESAGUARD_EMAIL_FROM` and `PESAGUARD_EMAIL_REPLY_TO` in the backend
environment. The provider (`Resend`) requires a verified sending domain.

## URLs and CTAs

All CTA URLs are built from trusted environment configuration, never from
user-controlled host headers. Configure via environment variables:

* `PESAGUARD_DASHBOARD_URL` (default `https://app.pesaguard.co.ke`)
* `PESAGUARD_DEVELOPER_URL` (default `https://developers.pesaguard.co.ke`)
* `PESAGUARD_API_URL` (default `https://api.pesaguard.co.ke`)
* `PESAGUARD_PUBLIC_URL` (default `https://pesaguard.co.ke`)
* `PESAGUARD_STATUS_URL` (default `https://status.pesaguard.co.ke`)

Provision DNS records, TLS certificates, and routes for every configured host
before enabling production email sends. A successful provider acceptance does
not confirm that recipients can open the links.

## Notification preferences

Essential security/account notifications (classification `essential`) are never
suppressed by recipient preferences. Non-essential categories respect
`CommunicationPreference` and `CommunicationConsent` records enforced by the
existing `NotificationPreferenceService` / `is_allowed_now()`.

## Idempotency and delivery

Each email gets a deterministic idempotency key derived from
`template_id|tenant_id|recipient|event_id|correlation_id`. Duplicate events do
not produce duplicate emails. The existing outbox provides:

* Lease-based claiming with skip-locked
* Bounded retries with exponential backoff + jitter
* Dead-letter handling with manual replay
* Delivery status tracking (accepted → submitted → delivered / bounced / failed)
* Provider response validation and retry classification (transient vs permanent)

## Security

* No passwords, API secrets, tokens, session identifiers, or credentials in email.
* API key IDs are masked; full secrets never appear.
* Email addresses are masked where appropriate (`j•••••••••i@example.com`).
* Verification, password reset, invitation, and email-change links are
  single-use, time-limited, server-validated tokens.
* Sensitive links use short-lived tokens bound to the account and action.
* Email content is HTML-sanitized (no scripts, iframes, unsafe URLs).
* Provider webhooks are HMAC-signature verified (existing infrastructure).
* Tenant isolation: the tenant is bound to the notification record; no
  cross-tenant recipient resolution.

## Environment variables

Required for production email delivery:

```
PESAGUARD_EMAIL_PROVIDER=resend
RESEND_API_KEY=<set via secret manager, never committed>
PESAGUARD_EMAIL_FROM=no-reply@pesaguard.co.ke
PESAGUARD_EMAIL_REPLY_TO=support@pesaguard.co.ke
PESAGUARD_DASHBOARD_URL=https://app.pesaguard.co.ke
PESAGUARD_API_URL=https://api.pesaguard.co.ke
PESAGUARD_PUBLIC_URL=https://pesaguard.co.ke
```

DNS authentication (SPF, DKIM, DMARC) must be configured for `pesaguard.co.ke`
through Resend before sending production email. Bounce and complaint processing
should be wired to Resend webhooks.

## Local development

The email system is importable and testable without a database or provider
credentials. Templates render with labelled test data. No real email is sent
unless `RESEND_API_KEY` is configured and a delivery is explicitly requested.

Tests:

```
cd pesaguard_backend_pipeline
python -m pytest pesaguard_email/tests/
```

Preview all templates (no email sent):

```
python -c "
import pesaguard_email.previews as p
for pv in p.list_previews():
    print(pv.template_id, pv.name, '|', pv.subject)
"
```

## Adding a new template

1. Create a renderer module under `pesaguard_email/templates/<category>/`
   with a `_render(context, urls)` function returning
   `(html_body, plain_text, cta_label, cta_url, notice, notice_kind, support_html)`.
2. Create a `TemplateDefinition` with a stable template ID, trigger event,
   sender, required/optional variables, and icon category.
3. Register both in `REGISTRY` and `_RENDERERS` at module import time.
4. Add the module import to the category `__init__.py`.
5. Add tests covering rendering, variables, and security.

## Provider / DNS still required for production

* Resend account with verified sending domain `pesaguard.co.ke`
* SPF/DKIM DNS records from Resend (provider-generated values)
* DMARC record
* `RESEND_API_KEY` injected via secret manager (never committed)
* Bounce/complaint webhook endpoints verified against Resend signatures

## Known limitations

* Only a representative subset of the 120+ catalogue templates is rendered;
  the remainder are documented but not implemented.
* Billing templates (I-series) are intentionally not activated — real billing
  is not verified in the backend.
* Resend bounce/complaint webhook handling is not yet implemented for email
  (existing webhooks are Africa's Talking SMS-focused).
* No inbound email parsing (inbound support@ mailbox) is implemented.
* Email template preview is not exposed as an HTTP route; use the Python API.
* Multilingual localization (Swahili) is not yet applied to email subjects/bodies.
