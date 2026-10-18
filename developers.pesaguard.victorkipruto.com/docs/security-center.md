# Developer Security Center (Phase 15)

Status: **DOMAIN AND PERSISTENCE ONLY — NO DETECTION RUNS, NO ENDPOINTS.**
The classification, posture, and emergency-action logic are implemented and
unit-tested. **V15 has never been executed**, so the new tables and constraints
are unverified. No detector invokes any of this, so nothing is currently
recorded or displayed.

## The central design decision

**A security signal is an observation, never a verdict.** Nothing in this phase
declares that a compromise occurred, that fraud happened, or that a person
misbehaved. Every value in `SecurityEventType` names something *seen*.

This is why there is deliberately **no severity ranking**. Ranking requires
context the platform does not have, and a fixed ordering would imply a
confidence the data does not support.

`isSerious()` exists instead, and marks only three categories where a genuine
hit is a containment failure rather than a misconfiguration:
`AUTHORIZATION_FAILURE`, `REVOKED_CREDENTIAL_USAGE`, `TOKEN_REPLAY`.

`isCommonlyBenign()` is used to **prioritise, never to suppress**. A suppressed
signal is one nobody can investigate later.

## Detection never resolves

`SecurityEvent.Record.detected(...)` has no overload accepting a resolution. It
is not possible to construct a pre-resolved event.

A detector that closes its own findings makes its silence indistinguishable from
"nothing is wrong" — the one state where a security centre must never be quiet.
Only a named human can move an event out of `OPEN`, and doing so always records
who, when, and why.

`INVESTIGATING` is kept distinct from `CONFIRMED` so an in-progress incident is
never counted as closed, which would let a long-running problem quietly drop off
a dashboard.

## Credential posture

`CredentialPosture` classifies using `ApiKey.effectiveStatus(now)` rather than
the stored status, so a key whose expiry has passed reads as expired before a
sweep has written that down. A dashboard calling a dead key "active" is worse
than one that is slightly stale.

It provides active / expired / revoked / suspended / recently-used / dormant,
plus **suspicious** usage:

- a key recording use **after** it was revoked or expired; and
- an active key whose last use came from a different address than before.

**Honest limit on the first:** the platform cannot see a revoked key that was
used and *then* revoked, because nothing rejected the request in between to
record it. What is caught is use recorded after the credential had already
stopped working — a genuine and actionable subset, not the whole problem.

Dormant keys (no use in 30 days) are surfaced so a developer can find
credentials they no longer need. Every stored key is attack surface whether
anyone remembers it or not.

## Sessions

`SessionDevice` produces a coarse label like "Chrome on Windows" and **never
stores the raw user agent**. A user agent is attacker-controlled, unbounded, and
can contain markup; it would land in a security dashboard.

Two details this implements:

- Markup characters and control characters are **stripped**, not escaped. A
  label containing markup should not survive into a rendered surface at all.
- The string is truncated to 512 chars **for matching** but the output is
  bounded to 64. A shorter match bound was a real bug: Edge advertises "Chrome"
  in its own agent string, so truncating first reported every Edge user as
  Chrome. `SessionDeviceTest.edgeIsNotReportedAsChrome` pins this.

**Honest limit:** device matching is by *family*, not by device. Two different
Windows machines both report "Chrome on Windows", so it detects a change of kind,
not a new physical machine. Claiming more would be a false claim of detection
capability.

Revoked sessions are shown rather than hidden — someone who has been signed out
wants to know what was ended and when, and omitting them makes a mass revocation
look like it did nothing.

## Emergency response

`EmergencyResponse` is deliberately awkward:

- **A reason is required**, always, and is sanitised of control characters so it
  cannot corrupt a single-line audit entry.
- **An empty selection is refused**, not treated as a no-op. A caller that
  computed nothing and reports success has given false assurance at exactly the
  moment false assurance is most damaging.
- **Bulk actions are bounded** (100 keys, 500 sessions) unless explicitly forced.
  A guard against a misclick that revokes an organization's entire production
  estate.
- **Already-terminal keys are excluded** from the affected count. Including them
  overstates the blast radius and makes the response log useless for working out
  what must now be replaced.
- **Credentials are terminated before sessions**, because an API key is what
  unattended software uses. If the process is interrupted, the automation is
  already stopped.

## Permissions

Two new permissions, deliberately separate:

- `security:read` — read own posture and signals
- `security:control` — terminate credentials and sessions

Separate from `AUDIT_READ`: someone who may read the audit log has no reason to
be able to revoke production credentials.

OWNER and SECURITY hold both. ADMIN and ANALYST hold read only. DEVELOPER and
VIEWER hold neither.

## Remaining work

- Two detectors raise signals: unfamiliar-device sign-ins and refresh-token
  replay. API-key misuse, allowlist violations, scope abuse, repeated failures,
  suspicious webhook activity, and abnormal API-usage detectors remain.
- The tenant-scoped controller exposes open signals, full history, and audited
  resolution. OpenAPI documents these routes and their permission requirements.
- Session device and IP fields are now updated by the session service.
- Credential rotation and application suspension actions are still unwired.
- Abnormal-usage detection still needs a reliable per-key history or baseline.
- V15 and the current migration chain still require PostgreSQL-backed execution.
## Decisions to revisit

- **The dedup index includes `detected_at`**, so it only collapses signals
  recorded in the same instant. That is nearly useless for stopping a detector
  re-firing every request; a time-bucketed key would be needed.
- **No alert integration.** A confirmed `AUTHORIZATION_FAILURE` should page
  someone. Right now it is a row nobody is watching.
- **Suspension has no independent expiry** in this design, unlike the Phase 14
  production grant.