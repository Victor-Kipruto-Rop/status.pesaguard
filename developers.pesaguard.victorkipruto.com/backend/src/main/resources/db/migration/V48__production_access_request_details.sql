-- Capture the production readiness evidence used to review a production grant.
alter table production_access_requests
    add column application_name varchar(160),
    add column organization_details varchar(2000),
    add column intended_api_usage varchar(2000),
    add column requested_scopes varchar(1000),
    add column requested_limits varchar(1000),
    add column integration_information varchar(2000),
    add column security_information varchar(2000);

-- Preserve historical rows with explicit legacy markers, then require complete
-- evidence for every new request.
update production_access_requests
set application_name = 'Legacy request',
    organization_details = 'Details not supplied for this historical request.',
    intended_api_usage = reason,
    requested_scopes = 'Not specified',
    requested_limits = 'Not specified',
    integration_information = 'Details not supplied for this historical request.',
    security_information = 'Details not supplied for this historical request.';

alter table production_access_requests
    alter column application_name set not null,
    alter column organization_details set not null,
    alter column intended_api_usage set not null,
    alter column requested_scopes set not null,
    alter column requested_limits set not null,
    alter column integration_information set not null,
    alter column security_information set not null;

create index production_access_requests_expiry_idx
    on production_access_requests(expires_at asc)
    where status in ('ACTIVE', 'SUSPENDED');
