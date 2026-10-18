create sequence feedback_public_reference_seq start with 1 increment by 1;

create table feedback (
    id uuid primary key,
    public_reference varchar(24) not null unique,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    requester_display_name varchar(120) not null,
    requester_email varchar(320) not null,
    project_id uuid,
    environment_id uuid,
    type varchar(32) not null,
    title varchar(120) not null,
    description varchar(8000) not null,
    priority varchar(16) not null default 'NORMAL',
    status varchar(24) not null default 'NEW',
    page_url varchar(1000),
    route varchar(500),
    browser varchar(200),
    operating_system varchar(200),
    application_version varchar(100),
    frontend_version varchar(100),
    request_id varchar(100),
    correlation_id varchar(100),
    assigned_team varchar(32),
    assigned_operator_id uuid,
    resolved_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint feedback_type_check check (type in (
        'SUGGESTION', 'FEATURE_REQUEST', 'BUG', 'COMPLAINT', 'DOCUMENTATION',
        'DEVELOPER_EXPERIENCE', 'OTHER'
    )),
    constraint feedback_priority_check check (priority in ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
    constraint feedback_status_check check (status in (
        'NEW', 'ACKNOWLEDGED', 'REVIEWING', 'PLANNED', 'IN_PROGRESS',
        'RESOLVED', 'CLOSED', 'REJECTED'
    )),
    constraint feedback_assignment_team_check check (
        assigned_team is null or assigned_team in (
            'SUPPORT', 'ENGINEERING', 'SECURITY', 'DEVELOPER_RELATIONS', 'PRODUCT'
        )
    ),
    constraint feedback_title_present check (btrim(title) <> ''),
    constraint feedback_description_present check (btrim(description) <> ''),
    constraint feedback_dates_consistent check (
        (status = 'RESOLVED' and resolved_at is not null)
        or (status <> 'RESOLVED' and resolved_at is null)
    ),
    constraint feedback_project_org_fk
        foreign key (project_id, organization_id) references projects(id, organization_id),
    constraint feedback_environment_project_org_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);

create index feedback_organization_user_updated_idx
    on feedback(organization_id, user_id, updated_at desc);
create index feedback_user_updated_idx on feedback(user_id, updated_at desc);
create index feedback_organization_status_updated_idx
    on feedback(organization_id, status, updated_at desc);
create index feedback_created_idx on feedback(created_at desc);
create index feedback_project_idx on feedback(project_id, created_at desc);
create index feedback_environment_idx on feedback(environment_id, created_at desc);
create index feedback_type_priority_idx on feedback(type, priority, created_at desc);
create index feedback_priority_updated_idx on feedback(priority, updated_at desc);
create index feedback_assigned_team_updated_idx on feedback(assigned_team, updated_at desc);
create index feedback_assigned_operator_idx on feedback(assigned_operator_id, updated_at desc);
create table feedback_comments (
    id uuid primary key,
    feedback_id uuid not null references feedback(id) on delete cascade,
    author_id uuid not null,
    author_display_name varchar(120) not null,
    author_role varchar(16) not null,
    body varchar(4000) not null,
    visibility varchar(16) not null,
    created_at timestamptz not null,
    constraint feedback_comments_role_check check (author_role in ('DEVELOPER', 'OPERATOR')),
    constraint feedback_comments_visibility_check check (visibility in ('PUBLIC', 'INTERNAL')),
    constraint feedback_comments_visibility_author_check check (
        visibility = 'PUBLIC' or author_role = 'OPERATOR'
    ),
    constraint feedback_comments_body_present check (btrim(body) <> '')
);

create index feedback_comments_feedback_created_idx on feedback_comments(feedback_id, created_at);
create index feedback_comments_author_idx on feedback_comments(author_id, created_at desc);

create table feedback_events (
    id uuid primary key,
    feedback_id uuid not null references feedback(id) on delete cascade,
    actor_id uuid not null,
    actor_type varchar(16) not null,
    event_type varchar(40) not null,
    old_value varchar(200),
    new_value varchar(200),
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    constraint feedback_events_actor_type_check check (actor_type in ('DEVELOPER', 'OPERATOR')),
    constraint feedback_events_metadata_object_check check (jsonb_typeof(metadata) = 'object')
);

create index feedback_events_feedback_created_idx on feedback_events(feedback_id, created_at);

create function prevent_feedback_event_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'feedback event history is immutable';
end;
$$;

create trigger feedback_events_immutable
    before update or delete on feedback_events
    for each row execute function prevent_feedback_event_mutation();

create table feedback_create_idempotency (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    feedback_reference varchar(24) references feedback(public_reference),
    created_at timestamptz not null,
    constraint feedback_idempotency_key_present check (btrim(idempotency_key) <> '')
);

create unique index feedback_create_idempotency_owner_key_idx
    on feedback_create_idempotency(organization_id, user_id, idempotency_key);

create table feedback_rate_limits (
    user_id uuid not null references users(id),
    action varchar(24) not null,
    window_started_at timestamptz not null,
    request_count integer not null,
    primary key (user_id, action, window_started_at),
    constraint feedback_rate_limits_action_check check (action in ('CREATE', 'COMMENT')),
    constraint feedback_rate_limits_count_check check (request_count > 0)
);
