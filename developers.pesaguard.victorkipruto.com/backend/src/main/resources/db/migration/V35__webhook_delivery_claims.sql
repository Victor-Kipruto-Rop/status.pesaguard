create table webhook_delivery_claims (
    event_id uuid not null references outbox_events(event_id) on delete cascade,
    subscription_id uuid not null references event_subscriptions(id) on delete cascade,
    claimed_until timestamptz not null,
    primary key (event_id, subscription_id)
);

create index webhook_delivery_claims_expiration_idx
    on webhook_delivery_claims(claimed_until);
