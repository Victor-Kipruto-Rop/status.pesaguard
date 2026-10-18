-- One usage alert of each type per environment per UTC hour.
create table usage_quota_alerts (
    environment_id uuid not null references project_environments(id) on delete cascade,
    organization_id uuid not null references organizations(id) on delete cascade,
    alert_type varchar(24) not null,
    period_start timestamptz not null,
    created_at timestamptz not null default now(),
    primary key (environment_id, alert_type, period_start),
    constraint usage_quota_alert_type_check
        check (alert_type in ('QUOTA_WARNING', 'QUOTA_EXCEEDED'))
);

create index usage_quota_alerts_retention_idx on usage_quota_alerts(created_at);
