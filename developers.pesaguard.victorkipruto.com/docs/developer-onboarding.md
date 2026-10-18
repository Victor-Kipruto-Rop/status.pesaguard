# Developer onboarding

The developer portal's first-run path is:

1. Register a developer account and organization. If no organization name is
   supplied, the API creates a workspace using the developer's name.
2. Verify the email address using a single-use link delivered by the configured
   email provider. The
   link is valid for 24 hours; registration does not create an authenticated
   session.
3. Sign in after email verification; login and refresh both require an active,
   verified account.
4. Save the developer profile when required. The portal automatically creates a
   Payments starter project when a workspace has no active project. If automatic
   setup fails, the developer can retry with Payments, Events, or Usage Analytics.
5. Atomically provision an active SANDBOX environment and sandbox.
6. Issue a read-only API key scoped to the selected template:
   `transactions:read` for Payments, `events:read` for Events, or `usage:read`
   for Usage Analytics.
7. Confirm setup from the persisted organization, project, and environment
   records, then enter the developer dashboard.

Registration is controlled by `PESAGUARD_REGISTRATION_ENABLED` and is disabled
by default. The portal reports the API error when registration is unavailable.
Email delivery uses Spring Mail backed by Resend by default. Set
`RESEND_API_KEY` and `PESAGUARD_MAIL_FROM` (use `no-reply@pesaguard.co.ke`,
after verifying it in Resend) in the ignored root `.env` before enabling public
registration. The local Mailpit sink is available only when
`PESAGUARD_EMAIL_PROVIDER=smtp` is explicitly selected. The service returns an
explicit error if delivery fails; it does not display a success message when no
email has been sent. Set
`PESAGUARD_EMAIL_VERIFICATION_URL` when the developer portal is hosted at a
different URL. The verification token is placed in the URL fragment so it is
not sent to the web server in the request path or query string.

`GET /api/v1/onboarding/status` is the server-side routing authority after
authentication. It reports persisted profile, organization, project, and
environment readiness along with the next required step. Completion requires
all four steps. The frontend does not infer completion from local storage or a
sample project list.

`POST /api/v1/onboarding/bootstrap` requires the authenticated developer session.
Project, environment, sandbox, and API-key creation run in one transaction. A
failure rolls back the bootstrap rather than leaving a partial workspace. The
response includes the raw API key once and sends `Cache-Control: no-store`; the
server stores only its HMAC. Copy and retain the key before navigating away.
The response also includes the selected template, its first endpoint, and the
official server-assigned `baseUrl` for that key's environment. The first-run key
is bound to `SANDBOX` and uses
`https://sandbox-api.pesaguard.co.ke`; the portal displays the URL,
Bearer authentication method, endpoint, and copy action with the one-time key.
Production environments use `https://api.pesaguard.co.ke`.
Developers should never enter a PesaGuard API host manually.
The frontend does not call the sandbox transactions fixture as an onboarding
gate; dashboard access is based on the backend's persisted workspace status.

The sandbox transactions endpoint validates API-key authentication and sandbox
authorization:

```http
GET https://sandbox-api.pesaguard.co.ke/api/v1/sandbox/transactions
Authorization: Bearer pgk_...
```

Keep the key in trusted server-side configuration; never put a Production key
in browser code, browser storage, logs, or a client application. Credentials
are environment-bound: presenting a Sandbox key at the Production host (or
vice versa) is rejected with `API_KEY_ENVIRONMENT_MISMATCH`.

It validates the key's state, expiry, `transactions:read` scope,
and active SANDBOX binding, then records successful key usage. It returns only
persisted transaction records; because transaction processing is not implemented
in this backend, the collection is currently empty. It does not accept an
arbitrary URL, request body, or payment rail, and does not initiate payment
execution.

The notification inbox and channel preferences are also account- and
organization-scoped backend records. The frontend displays only records returned
by the API; it does not seed sample notifications or keep read state in browser
storage.
