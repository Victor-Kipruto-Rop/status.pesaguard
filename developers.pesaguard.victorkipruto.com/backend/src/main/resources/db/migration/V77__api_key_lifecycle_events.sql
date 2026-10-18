-- API-key lifecycle events contain safe resource context and the source row's
-- optimistic-lock version. They are written with the key transition, allowing
-- downstream handlers to ignore stale retries without receiving key material.
update event_types
set schema = '{"type":"object","required":["id","name","status","sourceVersion","organizationId","projectId","environmentId"]}'
where name = 'developer.api_key.created';

update event_types
set schema = '{"type":"object","required":["id","reason","status","sourceVersion","organizationId","projectId","environmentId"]}'
where name = 'developer.api_key.revoked';

insert into event_types (name, namespace, entity, action, description, category,
                         version, schema, lifecycle)
values
    ('developer.api_key.suspended', 'developer', 'api_key', 'suspended',
     'An API key was suspended and can no longer authenticate until resumed.', 'CREDENTIALS', 1,
     '{"type":"object","required":["id","status","sourceVersion","organizationId","projectId","environmentId"]}',
     'ACTIVE')
on conflict (name) do nothing;
