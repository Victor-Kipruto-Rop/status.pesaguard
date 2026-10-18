create table developer_preferences (
    user_id uuid primary key references users(id) on delete cascade,
    request_timeout_ms integer not null default 30000,
    retry_count integer not null default 0,
    updated_at timestamptz not null default now(),
    constraint developer_preferences_timeout_check check (request_timeout_ms between 1000 and 60000),
    constraint developer_preferences_retry_check check (retry_count between 0 and 5)
);

create table platform_changelog_entries (
    id uuid primary key,
    version varchar(64) not null,
    title varchar(160) not null,
    body text not null,
    category varchar(24) not null,
    status varchar(16) not null,
    published_at timestamptz,
    created_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint platform_changelog_category_check check (category in ('FEATURE', 'IMPROVEMENT', 'FIX', 'SECURITY')),
    constraint platform_changelog_status_check check (status in ('DRAFT', 'PUBLISHED')),
    constraint platform_changelog_published_check check (
        (status = 'DRAFT' and published_at is null)
        or (status = 'PUBLISHED' and published_at is not null)
    )
);

create index platform_changelog_published_idx
    on platform_changelog_entries(published_at desc)
    where status = 'PUBLISHED';
