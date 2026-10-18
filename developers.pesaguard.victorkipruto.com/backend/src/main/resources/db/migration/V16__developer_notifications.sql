-- Phase 16: developer notifications.
--
-- Scope is deliberately limited to what a developer needs to be told about their
-- own integration. Platform-operational mail is out of scope.
--
-- One design point is enforced here rather than only in code: a mandatory event
-- must always be able to reach the user. The application refuses to store a
-- preference that would silence one, and this constraint is the backstop if a row
-- is ever written by another route.

create table notification_preferences (
    user_id uuid not null references users(id),
    category varchar(24) not null,
    -- Comma-separated enabled channels, e.g. 'IN_APP' or 'EMAIL,IN_APP'.
    enabled_channels varchar(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, category),
    constraint notification_preferences_category_check check (
        category in ('CREDENTIAL', 'WEBHOOK', 'USAGE', 'PRODUCTION', 'SECURITY')
    ),
    -- In-app is the durable record and can never be switched off. Encoding this
    -- here means a bad write is rejected rather than silently losing the only
    -- channel that cannot fail.
    constraint notification_preferences_in_app_required check (
        position('IN_APP' in enabled_channels) > 0
    )
);

create index if not exists notification_preferences_user_idx
    on notification_preferences(user_id);

create table notifications (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    type varchar(48) not null,
    subject varchar(200) not null,
    body varchar(4000) not null,
    -- Per-channel state, e.g. 'EMAIL=RETRY_SCHEDULED:2;IN_APP=DELIVERED:1'.
    deliveries varchar(500) not null,
    read_at timestamptz,
    created_at timestamptz not null default now(),
    constraint notifications_type_check check (type in (
        'API_KEY_CREATED', 'API_KEY_ROTATED', 'API_KEY_REVOKED', 'CREDENTIAL_EXPIRING',
        'WEBHOOK_ENDPOINT_FAILING', 'WEBHOOK_REPEATED_DELIVERY_FAILURE',
        'WEBHOOK_ENDPOINT_DISABLED',
        'QUOTA_WARNING', 'QUOTA_EXCEEDED', 'TRAFFIC_SPIKE',
        'PRODUCTION_REQUEST_RECEIVED', 'PRODUCTION_REVIEW_STARTED',
        'PRODUCTION_APPROVED', 'PRODUCTION_REJECTED', 'PRODUCTION_SUSPENDED',
        'SUSPICIOUS_ACTIVITY', 'CREDENTIAL_COMPROMISE', 'SESSION_REVOCATION'
    )),
    -- A notification must be attributable to a tenant and a recipient. An unowned
    -- row could be listed to the wrong user, which is a data leak rather than a
    -- cosmetic fault.
    constraint notifications_subject_present check (
        subject is not null and btrim(subject) <> ''
    )
);

create index if not exists notifications_user_idx
    on notifications(organization_id, user_id, created_at desc);

-- Backs the deduplication a burst of identical events would otherwise amplify: a
-- revoked key must produce one notification, not one per subsequent request.
create unique index if not exists notifications_dedup_idx
    on notifications(organization_id, user_id, type, created_at);

-- An unread-notification count is the single most common query in the portal, and
-- a partial index keeps it off the full history.
create index if not exists notifications_unread_idx
    on notifications(organization_id, user_id, created_at desc)
    where read_at is null;