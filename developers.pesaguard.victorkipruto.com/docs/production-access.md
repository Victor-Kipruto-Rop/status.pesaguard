# Production Access & Developer Verification (Phase 14)

Status: **WORKFLOW EXTENDED, NOT DEPLOYED.** The review workflow, suspension,
revocation, and review history are implemented and unit-tested. **V14 has never
been executed** (Docker/PostgreSQL unavailable), so the new states, the altered
CHECK constraints, and the history table are unverified.

## Legacy workflow

This request/review service is retained for compatibility and administrative
history; developers do not need to submit or review a production-access request
to launch. Production API-key traffic is gated by a successful Go-Live launch
for the key's exact project and environment. See [Go-Live](./go-live.md).

## This phase extended an existing system

Production access already existed: a `production_access_requests` table, a
`PENDING -> APPROVED/REJECTED/EXPIRED/CANCELLED` flow, separation of duties
enforced in both the domain and a database check, time-boxed grants capped at 7
days, and audit events on every action.

Rather than build a parallel system, this phase extended it. What was added:

- `UNDER_REVIEW` — a reviewer has claimed the request
- `ACTIVE` — provisioned and live
- `SUSPENDED` — temporarily withdrawn, reversible
- `REVOKED` — permanently withdrawn, terminal
- `production_access_history` — an append-only trail of every transition

## Approval is not activation

Per your decision, approval and activation are separate states:

```
REQUESTED(PENDING) -> UNDER_REVIEW -> APPROVED -> ACTIVE
                            |                        |
                            v                        v
                        REJECTED              SUSPENDED <-> ACTIVE
                                                     |
                                                     v
                                                 REVOKED (terminal)
```

`APPROVED` records that a reviewer said yes. It grants nothing. `ACTIVE` is
reached only by an explicit activation once provisioning has actually succeeded.

The reason is that the two events genuinely diverge: a reviewer approves, then
provisioning fails. Folding both into one state would report a working production
credential that does not exist. `isActiveGrant` returns true **only** for
`ACTIVE`, and `ProductionAccessStatus.permitsTraffic()` makes the same rule
explicit.

Activation re-checks the expiry rather than trusting approval time. A request
approved for 24 hours and activated on day 4 fails and moves to `EXPIRED`, rather
than coming up already-live with a window that has passed.

## Rules the domain enforces

**Separation of duties at every step.** The requester cannot claim their own
request for review, not just approve it. V4's database check
(`reviewed_by <> requested_by`) still applies to the approve and reject paths.

**Revocation is terminal.** There is no transition out of `REVOKED` — not
reactivate, not activate, not begin-review. A revoked grant must be re-requested
and re-reviewed, so whoever revokes cannot quietly undo their own action.

**Suspension and revocation require a reason.** An unexplained stop is
unactionable for whoever has to investigate it, and a database CHECK constraint
enforces it as well as the domain.

**Reactivation does not extend the window.** A suspended grant that resumes is
still bounded by its original expiry; otherwise suspension becomes a way to grant
extra time indefinitely.

**Grants are always time-boxed.** Approval requires an explicit `expiresIn`, at
most 7 days.

## Review history

Every transition writes a `production_access_history` row recording the previous
status, the new status, the actor, a note, and evidence.

The table is **append-only** — a trigger rejects updates and deletes. History that
can be rewritten is not history. A corrected trail is a new entry, not an edit to
what a reviewer originally recorded.

Notes and evidence are stored and returned verbatim. A reviewer citing a ticket or
a control mapping is making an audit claim; paraphrasing it would misrepresent
the record.

## Migration notes (V14)

Two constraints had to be **dropped and recreated**, because PostgreSQL cannot
alter a CHECK in place:

- `production_access_requests_status_check` — V4's list would reject the new states.
- `production_access_requests_review_check` — V4 required `reviewed_at` for any
  status outside PENDING/CANCELLED. `UNDER_REVIEW` and `SUSPENDED` are not
  decisions, so requiring a review timestamp on them would be wrong.

## Not implemented

- **None of the request-detail fields**: application details, organization
  details, intended API usage, requested scopes, requested limits, integration
  information, security information. The request still carries only a free-text
  `reason`. This is the largest gap against the phase.
- **No provisioning.** Activation is a manual API call; nothing actually creates
  a production credential when it succeeds.
- **The request grant is not the traffic gate.** API-key authentication does
  not use `isActiveGrant`; production traffic is gated by the Go-Live launch
  record instead.
- **No evidence capture.** The `evidence` column exists and the history entity
  stores it, but no endpoint accepts it and `recordHistory` always writes null.
- **No OpenAPI route.** The six new controller routes are absent from the spec.
- **No integration tests.** V14 has never run; the constraints, the altered
  checks, and the append-only trigger are all unverified.

## Decisions to revisit

- **Activation is a manual API call.** In practice it should be triggered by
  provisioning completing, so a failed provision can never leave a request
  approved but silently inactive with nobody noticing.
- **No evidence requirement on approval.** A reviewer can approve with no
  evidence recorded. If this phase is about verification, requiring evidence for
  at least some decisions is worth considering.
- **Suspension has no expiry of its own.** A suspended grant resumes or expires
  with the original window. An incident that outlasts the window forces a full
  re-approval, which may be the wrong pressure during an active incident.