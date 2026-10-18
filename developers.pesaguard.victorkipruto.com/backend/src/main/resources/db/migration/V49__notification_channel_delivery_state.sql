-- Persist channel retry timestamps and errors independently from the compact
-- notification summary so workers can safely find and retry due channels.
create table notification_channel_deliveries (
    notification_id uuid not null references notifications(id) on delete cascade,
    channel varchar(16) not null,
    state varchar(24) not null,
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    last_error varchar(500),
    primary key (notification_id, channel),
    constraint notification_channel_delivery_channel_check
        check (channel in ('EMAIL', 'IN_APP')),
    constraint notification_channel_delivery_state_check
        check (state in ('PENDING', 'IN_PROGRESS', 'RETRY_SCHEDULED', 'DELIVERED', 'FAILED', 'SUPPRESSED')),
    constraint notification_channel_delivery_attempts_check check (attempts >= 0),
    constraint notification_channel_delivery_retry_check check (
        (state = 'RETRY_SCHEDULED' and next_attempt_at is not null)
        or (state <> 'RETRY_SCHEDULED' and next_attempt_at is null)
    )
);

-- Backfill existing compact channel state. Previously scheduled retries had no
-- durable timestamp, so make them due immediately rather than silently losing them.
insert into notification_channel_deliveries
    (notification_id, channel, state, attempts, next_attempt_at, last_error)
select n.id,
       split_part(delivery_row, '=', 1),
       split_part(split_part(delivery_row, '=', 2), ':', 1),
       coalesce(nullif(split_part(split_part(delivery_row, '=', 2), ':', 2), ''), '0')::integer,
       case when split_part(split_part(delivery_row, '=', 2), ':', 1) = 'RETRY_SCHEDULED'
            then now() else null end,
       nullif(substring(split_part(delivery_row, '=', 2) from '^[^:]*:[0-9]+:(.*)$'), '')
from notifications n
cross join lateral regexp_split_to_table(n.deliveries, ';') as split_rows(delivery_row)
where delivery_row like '%=%'
on conflict (notification_id, channel) do nothing;

create index notification_channel_delivery_due_idx
    on notification_channel_deliveries(next_attempt_at, notification_id)
    where state = 'RETRY_SCHEDULED';
