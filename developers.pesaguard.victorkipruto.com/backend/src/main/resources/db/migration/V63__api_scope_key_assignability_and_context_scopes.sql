alter table api_scopes
    drop constraint api_scopes_name_format;

alter table api_scopes
    add constraint api_scopes_name_format
        check (name ~ '^[a-z][a-z0-9_-]{1,47}:[a-z][a-z0-9_-]{1,23}$');

alter table api_scopes
    add column api_key_assignable boolean not null default false;

update api_scopes
set api_key_assignable = true
where name in (
    'transactions:read',
    'usage:read',
    'audit:read',
    'events:read',
    'webhooks:read'
);

insert into api_scopes (
    name, description, category, resource, action, restricted, api_key_assignable
)
values
    ('organization:read', 'Read safe context for the organization bound to this API key.', 'organization', 'organization', 'read', false, true),
    ('project:read', 'Read safe context for the project bound to this API key.', 'project', 'project', 'read', false, true),
    ('environment:read', 'Read safe context for the environment bound to this API key.', 'environment', 'environment', 'read', false, true),
    ('api:read', 'Read authentication context for this API key. Secrets and hashes are never returned.', 'api', 'api', 'read', false, true),
    ('airtel:read', 'Read Airtel Money integration data. This API capability is not yet implemented.', 'airtel', 'airtel', 'read', false, false),
    ('airtel:write', 'Write Airtel Money integration data. This API capability is not yet implemented.', 'airtel', 'airtel', 'write', true, false),
    ('accounts:read', 'Read account data. This API capability is not yet implemented.', 'accounts', 'accounts', 'read', false, false),
    ('banks:read', 'Read bank integration data. This API capability is not yet implemented.', 'banks', 'banks', 'read', false, false),
    ('banks:write', 'Write bank integration data. This API capability is not yet implemented.', 'banks', 'banks', 'write', true, false),
    ('customers:read', 'Read customer data. This API capability is not yet implemented.', 'customers', 'customers', 'read', false, false),
    ('jobs:read', 'Read asynchronous job status. This API capability is not yet implemented.', 'jobs', 'jobs', 'read', false, false),
    ('jobs:write', 'Submit asynchronous jobs. This API capability is not yet implemented.', 'jobs', 'jobs', 'write', true, false),
    ('mpesa:read', 'Read M-Pesa integration data. This API capability is not yet implemented.', 'mpesa', 'mpesa', 'read', false, false),
    ('mpesa:write', 'Write M-Pesa integration data. This API capability is not yet implemented.', 'mpesa', 'mpesa', 'write', true, false),
    ('rate_limits:read', 'Read rate-limit information. This API capability is not yet implemented.', 'rate_limits', 'rate_limits', 'read', false, false),
    ('reports:read', 'Read financial reports. This API capability is not yet implemented.', 'reports', 'reports', 'read', false, false),
    ('reports:write', 'Create financial reports. This API capability is not yet implemented.', 'reports', 'reports', 'write', true, false),
    ('risk:read', 'Read risk scores and signals. This API capability is not yet implemented.', 'risk', 'risk', 'read', false, false),
    ('settlements:read', 'Read settlement information. This API capability is not yet implemented.', 'settlements', 'settlements', 'read', false, false)
on conflict (name) do nothing;

update api_scopes
set api_key_assignable = false
where name in (
    'transactions:write',
    'payments:read',
    'payments:write',
    'reconciliation:read',
    'reconciliation:write',
    'fraud:read',
    'webhooks:write',
    'developer:read',
    'developer:write'
);

update api_scopes
set description = case name
    when 'transactions:write' then 'Write transaction data. This API capability is not yet implemented.'
    when 'payments:read' then 'Read payments. This API capability is not yet implemented.'
    when 'payments:write' then 'Initiate or refund payments. This API capability is not yet implemented.'
    when 'reconciliation:read' then 'Read reconciliation results. This API capability is not yet implemented.'
    when 'reconciliation:write' then 'Start reconciliation and resolve exceptions. This API capability is not yet implemented.'
    when 'fraud:read' then 'Read fraud signals. This API capability is not yet implemented.'
    when 'webhooks:write' then 'Manage webhook endpoints and deliveries. This API capability is not yet implemented for API keys.'
    when 'developer:read' then 'Read developer-platform resources. This API capability is not available to API keys.'
    when 'developer:write' then 'Manage developer-platform resources. This API capability is not available to API keys.'
    else description
end
where name in (
    'transactions:write',
    'payments:read',
    'payments:write',
    'reconciliation:read',
    'reconciliation:write',
    'fraud:read',
    'webhooks:write',
    'developer:read',
    'developer:write'
);
