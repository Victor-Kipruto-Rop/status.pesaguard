# Authentication Risk Alerts

## Scope

Use this runbook for `PesaGuardAuthenticationRiskBlocksHigh` or `PesaGuardAuthenticationStepUpHigh`, unexpected login denials, or suspected credential compromise. Risk metrics use fixed labels and intentionally do not identify accounts, tenants, IPs, or devices; use protected audit records for investigation.

## Triage

1. Confirm the alert window, application versions, active tenant risk-policy revisions, and whether a rollout or trusted-proxy configuration changed.
2. Review authentication audit outcomes for `login.risk_blocked`, `login.risk_step_up_required`, `login.failed`, `login.account_locked`, `login.brute_force_detected`, and `login.credential_stuffing_detected`. Use the recorded correlation/request identifiers and approved restricted audit tooling; do not copy credentials or raw tokens into incident notes.
3. Check device history, recent sessions, tenant-scoped active lockouts at `GET /auth/account/lockouts`, failed-attempt counts, credential-compromise indicators, and approved suspicious-IP intelligence. Treat client-provided location data as untrusted unless the trusted geo-header boundary is enabled and verified.
4. Compare the signals and risk actions with the tenant's configured weights, thresholds, and actions. Record any false-positive analysis and the operator who approved a policy change.

## Response

- For a confirmed compromised identity, follow account recovery, reset the credential, require MFA, and revoke affected sessions/devices. Preserve audit evidence.
- Use `POST /auth/account/unlock` only after reviewing the lockout and validating the recovery decision. It requires session-bound MFA under the privileged-operation policy. Verified password reset is the emergency self-service recovery path and automatically clears the temporary lockout while revoking active sessions.
- Keep Redis-backed login throttles enabled in production. The login endpoint fails closed when distributed limiter storage is missing or unavailable; investigate Redis health rather than switching to per-process in-memory protection.
- For a high-risk `review` decision, keep the sign-in blocked until the designated security reviewer records a decision through the approved operational process.
- For an MFA step-up spike without confirmed compromise, investigate shared device/IP patterns and proxy/geo data quality before changing policy thresholds.
- Do not globally weaken risk actions to clear an alert. Scope temporary policy changes to the affected tenant, require an owner and expiry, and audit the change.
- Use emergency global revocation only for a confirmed broad compromise or when incident command authorizes it.

## Recovery and closure

Verify the affected account can recover through a verified credential and MFA path, revoked devices remain denied, and legitimate devices can authenticate. Monitor risk decision counts, 401/403/429/503 rates, and successful logins through a complete observation window. Document impact, evidence, policy changes, follow-up owner, and due date.