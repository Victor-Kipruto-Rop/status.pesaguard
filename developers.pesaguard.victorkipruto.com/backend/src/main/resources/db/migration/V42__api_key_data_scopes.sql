insert into api_scopes (name, description, category, resource, action, restricted)
values
    ('usage:read', 'Read usage data for the API key project and environment.', 'usage', 'usage', 'read', false),
    ('audit:read', 'Read project-scoped audit events for the API key project.', 'audit', 'audit', 'read', false),
    ('events:read', 'Read event data for the API key project.', 'events', 'events', 'read', false)
on conflict (name) do nothing;
