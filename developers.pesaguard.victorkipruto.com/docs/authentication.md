# Authentication architecture

How PesaGuard Developer Platform authenticates a developer, and where the
boundaries are. For token mechanics see [token-lifecycle.md](token-lifecycle.md).

## Layering

Authentication is deliberately separate from authorization, and separate again
from tenancy. Each layer can be reasoned about without the others.

```
Password / second factor      authentication  — who is this?
        ↓
Access + refresh tokens       session         — what session is this request in?
        ↓
organization_memberships      authorization   — what may this user do here?
        ↓
role / permission              policy          — is this action allowed?
```

`LoginService` verifies a credential. It does not decide whether the user may
create a project. `AccessDecisionService` decides that, and reads the principal
rather than the raw request.

## Registration, verification, and first workspace

Registration creates the developer account and its initial organization, then
issues a single-use six-digit email verification code. Only its HMAC and issue
time are stored. The developer enters the code in the portal, which submits it
to `POST /api/v1/auth/verify-email`; legacy verification links remain supported.
Codes expire after ten minutes. `POST /api/v1/auth/verify-email/resend` replaces
the outstanding code and returns the same accepted response for unknown,
verified, or unverified addresses.

Password login is rejected until email verification succeeds. Email OTP verification is a separate
step and does not allow the user to edit the pending address; the portal retains
the address privately for submission and displays only a redacted version.
After email verification succeeds, a successful
login creates the access session and refresh-token family. The portal then calls
`GET /api/v1/onboarding/status`; account activity and email verification are
checked on the server, and the project and environment state determines whether
the developer goes to setup or the dashboard. The onboarding bootstrap endpoint
checks verification again before creating the first project, sandbox environment,
and starter credential. A workspace without a project receives the Payments
starter automatically; an automatic setup failure can be retried with Payments,
Events, or Usage Analytics. Login remembers the last active workspace on the
user account and opens it directly on the next sign-in; accounts without a saved
workspace use an active membership as a fallback. The portal reads this status on
every authenticated app load, and a backend security filter rechecks account,
membership, and onboarding
state on each protected developer API request. Browser storage cannot mark
onboarding complete or bypass the API gate.

Every password login requires a six-digit email MFA code, whether or not the user
has enrolled other factors. `POST /api/v1/auth/login` verifies the password and
policy checks, then returns `LOGIN_EMAIL_MFA_REQUIRED` with a short-lived,
single-use challenge; it does not issue a session. The code is delivered to the
verified address, and a successful `POST /api/v1/auth/login/email-mfa/verify`
creates the session. `POST /api/v1/auth/login/email-mfa/resend` enforces a
cooldown. The challenge is HMAC-protected at rest, expires after ten minutes,
and is tied to the user's last-accessed active workspace (falling back to an
active membership when no saved workspace remains). The portal redacts the
address and makes it non-editable while the challenge is active.

Authenticator codes, recovery codes, and passkeys do not complete normal login.
The public passkey login ceremonies are disabled; registered passkeys remain
available as account credentials. Password recovery uses a one-hour email reset
link and returns a generic accepted response for known and unknown addresses.
Completing a reset revokes the account's active sessions and refresh-token
families. The portal's confirmation does not verify that an account exists or
that an email reached its inbox. When a reset email does not arrive, check spam
and verify the configured SMTP or Resend delivery logs.

The onboarding status includes profile, organization, project, and environment
readiness plus `nextStep`. The profile name is collected during registration; a
developer with an incomplete legacy profile can update it through
`PATCH /api/v1/onboarding/profile`. The initial organization is provisioned with
registration because sessions and authorization are scoped to an organization.

Email delivery uses Spring Mail backed by Resend by default for verification,
sign-in MFA, password recovery, invitations, support alerts, and notifications.
Set `RESEND_API_KEY` and `PESAGUARD_MAIL_FROM` to a verified Resend sender
address. The provider fails startup if Resend is selected without an API key.
`PESAGUARD_EMAIL_VERIFICATION_URL` sets the portal URL used in verification
messages. SMTP/Mailpit is available only when `PESAGUARD_EMAIL_PROVIDER=smtp`
is explicitly selected.
Registration returns a delivery error if the message cannot be sent; resend
remains enumeration-safe.

## Developer API credentials and environment hosts

Every project environment includes a server-assigned `baseUrl`; clients should
read that value from the environment API or the one-time API-key creation
response rather than asking developers to configure a PesaGuard host:

| Environment | Official Base URL |
| --- | --- |
| `DEVELOPMENT`, `SANDBOX`, `STAGING` | `https://sandbox-api.pesaguard.co.ke` |
| `PRODUCTION` | `https://api.pesaguard.co.ke` |

An API key is bound to its project environment. Send it as a Bearer credential
only to the returned Base URL; the server rejects a key presented at the other
environment host with `API_KEY_ENVIRONMENT_MISMATCH`. Production calls also
require active production access. The Base URL is an origin; append the endpoint
path documented for the capability and scope granted to the key.

Keep API keys in a trusted backend secret manager or server-side environment
variable. Never embed production keys in browser code, mobile applications,
source control, logs, or generated examples. For example, configure
`PESAGUARD_API_KEY` on your backend, then make a server-to-server request:

New keys use a tier-specific prefix: `pgk_dev_`, `pgk_sbx_`, `pgk_stg_`, or
`pgk_live_`. Treat that prefix as a visual environment cue, not an authorization
mechanism; the server resolves the full key to its persisted environment and
enforces the environment host and data boundary.

When Developer Platform-to-pipeline synchronization is enabled, the same key
is synchronized as a SHA-256 digest (never as plaintext), with its
scopes, expiry, IP allowlist, lifecycle state, and exact organization/project/
environment binding. Send it using `Authorization: Bearer <key>` or
`X-API-Key: <key>`. Pipeline records are partitioned by the exact project and
environment, so organization peers and legacy unpartitioned records are not
visible through that key. Configure `PESAGUARD_PIPELINE_KEY_SYNC_URL` and the
same independently generated, minimum-32-byte
`PESAGUARD_PIPELINE_KEY_SYNC_SECRET` in both services. Production must set
`PESAGUARD_PIPELINE_KEY_SYNC_REQUIRED=true`; a sync failure then fails the
credential lifecycle request rather than silently leaving services divergent.

```bash
curl --request GET \
  "https://sandbox-api.pesaguard.co.ke/api/v1/sandbox/transactions" \
  --header "Accept: application/json" \
  --header "Authorization: Bearer ${PESAGUARD_API_KEY}"
```

Use the production Base URL for a Production environment and only after
production access is active. Do not copy Sandbox credentials to Production or
the reverse. The Developer Platform displays the server-assigned URL, auth
method, endpoint, and matching integration example after environment selection.

## Passkeys

Passkeys are implemented with Yubico's WebAuthn server library. The relying party
checks registration and assertion challenges, signatures, user verification,
RP ID, origin, attestation, and signature counters. The database stores each
credential's COSE public key, credential ID, stable user handle, counter, backup
state, display name, and timestamps. Ceremony request JSON is server-generated,
short-lived, bound to its purpose and known user/workspace, and atomically
consumed before the response is parsed or verified.

Authenticated users can add and list their own passkeys. Removal requires a
fresh current-password proof and is scoped to a credential owned by that user.
Passkey-based normal sign-in is disabled so it cannot bypass mandatory email
verification. Registration and assertion ceremonies validate their relying
party, origin, challenge, user verification, and credential ownership; secrets
and credential response data are not logged.

Configure the WebAuthn relying party explicitly in every deployment:

| Variable | Purpose |
|---|---|
| `PESAGUARD_WEBAUTHN_RP_ID` | DNS RP ID, without scheme or port; e.g. `developers.pesaguard.com` |
| `PESAGUARD_WEBAUTHN_RP_NAME` | Human-readable name shown by authenticators |
| `PESAGUARD_WEBAUTHN_ORIGINS` | Comma-separated exact frontend origins, including scheme and port if any |
| `PESAGUARD_WEBAUTHN_CHALLENGE_TTL` | Ceremony lifetime, at most ten minutes; defaults to `PT3M` |

The safe local defaults are RP ID `localhost` and origin
`http://localhost:5173`. Production origins must use HTTPS and match the RP ID.
Set the existing `PESAGUARD_ALLOWED_ORIGINS` CORS list to the same portal
origin(s). Never derive RP ID or accepted origins from request `Host` or
`Origin` headers.

## The principal

`AuthenticatedUser` is the only thing downstream code authenticates against.

| Field | Source | Why it is here |
|---|---|---|
| `userId` | JWT `sub` | Identity |
| `sessionId` | JWT `jti` | Which session; revocation target |
| `organizationId` | JWT `org` | Tenancy — every query is scoped through it |
| `authorities` | JWT `rol` | Role, for `AccessDecisionService` |
| `organizationStatus` | Defaulted | Checked by `TenantGuard` |

Nothing downstream may re-derive identity from headers, cookies or request
parameters. A user-controlled `tenant_id` in a body is never trusted.

## Password hashing

**Argon2id** (`Argon2PasswordEncoder`), defaulting to 19 MiB, 2 iterations,
1 lane — the OWASP first-pass baseline.

Argon2id is memory-hard, so an attacker with GPU or ASIC advantage gains far
less per dollar than against BCrypt, which is CPU-bound only. The memory figure
is the security-critical parameter: it is what makes parallel guessing
expensive, and it should be re-measured against real hardware rather than
assumed.

**BCrypt is retained only as a decode path.** The encoder is a
`DelegatingPasswordEncoder` defaulting to `argon2` that also accepts `bcrypt`,
so pre-migration hashes still verify. Because the BCrypt encoder reports its own
parameters as stale via `upgradeEncoding`, every successful sign-in silently
rewrites the hash to Argon2id. The user base converts as people log in, without
a batch job that would strand accounts nobody ever returns to.

Never store a password, only a hash. There is no `password` column.

## Password policy

`PasswordPolicy` validates length and similarity against the email and display
name. It favours length over composition rules, because arbitrary complexity
requirements push users toward predictable substitutions (`Password1!`) without
adding real entropy.

Support staff can never be asked to confirm a user's password.

## Login sequence

```
Rate limit (IP + account)          RequestThrottleService
      ↓
Find membership by email           OrganizationMembershipRepository
      ↓
Verify password                    PasswordEncoder  ← Argon2id, upgrades on success
      ↓
Check organization status          password auth enabled, IP allowlist
      ↓
Check MFA requirement              MfaService.isEnabled
      ↓
Create session row                 auth_sessions
      ↓
Sign access token                  AccessTokenService  ← RS256, 5 minutes
      ↓
Create refresh family + token      RefreshTokenService  ← SHA-256 at rest
      ↓
Audit + security event             "auth.login.succeeded"
```

### Enumeration resistance

Every failure below returns the **same** 401 with the same body. A caller cannot
distinguish an unknown email from a wrong password, a suspended account, or a
locked one:

| Situation | Response |
|---|---|
| Unknown email | 401 `INVALID_CREDENTIALS` |
| Wrong password | 401 `INVALID_CREDENTIALS` |
| Suspended account | 401 `INVALID_CREDENTIALS` |
| Locked out | 401 `INVALID_CREDENTIALS` |
| Email MFA required | 401 `LOGIN_EMAIL_MFA_REQUIRED` — includes the challenge metadata and issues no session |

Password verification is still performed against a dummy hash when the email is
unknown, so response *timing* does not leak account existence either. Skipping it
would make "unknown user" measurably faster than "wrong password".

## Brute-force protection

Layered, because no single control is sufficient:

| Layer | Scope | Why |
|---|---|---|
| IP rate limit | Address | Stops one host spraying many accounts |
| Account limit | Email | Stops one account sprayed from many hosts |
| Progressive delay | Account | Makes a slow guess costly per attempt |
| Email MFA | Account | A six-digit, expiring code is required after every password check |

**IP-only blocking is not enough** and is not used alone: many legitimate users
share an office or mobile NAT, so an IP-only rule locks out real people while an
attacker rotates addresses freely.

Lockout is temporary and resets on success. There is no permanent lockout from
failed password attempts — that is a denial-of-service primitive an attacker can
aim at any known email address.

## CSRF and cookies

Access and refresh tokens are **bearer credentials held by the portal in
JavaScript memory**. They are not cookies.

CSRF therefore does not apply to this path: a cross-origin page cannot read the
portal's in-memory token or cause it to be attached to a request. `csrf()` is
disabled in `SecurityConfig` for exactly this reason, and the reasoning is
recorded there.

**If cookie authentication is ever introduced, CSRF protection must be added in
the same change.** Shipping cookies without CSRF protection is how a logged-in
developer's portal gets driven by a third-party page. Cookies would also need
`Secure; HttpOnly; SameSite`.

## Rate limiting

`RateLimitFilter` runs at `HIGHEST_PRECEDENCE + 20` and returns real 429s with
`X-RateLimit-*` headers. Counters live in Redis, so limits are enforced across
instances rather than per pod.

Sensitive endpoints get tighter budgets than ordinary ones: `/login`,
`/refresh`, `/forgot-password`, `/reset-password` and `/mfa/*` versus `/me` or
`/sessions`. See [rate-limiting.md](rate-limiting.md).

## Sensitive account-security actions

Disabling MFA requires the current password and, when a confirmed factor is
enabled, a fresh TOTP or unused recovery code. The server consumes the submitted
MFA proof before removing the factor and records the change in the audit trail.
`POST /api/v1/auth/sessions/revoke-others` revokes every other active session,
its refresh-token family, and its access token while keeping the caller's current
session.

## Secrets management

No secret is committed. Keys are read from the environment:

| Variable | Purpose |
|---|---|
| `PESAGUARD_CREDENTIAL_HMAC_KEY` | Hashes stored credentials |
| `PESAGUARD_AUDIT_HMAC_KEY` | Hashes audit-chain links |
| `PESAGUARD_OPERATOR_HMAC_KEY` | Internal operator tokens — **a separate key** |
| `PESAGUARD_JWT_PRIVATE_KEY` | RS256 signing |
| `PESAGUARD_JWT_PUBLIC_KEY` | RS256 verification |

The operator key is separate on purpose: sharing one with the credential key
would make a developer API key an operator token by formatting alone.

Missing or malformed key material **fails at startup**, never lazily at the first
authentication attempt during an incident.

## What is logged

Structured logs carry `request_id`, `tenant_id`, `service`, `operation`,
`status`, `duration`, `error_code`.

Never logged: passwords, tokens, MFA secrets, backup-code plaintext,
authorization headers, cookies.

Security events and audit records answer *who / what / when / where / device /
IP / request id / result / reason* — the question set that makes an incident
reviewable after the fact.

## Account lifecycle and personal data export

The authenticated, self-only account endpoints use the identity in the access
token; they accept no user id:

| Endpoint | Behavior |
|---|---|
| `GET /api/v1/account/lifecycle` | Returns account status and, while pending, the scheduled deletion time. |
| `POST /api/v1/account/deactivate` | Requires `currentPassword` and, when MFA is enabled, a fresh `mfaCode`. Stops new authentication and revokes sessions, refresh families, MFA/recovery/reset credentials, and API keys created by this user. |
| `POST /api/v1/account/deletion` | Requires step-up proof and starts a cancelable 30-day grace period. |
| `POST /api/v1/account/deletion/cancel` | Requires step-up proof and cancels a pending deletion during the grace period. |
| `POST /api/v1/account/export` | Requires step-up proof and returns a no-store JSON attachment. |

Step-up request bodies have the shape
`{"currentPassword":"…","mfaCode":"…"}`. `mfaCode` may be omitted when
MFA is not enabled; otherwise it must be a fresh TOTP or unused recovery code.
The export includes the caller's profile, membership metadata, and metadata for
API keys they created. It never includes passwords, tokens, hashes, MFA values,
or organization project/resource payloads.

Deletion completion runs after 30 days. Completion revokes the user's credentials
and transactionally removes the account, every organization the account owned or
belonged to, all organization-scoped records (including records belonging to
other members), and linked append-only history. Other members' user accounts and
data outside those organizations remain. Platform-wide support articles remain
available with the deleted author's reference removed. A failed purge rolls back
as a unit and can be retried by the deletion scheduler.
