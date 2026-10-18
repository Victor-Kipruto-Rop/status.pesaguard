create table developer_onboarding_steps (
    id uuid primary key,
    user_id uuid not null references developer_onboarding_progress(user_id) on delete cascade,
    step_key varchar(40) not null,
    status varchar(24) not null,
    required boolean not null,
    conditional boolean not null default false,
    started_at timestamptz,
    completed_at timestamptz,
    skipped_at timestamptz,
    blocked_reason varchar(500),
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint uq_developer_onboarding_step_user_key unique (user_id, step_key),
    constraint developer_onboarding_step_key_check check (step_key in (
        'ACCOUNT', 'EMAIL_VERIFICATION', 'SECURITY', 'PROFILE', 'ORGANIZATION',
        'PROJECT', 'SANDBOX', 'API_KEY', 'FIRST_API_REQUEST', 'API_EXPLORER',
        'INTEGRATION', 'WEBHOOK', 'TEAM', 'DOCUMENTATION', 'PRODUCTION_READINESS',
        'PRODUCTION_REQUEST', 'PRODUCTION_APPROVAL', 'PRODUCTION_CREDENTIALS', 'COMPLETION'
    )),
    constraint developer_onboarding_step_status_check
        check (status in ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED', 'BLOCKED')),
    constraint developer_onboarding_step_metadata_object_check
        check (jsonb_typeof(metadata) = 'object')
);

create index idx_developer_onboarding_steps_user_status
    on developer_onboarding_steps(user_id, status);

insert into developer_onboarding_steps (
    id, user_id, step_key, status, required, conditional,
    started_at, completed_at, created_at, updated_at, version
)
select gen_random_uuid(), progress.user_id, mapped.step_key, 'COMPLETED',
       mapped.required, mapped.conditional, progress.created_at, progress.updated_at,
       progress.created_at, progress.updated_at, 0
from developer_onboarding_progress progress
cross join lateral jsonb_array_elements_text(progress.completed_steps) completed(step_name)
join lateral (
    values
        ('welcome', 'PROFILE', true, false),
        ('organization', 'ORGANIZATION', true, false),
        ('project', 'PROJECT', true, false),
        ('environment', 'SANDBOX', true, false),
        ('api-key', 'API_KEY', true, false),
        ('first-request', 'FIRST_API_REQUEST', true, false),
        ('api-explorer', 'API_EXPLORER', false, false),
        ('webhook', 'WEBHOOK', false, false),
        ('documentation', 'DOCUMENTATION', false, false),
        ('production-readiness', 'PRODUCTION_READINESS', true, false)
) as mapped(old_name, step_key, required, conditional)
    on completed.step_name = mapped.old_name
on conflict (user_id, step_key) do nothing;

insert into developer_onboarding_steps (
    id, user_id, step_key, status, required, conditional,
    started_at, created_at, updated_at, version
)
select gen_random_uuid(), progress.user_id, mapped.step_key, 'IN_PROGRESS',
       mapped.required, mapped.conditional, progress.updated_at,
       progress.created_at, progress.updated_at, 0
from developer_onboarding_progress progress
join lateral (
    values
        ('profile', 'PROFILE', true, false),
        ('welcome', 'PROFILE', true, false),
        ('organization', 'ORGANIZATION', true, false),
        ('project', 'PROJECT', true, false),
        ('environment', 'SANDBOX', true, false),
        ('api-key', 'API_KEY', true, false),
        ('first-request', 'FIRST_API_REQUEST', true, false),
        ('api-explorer', 'API_EXPLORER', false, false),
        ('webhook', 'WEBHOOK', false, false),
        ('documentation', 'DOCUMENTATION', false, false),
        ('production-readiness', 'PRODUCTION_READINESS', true, false)
) as mapped(old_name, step_key, required, conditional)
    on progress.current_step = mapped.old_name
where progress.completed = false
  and not exists (
      select 1 from developer_onboarding_steps step
      where step.user_id = progress.user_id and step.step_key = mapped.step_key
  )
on conflict (user_id, step_key) do nothing;
