# Developer Platform Audit (Phase 18)

Status: **EXTENDED, NOT REBUILT.** The audit system this phase asks for largely
existed already, and it was good. Two things were genuinely missing and are now
added: the `project` field, and a declared action vocabulary. **V18 has never
been executed.**

## What already existed

A complete audit log was already in place, and this phase did not replace it:

- `audit_events` with a per-organization `sequence_number`
- an **append-only trigger** (`audit_events_append_only`) rejecting UPDATE and
  DELETE
- an **HMAC-SHA256 hash chain**: each event's hash covers its own canonical form
  plus the previous event's hash, so altering or removing a row breaks every
  hash after it
- `verifyChain` in `AuditQueryService`, with constant-time comparison
- a **versioned canonical form** (v1 and v2) — a deliberate extension point
- correlation id, IP address, and user agent, added in V2

Ten of the eleven fields the phase lists were already recorded. Rebuilding this
would have produced a second source of truth about who changed what, which is
precisely the failure mode an audit log exists to prevent.

## What was missing

### 1. `project`

The only required field genuinely absent. Added to the entity, the schema, and —
critically — to the **signed** fields.

`project_id` is nullable on purpose: an organization-level change such as a
membership removal belongs to no project, and storing a placeholder to satisfy a
column would put a false attribution into an evidence record.

### 2. A declared action vocabulary

`action` was free text. That is the quiet failure mode of an audit log:
`"production_acess.requested"` and `"production_access.requested"` are different
strings, so a query for every rejected production request silently misses one of
them. Nothing reports the omission, and the gap is invisible until someone needs
the answer.

`AuditAction` declares 49 actions across the 12 `AuditCategory` values the phase
requires. Three properties are now enforced by construction:

- the action string is **derived** from the constant, so it cannot be misspelled;
- every category has at least one action, asserted by test;
- every action carries a declared default resource type, so an event is not
  filed against the wrong kind of resource by accident.

`parse` returns empty for an unknown value rather than throwing. Audit history
must stay readable after the vocabulary changes: a row that can no longer be
classified is still a row an investigator needs to see.

## The hash chain version bump

Adding `project_id` to the signed fields changes the canonical form, so
`HASH_VERSION` moved from **2 to 3**.

This is the part that would have broken silently. Two changes were required
together:

1. `AuditService` hashes the new field and stamps version 3.
2. `AuditQueryService.canonical` selects the form **per version explicitly**.

The existing code treated "anything that is not v1" as current. Left alone, that
shortcut would have made every v2 row fail verification the moment v3 was added —
and a chain that reports itself tampered after a routine upgrade is
indistinguishable from a real tamper, which is the worst possible failure for
this table. Both branches are now spelled out.

## Backward compatibility

The existing seven-argument `append` is retained as an overload delegating with a
null project, so ~20 call sites are unchanged and organization-level events are
correctly recorded as project-less.

## Not implemented

- **No call sites pass a real `projectId` yet.** The overload exists and is
  available; no service was updated to supply one. Every event currently records
  a null project, so the column is populated but not yet populated *usefully*.
- **No existing service was migrated to `AuditAction`.** Call sites still pass
  hand-written strings such as `"production_access.requested"`. The catalog exists
  and is tested, but nothing enforces that writers use it. **This is the main
  remaining gap.**
- **V18's action-shape constraint is a loose regex**, not an enum, because
  historical rows predate the catalog and rewriting them would damage an
  append-only log.
- **V18 has never executed**, so the chain format change is unverified against a
  real database. The v1/v2 branches have never run against stored rows.
- **No alerting on the chain.** `verifyChain` exists but nothing calls it on a
  schedule, so tampering is only detected when an operator happens to look.

## Decisions to revisit

- **The existing chain has never been verified in anger.** No database has ever
  run, so the v1 and v2 verification branches are untested against real rows.
  Restoring historical rows should be the first thing tested once a database is
  available.
- **No `correlation_id` distinct from `request_id`.** `AuditService` currently
  passes `requestId` for both, so correlation adds nothing yet. It becomes useful
  when a single user action spans several internal requests.
- **Retention is unbounded.** An append-only table that is never pruned grows
  without limit; nothing here addresses how much history is retained or where it
  is archived.