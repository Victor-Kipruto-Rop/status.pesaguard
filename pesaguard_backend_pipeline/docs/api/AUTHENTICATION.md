# Authentication API

## POST /api/v1/auth/login

Authentication:
Bearer JWT or session token

Permissions:
None

Request body:

```json
{
  "email": "user@example.com",
  "password": "secret"
}
```

Validation:
- Email is required and must be a valid address.
- Password is required and must satisfy configured policy.

Response:

```json
{
  "access_token": "<jwt>",
  "refresh_token": "<jwt>",
  "token_type": "bearer",
  "expires_in": 900,
  "user": {
    "id": "<uuid>",
    "name": "<name>",
    "email": "<email>",
    "role": "admin"
  },
  "permissions": ["dashboard:read", "transactions:read"]
}
```

Errors:
- 401 invalid credentials
- 423 account locked
- 429 rate limited

Failed sign-ins are rate limited independently by account, source IP, and device. Production deployments must configure Redis for distributed limiter state; if Redis is unavailable or not configured, login fails closed. A device ID may be supplied by the client; otherwise a short-lived limiter identity is derived from the request's user-agent and source address.

Account lockout is temporary and configurable with `PESAGUARD_LOGIN_FAILURE_LIMIT`, `PESAGUARD_LOGIN_FAILURE_WINDOW_MINUTES`, `PESAGUARD_LOGIN_PROGRESSIVE_DELAY_BASE_SECONDS`, `PESAGUARD_LOGIN_PROGRESSIVE_DELAY_MAX_SECONDS`, `PESAGUARD_ACCOUNT_LOCKOUT_MINUTES`, `PESAGUARD_ACCOUNT_LOCKOUT_MAX_MINUTES`, and `PESAGUARD_ACCOUNT_LOCKOUT_ESCALATION_RESET_HOURS`. Retry responses include `Retry-After`; repeated failures receive increasing account-level backoff and repeated lockouts increase their duration up to the configured cap. Successful authentication, verified password recovery, or an authorized administrator unlock clears the active lockout. Verified account recovery revokes existing sessions.

Security administrators with `manage:users` can view current-tenant lockouts through `GET /auth/account/lockouts`. `POST /auth/account/unlock` unlocks a tenant-scoped account and requires a valid session-bound MFA proof when privileged-operation MFA enforcement is enabled. Login lockouts and detection of repeated failures or multi-account attempts are written to authentication audit records and emitted as security events.

## Device management

Device identities are scoped to both tenant and user. A device ID is a client-provided stable identifier; it is not accepted as proof of identity. On successful login the service records a bounded user-agent string, parsed browser and operating system, first/last seen timestamps, last IP, session count, trust state, and revocation state. Device history is visible to the owner; users with `manage:users` can list and manage devices within their tenant.

| Endpoint | Purpose |
|---|---|
| `GET /auth/devices` | List the caller's devices, or tenant devices for `manage:users`. |
| `POST /auth/devices/{device_id}/rename` | Rename a device owned by the caller or managed in the caller's tenant. |
| `POST /auth/devices/{device_id}/trust` | Mark an owned device trusted after valid MFA. |
| `POST /auth/devices/{device_id}/logout` | End all active sessions on one device without permanently revoking it. |
| `POST /auth/devices/{device_id}/revoke` | Permanently revoke a device and invalidate its sessions and refresh-token families. |
| `POST /auth/logout-all` | End every active session for the caller across all devices. |

Revoked device identities remain recorded for investigation and cannot authenticate again. Trust does not bypass MFA or risk policy. Device list and mutation operations are tenant-scoped; a device belonging to another user is not addressable without `manage:users`.

## Risk-based authentication

Successful-password attempts are scored using new device, new location, impossible travel, unusual login time, multiple failed attempts, credential-compromise indicators, suspicious IP, high-risk device/session history, and privileged-operation signals. Risk weights, medium/high thresholds, and actions are configurable through `PESAGUARD_AUTH_RISK_POLICY` or a tenant's `authentication_risk_policy` setting. Medium risk defaults to MFA step-up; high risk defaults to block. Supported actions are `allow`, `require_mfa`, `block`, and `review` (where applicable); a `review` outcome remains blocked pending the approved review workflow. Privileged operations require fresh session-bound MFA by default.

Geo country/coordinate headers are ignored unless `PESAGUARD_TRUST_GEO_HEADERS=1` is set. Enable this only when the application is behind a trusted ingress that strips client-supplied copies and writes authoritative `X-Geo-Country`, `X-Geo-Latitude`, and `X-Geo-Longitude` values. Without trusted geo data, location and impossible-travel signals remain unavailable rather than inferred from untrusted input. Configure suspicious network ranges and compromised addresses in the risk policy; malformed controls are logged and safely ignored or replaced by bounded defaults.

## POST /api/v1/auth/logout

Authentication:
Bearer JWT

Permissions:
None

## POST /api/v1/auth/refresh

Authentication:
Refresh token

Refresh tokens are single-use, rotated on success, and stored only as hashes. Reuse revokes the token family. A refresh token remains bound to its originating session: replacement access and refresh tokens retain that session binding, and revoking or expiring the session invalidates tokens issued from it. Session-state lookup failures reject authentication rather than falling back to an unchecked legacy session flag. Access tokens expire within 15 minutes; `PESAGUARD_ACCESS_TOKEN_TTL_MINUTES` accepts values from 1 through 15.

## POST /auth/client-token

Exchange an IAM service identity ID or confidential API-client identifier and its one-time-issued secret for a short-lived bearer access token.

```json
{
  "tenant_id": "tenant-id",
  "client_id": "client-identifier-or-service-identity-id",
  "client_secret": "<one-time-issued-secret>",
  "scopes": ["transactions:read"]
}
```

`scopes` is optional; when omitted, the token receives all scopes assigned to that identity. Requested scopes cannot exceed the identity grant. Secrets are stored as keyed hashes, and tokens are checked against current identity status and grants on every request. Rotate or revoke service and API-client credentials through the tenant-scoped IAM endpoints.

## POST /api/v1/auth/password-reset

## POST /api/v1/auth/password-change

Authentication:
Bearer JWT

Permissions:
users:write

## GET /api/v1/auth/session

Authentication:
Bearer JWT

Returns current authenticated session and permission set.
