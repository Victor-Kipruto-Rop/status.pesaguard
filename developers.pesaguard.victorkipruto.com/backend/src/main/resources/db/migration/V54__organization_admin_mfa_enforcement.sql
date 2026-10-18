alter table organization_security_settings
    add column if not exists mfa_required_for_admins boolean not null default false;
