-- Phase 15: developer security centre.
--
-- Records security signals against an organization. A signal is an observation,
-- never a verdict: nothing here asserts that a compromise occurred, and a
-- detector can never resolve its own finding.

create table security_events (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    type varchar(48) not null,
    -- The credential, session, or endpoint concerned. Deliberately not a foreign
    -- key: these are different id spaces, and the referenced row is frequently
    -- already deleted, which is exactly when the signal matters most.
    subject_id uuid,
    subject_kind varchar(32),
    detail varchar(2000),
    -- When it was observed, not when it was stored, so a detection backlog does
    -- not report old findings as new.
    detected_at timestamptz not null,
    resolution varchar(24) not null default 'OPEN',
    resolved_by uuid references users(id),
    resolved_at timestamptz,
    resolution_note varchar(1000),
    recorded_at timestamptz not null default now(),
    constraint security_events_type_check check (type in (
        'REVOKED_CREDENTIAL_USAGE', 'ALLOWLIST_VIOLATION', 'ABNORMAL_API_USAGE',
        'TOKEN_REPLAY', 'REPEATED_FAILURES', 'SUSPICIOUS_WEBHOOK_ACTIVITY',
        'SCOPE_ABUSE', 'AUTHORIZATION_FAILURE'
    )),
    constraint security_events_resolution_check check (
        resolution in ('OPEN', 'DISMISSED', 'CONFIRMED', 'INVESTIGATING')
    ),
    -- A resolution must be attributable. A closed finding with no actor is one
    -- nobody can be held to, and is indistinguishable from a detector having
    -- quietly closed it.
    constraint security_events_resolution_actor_check check (
        resolution = 'OPEN'
        or (resolved_by is not null and resolved_at is not null and resolution_note is not null)
    ),
    -- Time must not run backwards: a resolution cannot predate its detection.
    constraint security_events_resolution_order_check check (
        resolved_at is null or resolved_at >= detected_at
    )
);

create index if not exists security_events_org_open_idx
    on security_events(organization_id, detected_at desc)
    where resolution = 'OPEN';

create index if not exists security_events_org_idx
    on security_events(organization_id, detected_at desc);

create index if not exists security_events_subject_idx
    on security_events(organization_id, subject_id, detected_at desc)
    where subject_id is not null;

-- A detector that re-fires the same signal every request would bury a real one
-- in noise. This is the rate limit on repetition of an identical finding.
create unique index if not exists security_events_dedup_idx
    on security_events(organization_id, type, subject_id, detected_at)
    where subject_id is not null;

-- Sessions gain the device facts the security centre reports, and the address
-- they were last seen from. Kept coarse on purpose: an exact user-agent string
-- is untrusted, unbounded, and can contain markup.
alter table auth_sessions
    add column if not exists device_label varchar(64),
    add column if not exists last_ip varchar(45);

-- Session history: a revocation must be retained rather than the row deleted, or
-- "was I signed in from that machine?" is unanswerable after the fact.
create table auth_session_revocations (
    id uuid primary key,
    session_id uuid not null,
    organization_id uuid not null references organizations(id),
    revoked_by uuid references users(id),
    reason varchar(500),
    device_label varchar(64),
    last_ip varchar(45),
    revoked_at timestamptz not null,
    constraint auth_session_revocations_reason_present check (
        reason is not null and reason <> ''
    )
);

create index if not exists auth_session_revocations_org_idx
    on auth_session_revocations(organization_id, revoked_at desc);

create index if not exists auth_session_revocations_session_idx
    on auth_session_revocations(session_id);