create table developer_onboarding_progress (
    user_id uuid primary key references users(id) on delete cascade,
    current_step varchar(40) not null default 'welcome',
    completed_steps jsonb not null default '[]'::jsonb,
    skipped boolean not null default false,
    completed boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint developer_onboarding_current_step_check check (
        current_step in ('profile', 'welcome', 'organization', 'project', 'environment', 'api-key',
            'first-request', 'api-explorer', 'webhook', 'documentation',
            'production-readiness', 'complete')
    ),
    constraint developer_onboarding_completed_steps_array_check
        check (jsonb_typeof(completed_steps) = 'array')
);
