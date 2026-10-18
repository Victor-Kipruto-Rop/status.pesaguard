# PesaGuard email delivery

## Outbound delivery

The legacy report/authentication sender and alert notifier use the Resend REST
adapter by default. The separate communications outbox has a Resend adapter
selected from deployment configuration and records provider acceptance with
bounded retry/dead-letter behavior. Resend requests from the direct sender and
outbox use a 10-second timeout, TLS verification enabled, redirects disabled,
sanitized provider errors, and an `Idempotency-Key` on keyed sends. Resend
retains idempotency keys for 24 hours; use a unique key per email event and do
not assume that provider idempotency covers later replays. See Resend's
[send-email API](https://resend.com/docs/api-reference/emails/send-email) and
[idempotency documentation](https://resend.com/docs/dashboard/emails/idempotency-keys).

Configure these values through the deployment environment:

| Variable | Value |
| --- | --- |
| `PESAGUARD_EMAIL_PROVIDER` | `resend` (default); SMTP is available only when explicitly selected |
| `RESEND_API_KEY` | Resend API key from the deployment secret manager |
| `PESAGUARD_EMAIL_FROM` | `no-reply@pesaguard.co.ke` |
| `PESAGUARD_EMAIL_REPLY_TO` | `support@pesaguard.co.ke` |
| `ALERT_EMAIL_RECIPIENTS` | Comma-separated, explicitly authorized operational recipients |

Do not paste the API key into chat, commit it, bake it into a container image,
or copy it into `.env.example`. Use `PESAGUARD_EMAIL_PROVIDER=smtp` only for an
intentional legacy SMTP deployment; SMTP still requires TLS.

Add the domain in the Resend console and publish the exact DNS records Resend
provides for domain verification and DKIM/SPF. This repository intentionally
does not invent provider-specific DNS record values. Keep a single SPF policy
by merging permitted senders into the existing record; publish a DMARC policy
and monitor aggregate reports before tightening enforcement. Verify sending
and alignment for the exact `pesaguard.co.ke` domain before production use.

## Branded email presentation

The Python `EmailService` wraps report, authentication, public-status, and
other direct email HTML for Resend and explicitly configured SMTP. Discrepancy
alerts use the same layout with either explicitly selected provider. The
communications outbox's Resend adapter forwards text and optional HTML through
the shared Resend renderer. Existing HTML content in the legacy sender is
placed inside the layout; text-only content is safely escaped into readable
HTML while preserving the plain-text alternative. User-provided HTML is
sanitized to a limited set of email-safe tags, attributes, inline styles, and
links. Images in message content are restricted to PesaGuard's logo paths to
prevent third-party tracking pixels.

## Implemented event coverage

This table lists send paths verified in the repository; it is not a promise
that every catalogued or possible business event has a producer.

| System | Email event | Trigger and delivery behavior |
| --- | --- | --- |
| Python backend | Account verification and password reset | Authentication routes send synchronously in production; non-production skips delivery. Delivery outcomes are recorded, and failed delivery invalidates the associated challenge/reset state. |
| Python backend | Password-expiration notice | Sent when credential policy evaluation detects an approaching expiration; duplicate notices are suppressed per account per UTC day. |
| Python backend | MFA email verification and reset codes | Authenticated MFA routes send synchronously in production and mark failed challenges when delivery fails. |
| Python backend | Reconciliation report and escalation alert | Authenticated API routes; locale-aware and synchronously submitted. Escalations can also be initiated by the escalation engine. |
| Python backend | Discrepancy/on-call alert | Alert and on-call paths call the notifier. Resend requests use a stable tenant/event/recipient key and bounded retry; explicitly selected SMTP has no provider idempotency key. |
| Python backend | Public-status subscription confirmation and status change | Opt-in confirmation and change notifications use the status email address; the status fingerprint advances after successful submission. |
| Python backend | Daily operations summary | A callable sender exists, but no scheduled caller was found in the repository. |
| Python backend | Generic communications email | Tenant-authenticated API enqueues a message with a required idempotency key. The worker selects the configured provider; outbox retries and provider acceptance are persisted separately from the legacy direct-email paths. |
| Developer Platform | Verification, login and password-change codes; password-reset link | Direct account/security senders. Reset delivery intentionally keeps the public response indistinguishable, so its caller does not learn delivery success. |
| Developer Platform | Account lifecycle and organization invitation notices | Direct sends for pause/reactivation/deletion and invitations; these do not share the durable notification queue. |
| Developer Platform | Credential, webhook, quota, production-access, security, and support-ticket product notifications | Durable notification events are emitted only by the producers currently implemented in the Java notification subsystem. Catalog types without producers do not generate mail. Email delivery is per-channel and retried independently from in-app delivery. |
| Developer Platform | New support-ticket alert to platform support | Synchronous internal notification; it is not a customer-facing notification event. |

The Developer Platform rows describe the Java implementation reviewed earlier
in this task. At the final verification pass, its backend source tree and
Gradle build files were absent from the current workspace, so those source
changes and tests cannot be confirmed as present or rerun here. Restore that
project before treating its email work as verified or releasing it.

## Verified limits and rollout gaps

- The Python and Developer Platform applications retain separate delivery
  adapters, configuration, persistence, and retry boundaries. There is no
  cross-application mail API or shared event/outbox.
- Resend acceptance is not inbox delivery. The Developer Platform records
  `ACCEPTED` for provider acceptance and has no connected Resend delivery-event
  webhook; Python legacy history still uses `sent` for successful provider
  submission.
- Direct sends are not all transactional with their source events. The
  Developer Platform notification queue is durable, but direct account,
  invitation, support, Python authentication, report, and public-status sends
  do not all use that queue. Some event paths therefore have weaker
  duplicate/recovery guarantees.
- Daily-summary scheduling was not found. No template is claimed for billing,
  receipt, invoice, or other event types without an implemented producer.
- The shared layout references the hosted SVG logo. Email clients may block
  SVG or remote images; no raster fallback or cross-client rendering test is
  present.
- No live test email was sent. DNS verification, SPF/DKIM/DMARC alignment,
  provider acceptance, inbox placement, and role-address routing remain
  deployment verification tasks.

## Role addresses and message use

[`email_addresses.py`](../../email_addresses.py) is the application directory for the
following role addresses. Resend sends from `no-reply@` for ordinary
transactional and reconciliation messages, `security@` for authentication
and security notices, `alerts@` for incident alerts, and `status@` for public
status subscription confirmations and updates. Incident replies go to
`incidents@`; ordinary customer and status replies go to `support@`.

| Address | Intended use |
| --- | --- |
| `support@pesaguard.co.ke` | Customer help and default transactional replies |
| `sales@pesaguard.co.ke` | Sales enquiries |
| `billing@pesaguard.co.ke` | Invoices and subscription questions |
| `security@pesaguard.co.ke` | Security reports and authentication notices |
| `privacy@pesaguard.co.ke` | Privacy and personal-data requests |
| `compliance@pesaguard.co.ke` | Regulatory and compliance enquiries |
| `alerts@pesaguard.co.ke` | Automated operational and incident messages |
| `no-reply@pesaguard.co.ke` | Automated transactional messages |
| `onboarding@pesaguard.co.ke` | Customer setup and implementation |
| `partnerships@pesaguard.co.ke` | Banks, payment providers, and partners |
| `operations@pesaguard.co.ke` | Operational issues |
| `incidents@pesaguard.co.ke` | Incident coordination and alert replies |
| `legal@pesaguard.co.ke` | Legal notices and contracts |
| `abuse@pesaguard.co.ke` | Misuse and abuse reports |
| `status@pesaguard.co.ke` | Public status subscription confirmations and service-status updates; replies route to support |
| `careers@pesaguard.co.ke` | Job applications |
| `press@pesaguard.co.ke` | Media enquiries |

The address directory does **not** create mailboxes or aliases. Create role
aliases/forwarders in the chosen inbound email-hosting service, assign an
accountable owner, and test inbound and outbound messages before publishing
them. Resend is used here for outbound delivery; MX/inbound mail hosting is a
separate provider and configuration. Do not publish an address until its
destination and handling owner are operational.

## Validation and rollout

Automated tests use mocked HTTP responses and never send mail. Run the focused
suite from the repository root:

```powershell
python -m pytest -q --tb=short pesaguard_backend_pipeline\tests\test_email_branding.py pesaguard_backend_pipeline\tests\test_resend_email.py pesaguard_backend_pipeline\tests\test_email_smtp.py pesaguard_backend_pipeline\tests\test_notifier_delivery.py pesaguard_backend_pipeline\tests\test_communications_services.py
```

After DNS verification and secret injection, run an explicitly authorized
staging smoke test to a controlled recipient. Confirm Resend acceptance,
inbox delivery, SPF/DKIM/DMARC alignment, reply-to routing, outbox retry and
dead-letter visibility, and sanitized logs. A local passing test suite does
not prove DNS verification or live deliverability. Roll back by selecting the
previous email provider setting; queued notifications remain in the existing
outbox and must not be replayed blindly after provider acceptance is uncertain.
