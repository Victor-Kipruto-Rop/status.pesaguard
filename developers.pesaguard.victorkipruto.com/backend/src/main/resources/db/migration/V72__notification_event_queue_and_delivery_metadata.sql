alter table notification_preferences
    drop constraint notification_preferences_category_check;

alter table notification_preferences
    add constraint notification_preferences_category_check check (category in (
        'ACCOUNT', 'ORGANIZATION', 'PROJECT', 'ENVIRONMENT', 'CREDENTIAL', 'API_KEY',
        'API_USAGE', 'WEBHOOK', 'INTEGRATION', 'TEAM', 'BILLING', 'SYSTEM', 'DEVELOPER',
        'PLATFORM', 'USAGE', 'PRODUCTION', 'SUPPORT', 'SECURITY'
    ));

alter table notification_channel_deliveries
    drop constraint notification_channel_delivery_channel_check;

alter table notification_channel_deliveries
    add constraint notification_channel_delivery_channel_check
    check (channel in ('EMAIL', 'IN_APP', 'BROWSER_PUSH', 'MOBILE_PUSH', 'SMS', 'WEBHOOK'));

alter table notifications
    add column severity varchar(16) not null default 'INFO',
    add column category varchar(24) not null default 'SYSTEM',
    add column resource_type varchar(80),
    add column resource_id varchar(160),
    add column action_url varchar(1000),
    add column event_id uuid;

update notifications
set event_id = md5(id::text || created_at::text)::uuid
where event_id is null;

alter table notifications
    alter column event_id set not null;

create unique index notifications_event_id_idx on notifications(event_id);

update notifications n
set category = case
    when n.type in ('API_KEY_CREATED', 'API_KEY_ROTATED', 'API_KEY_REVOKED', 'CREDENTIAL_EXPIRING')
        then 'CREDENTIAL'
    when n.type in ('WEBHOOK_ENDPOINT_FAILING', 'WEBHOOK_REPEATED_DELIVERY_FAILURE',
                    'WEBHOOK_ENDPOINT_DISABLED') then 'WEBHOOK'
    when n.type in ('QUOTA_WARNING', 'QUOTA_EXCEEDED', 'TRAFFIC_SPIKE') then 'USAGE'
    when n.type in ('PRODUCTION_REQUEST_RECEIVED', 'PRODUCTION_REVIEW_STARTED',
                    'PRODUCTION_APPROVED', 'PRODUCTION_REJECTED', 'PRODUCTION_ACTIVATED',
                    'PRODUCTION_REACTIVATED', 'PRODUCTION_SUSPENDED', 'PRODUCTION_REVOKED')
        then 'PRODUCTION'
    when n.type in ('SUPPORT_TICKET_CREATED', 'SUPPORT_TICKET_RESOLVED') then 'SUPPORT'
    else 'SECURITY'
end;

alter table notifications
    add constraint notifications_category_check check (category in (
        'ACCOUNT', 'ORGANIZATION', 'PROJECT', 'ENVIRONMENT', 'CREDENTIAL', 'API_KEY',
        'API_USAGE', 'WEBHOOK', 'INTEGRATION', 'TEAM', 'BILLING', 'SYSTEM', 'DEVELOPER',
        'PLATFORM', 'USAGE', 'PRODUCTION', 'SUPPORT', 'SECURITY'
    )),
    add constraint notifications_severity_check
        check (severity in ('INFO', 'SUCCESS', 'WARNING', 'ERROR', 'CRITICAL', 'SECURITY'));

create table notification_event_queue (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    type varchar(48) not null,
    severity varchar(16) not null,
    subject varchar(200) not null,
    body varchar(4000) not null,
    resource_type varchar(80),
    resource_id varchar(160),
    action_url varchar(1000),
    deduplication_key varchar(200),
    state varchar(16) not null default 'PENDING',
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    last_error varchar(500),
    created_at timestamptz not null default now(),
    processed_at timestamptz,
    constraint notification_event_queue_state_check
        check (state in ('PENDING', 'PROCESSED', 'DEAD_LETTER')),
    constraint notification_event_queue_attempts_check check (attempts >= 0),
    constraint notification_event_queue_subject_check
        check (subject is not null and btrim(subject) <> '')
);

create unique index notification_event_queue_dedup_idx
    on notification_event_queue(organization_id, user_id, type, deduplication_key)
    where deduplication_key is not null;

create index notification_event_queue_ready_idx
    on notification_event_queue(available_at, created_at)
    where state = 'PENDING';

create index notification_event_queue_user_idx
    on notification_event_queue(organization_id, user_id, created_at desc);

alter table notification_channel_deliveries
    alter column channel type varchar(16);
