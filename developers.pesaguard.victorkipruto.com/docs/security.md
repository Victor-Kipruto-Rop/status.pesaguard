# Security notes

## Secrets and keys

- `.env.example` is a template only; real credentials belong in a secret manager or protected runtime environment.
- `PESAGUARD_CREDENTIAL_HMAC_KEY` and `PESAGUARD_AUDIT_HMAC_KEY` must be independent, valid Base64 values decoding to at least 32 bytes.
- Passwords are bcrypt-hashed. Plaintext passwords are never persisted or logged.
- Session tokens are stored only as SHA-256 hashes.
- Invitation tokens are stored only as SHA-256 hashes; the raw token is returned once, in the creation response, and must be handled like a password.
- API-key secrets are stored as an HMAC using the credential key. The raw value is disclosed only in the issue response and must be treated like a password.
- Never put bearer tokens, API-key secrets, passwords, private keys, or complete authorization headers in logs, audit metadata, URLs, source control, or client-side storage.
- API-key scopes are allowlisted per implemented API capability. Scope definitions reserved for unfinished payments, provider integrations, reconciliation, risk, settlement, reporting, or job APIs are visible as unavailable and cannot be assigned. API-key data routes derive organization, project, and environment exclusively from the authenticated key; context responses omit credentials, secret material, configuration, and infrastructure details.

## Organization security settings

Each organization owns one `organization_security_settings` row, created at registration, organization creation, and lazily on first read. It controls:

- allowed authentication methods — only `PASSWORD` is accepted today; `MFA`/`OIDC` values are rejected rather than silently stored;
- session TTL, idle timeout, and the maximum number of concurrent sessions per membership (the least recently seen sessions are revoked when the limit is exceeded);
- the credential length policy applied when a password is set (registration and invitation acceptance);
- an optional IPv4/IPv6 CIDR allowlist enforced at login;
- the security event types the organization subscribes to.

Enabling `mfaRequired` is rejected with `UNSUPPORTED_SECURITY_POLICY` until an MFA provider exists. Storing a policy that cannot be enforced would be a silent security downgrade, so the API fails closed instead. Credential length policy is applied when a credential is created, not retroactively at login: tightening a minimum length must not lock out existing users who already hold a valid credential.

## Authentication and authorization

The security filter chain is stateless. Public registration, login, and invitation acceptance are reachable without a session; every other route requires a valid, unexpired, unrevoked session whose membership is active and whose organization is active. Method security restricts lifecycle, security-settings, membership, and invitation operations by role (`OWNER`, `ADMIN`, or member), and the services re-check the same rules against the database rather than trusting the filter alone.

The authenticated organization is the only tenant source. Login automatically
uses the account's last-accessed active organization, falling back to an active
membership if no saved selection is available. Login may carry an
`organizationId` to select another of that account's own memberships; it is
treated as a selection, never as an authority. An unknown selection returns the
same `INVALID_CREDENTIALS` response as a wrong password so membership cannot be
probed.

## Error and logging behavior

Clients receive structured, non-sensitive errors with a request ID. Unexpected exceptions are logged with a request ID and type, without returning stack traces or internal details. Operational logs should be structured and redacted at the logging boundary.

## Incident actions

If a credential or key is exposed:

1. treat it as compromised;
2. stop further disclosure and preserve relevant request IDs/audit evidence;
3. revoke the affected session/API key;
4. rotate the credential through the approved secret-management process;
5. audit usage and investigate access;
6. document the removal and any required data notification.

Do not delete audit or financial records to hide an incident. Use approved retention/legal procedures.
