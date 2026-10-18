alter table api_keys drop constraint if exists api_keys_status_check;
alter table api_keys add constraint api_keys_status_check
    check (status in ('CREATED', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED', 'COMPROMISED'));
