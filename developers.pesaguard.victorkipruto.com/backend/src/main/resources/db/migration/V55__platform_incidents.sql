-- Public-safe platform incident lifecycle. Incident details are never attached
-- to a tenant; visibility is explicit and updates retain their operator actor.
create table platform_incidents (
    id uuid primary key,
    title varchar(180) not null,
    severity varchar(16) not null,
    status varchar(24) not null,
    affected_services jsonb not null default '[]'::jsonb,
    summary text not null,
    public_visible boolean not null default false,
    started_at timestamptz not null,
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint platform_incidents_severity_check check (severity in ('MINOR', 'MAJOR', 'CRITICAL')),
    constraint platform_incidents_status_check check (status in ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED')),
    constraint platform_incidents_resolution_check check (
        (status = 'RESOLVED' and resolved_at is not null) or (status <> 'RESOLVED' and resolved_at is null)
    )
);
create index platform_incidents_public_active_idx on platform_incidents(public_visible, status, started_at desc);

create table platform_incident_updates (
    id uuid primary key,
    incident_id uuid not null references platform_incidents(id),
    message text not null,
    public_visible boolean not null default false,
    actor_id uuid not null,
    actor_subject varchar(200) not null,
    action_reason varchar(500) not null,
    created_at timestamptz not null default now()
);
create index platform_incident_updates_incident_idx on platform_incident_updates(incident_id, created_at asc);
