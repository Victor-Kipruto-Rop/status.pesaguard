# OAuth 2.0 Authorization Code Flow

PesaGuard issues third-party API access through an OAuth 2.0 authorization-code
server with PKCE. This document describes the flow as it is actually implemented.
Where the implementation is narrower than a specification allows, that is stated
rather than glossed over.

## What is supported, and what is not

Supported:

- Authorization code grant with mandatory PKCE (`S256` only)
- Refresh-token grant with rotation and reuse detection
- Confidential clients only, authenticated with HTTP Basic
- Token revocation (RFC 7009) and introspection (RFC 7662)
- Explicit, per-request user consent

Not supported, deliberately:

- The implicit and password grants. Both are deprecated or unsafe and neither is
  needed by a server-side or native client that can hold a secret.
- Public clients. Every application is confidential. If you need a native or
  SPA client, it must operate a backend that holds the secret.
- `plain` PKCE. `S256` is mandatory; `plain` is rejected at authorization time.

## Credentials are HMAC-only in storage

Client secrets, authorization codes, access tokens and refresh tokens are stored
only as HMAC-SHA256 digests. The raw value is returned exactly once — at creation,
at rotation, or at issuance — and cannot be recovered from the database
afterwards. Losing a client secret means rotating it, not retrieving it.

Token and secret responses carry `Cache-Control: no-store` so intermediaries do
not retain them.

## Application lifecycle

```
PENDING_VERIFICATION --verify--> ACTIVE --suspend--> SUSPENDED --resume--> ACTIVE
                                       |
                                       +------revoke----> REVOKED (terminal)
```

An application in `PENDING_VERIFICATION`, `SUSPENDED` or `REVOKED` cannot
authorize. `REVOKED` is terminal and cannot be resumed.

Redirect URIs are validated at registration time, not only at authorization
time, so a bad registration fails immediately rather than breaking a live
integration later. Matching is **exact string equality**: no prefix, subdomain or
wildcard matching. `https` is required except for loopback hosts, which RFC 8252
permits over `http` for native apps. Fragments and embedded credentials are
rejected.

## The flow

### 1. Authorization request

`POST /api/v1/oauth/authorize` with a signed-in portal session. This records
intent only — **no code is minted here**.

```json
{
  "clientId": "pgo_...",
  "redirectUri": "https://app.example.com/callback",
  "responseType": "code",
  "scope": "payments:read",
  "state": "opaque-csrf-token",
  "codeChallenge": "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
  "codeChallengeMethod": "S256"
}
```

The response is a pending consent request. The user has granted nothing yet.

`state` is stored twice: as an HMAC (the integrity anchor) and in plaintext. The
plaintext copy exists solely so the server can echo the CSRF token back on the
approval redirect. It is not a credential and is never used to authenticate
anything.

### 2. Consent

```
GET  /api/v1/oauth/consents                list pending requests
POST /api/v1/oauth/consents/{id}/approve   mint the code
POST /api/v1/oauth/consents/{id}/deny      refuse
```

Approval is the **only** operation that issues an authorization code. It returns
the raw code once, plus the `state` echoed back and a fully-built `redirectUrl`
the consent screen can navigate to directly.

### 3. Code exchange

`POST /api/v1/oauth/token` with `grant_type=authorization_code`. Authenticate with
HTTP Basic; do not put the secret in the URL.

```bash
curl -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=authorization_code \
  -d code="$CODE" \
  -d redirect_uri="https://app.example.com/callback" \
  -d code_verifier="$VERIFIER" \
  https://api.pesaguard.com/api/v1/oauth/token
```

The exchange validates, in order: client authentication, code ownership by this
client, exact redirect URI equality, single use, expiry, then the PKCE verifier
against the stored challenge. A verifier or redirect mismatch does **not** consume
the code.

### 4. Refresh with rotation

Every refresh mints a new refresh token and marks the old one spent. Presenting a
token that is already spent or revoked is treated as theft: the **entire token
family is revoked**, along with the family's access tokens.

Rotation persists exactly one successor per use. Concurrent refreshes are
serialized by a pessimistic row lock on the presented token, so two simultaneous
requests cannot both pass the replay check.
## Replay and theft response

| Event | Response |
| --- | --- |
| Authorization code presented twice | Grant revoked; exchange fails |
| Refresh token presented after rotation | Whole family revoked |
| Refresh token presented after explicit revocation | Whole family revoked |
| Wrong PKCE verifier | Rejected; the code stays usable |

Redemption uses `SELECT ... FOR UPDATE` so a check-then-consume race cannot mint
two sets of tokens from one code.

## Revocation and introspection

`POST /api/v1/oauth/revoke` follows RFC 7009: revoking an unknown or already
revoked token returns success, so the endpoint cannot be used to probe whether a
token exists.

`POST /api/v1/oauth/introspect` follows RFC 7662 and requires client
authentication. A client can only introspect tokens belonging to itself. An
unrecognised token returns `active: false` rather than an error.

Both endpoints read credentials from the HTTP Basic `Authorization` header. They
do not accept credentials as query parameters: URLs are written to access logs,
proxy logs and browser history, and a client secret must not be written to any of
them.

## Throttling

`/token`, `/revoke` and `/introspect` are unauthenticated at the transport layer
and are bounded per source address by `RequestThrottleService`. Authorization and
consent decisions require a portal session and are not rate-limited the same way.

## Permissions

Application management uses dedicated permissions, not the webhook ones:

| Permission | Governs |
| --- | --- |
| `oauth_application:read` | List applications |
| `oauth_application:create` | Register an application |
| `oauth_application:update` | Edit an application, verify |
| `oauth_application:rotate` | Rotate a client secret |
| `oauth_application:suspend` | Suspend and resume |
| `oauth_application:revoke` | Revoke an application |

`OWNER`, `ADMIN` and `DEVELOPER` hold all six. `SECURITY`, `ANALYST` and `VIEWER`
hold none, which is the intended separation: managing OAuth clients is a
developer-portal action, not an audit action.

## What rotating, suspending or revoking does

All three operations revoke **every live grant the application holds, across
every user that ever consented to it** — both access and refresh tokens. Scoping
this to the application's creator would leave tokens issued to consenting users
alive, which would defeat the purpose of revoking.

## Known limitations

- **Project and environment binding is not implemented.** An application is
  currently scoped to an organization, not to a specific project and environment
  pair. A token issued through one application cannot be distinguished by project
  or environment at authorization time. This should be added before
  multi-environment customers depend on scope separation.
- **No browser-facing consent screen.** The consent endpoints are API-only. A
  portal front end must render the screen and perform the redirect; the server
  returns the ready-made `redirectUrl` but does not itself render HTML or issue a
  302.
- **No database-backed integration tests executed.** The OAuth tests in the
  repository are unit tests against domain objects and mocked repositories. The
  pessimistic-lock behaviour and the composite tenant constraints in `V6` have not
  been exercised against a real PostgreSQL instance, because Docker is unavailable
  in the development environment. Treat concurrency behaviour as unverified until
  those tests run against PostgreSQL.