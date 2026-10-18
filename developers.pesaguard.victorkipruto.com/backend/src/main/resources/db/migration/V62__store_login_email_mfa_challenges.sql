alter table users
    add column login_mfa_challenge_id uuid unique,
    add column login_mfa_organization_id uuid references organizations(id) on delete set null,
    add column login_mfa_code_hash varchar(64),
    add column login_mfa_issued_at timestamptz,
    add column login_mfa_attempts integer not null default 0;
