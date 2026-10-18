alter table webhook_endpoints add column environment_id uuid;
alter table outbox_events add column environment_id uuid;

update outbox_events
set environment_id = (payload::jsonb ->> 'environmentId')::uuid
where payload::jsonb ? 'environmentId';

update webhook_endpoints endpoint
set environment_id = environment.id
from project_environments environment
where environment.organization_id = endpoint.organization_id
  and environment.project_id = endpoint.project_id
  and environment.type = 'SANDBOX';

do $$
begin
    if exists (select 1 from webhook_endpoints where environment_id is null) then
        raise exception 'Cannot migrate webhook endpoints: a project has no Sandbox environment';
    end if;
end $$;

update outbox_events event
set environment_id = endpoint.environment_id
from webhook_endpoints endpoint
where event.environment_id is null
  and event.event_type = 'developer.webhook.created'
  and endpoint.id::text = event.payload::jsonb ->> 'id'
  and endpoint.organization_id = event.organization_id
  and endpoint.project_id = event.project_id;

update event_subscriptions subscription
set environment_id = endpoint.environment_id
from webhook_endpoints endpoint
where endpoint.id::text = subscription.endpoint_id
  and endpoint.organization_id = subscription.organization_id
  and endpoint.project_id = subscription.project_id;

update event_subscriptions subscription
set environment_id = environment.id
from project_environments environment
where subscription.environment_id is null
  and environment.organization_id = subscription.organization_id
  and environment.project_id = subscription.project_id
  and environment.type = 'SANDBOX';

do $$
begin
    if exists (select 1 from event_subscriptions where environment_id is null) then
        raise exception 'Cannot migrate webhook subscriptions: a project has no Sandbox environment';
    end if;
end $$;

alter table webhook_endpoints alter column environment_id set not null;
alter table event_subscriptions alter column environment_id set not null;

alter table webhook_endpoints
    add constraint webhook_endpoints_environment_project_org_fk
    foreign key (environment_id, project_id, organization_id)
    references project_environments(id, project_id, organization_id);

alter table event_subscriptions
    add constraint event_subscriptions_environment_project_org_fk
    foreign key (environment_id, project_id, organization_id)
    references project_environments(id, project_id, organization_id);

alter table outbox_events
    add constraint outbox_events_environment_project_org_fk
    foreign key (environment_id, project_id, organization_id)
    references project_environments(id, project_id, organization_id);

create index webhook_endpoints_environment_idx
    on webhook_endpoints(organization_id, project_id, environment_id, created_at desc);

create index event_subscriptions_environment_idx
    on event_subscriptions(organization_id, project_id, environment_id, created_at desc);

create index outbox_events_environment_idx
    on outbox_events(organization_id, project_id, environment_id, created_at desc);
