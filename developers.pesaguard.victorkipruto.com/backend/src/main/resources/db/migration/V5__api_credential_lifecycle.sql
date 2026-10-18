-- Phase 05: API credential lifecycle.
--
-- Phase 01 already issues keys with a random secret, an HMAC hash, a prefix,
-- scopes, an expiry and a revocation flag. This migration adds the remaining
-- lifecycle states, per-key IP restrictions, usage metadata, rotation lineage,
-- and an append-only history of state changes.
--
-- No plaintext secret column is added. The raw secret exists only in the issue
-- response and is never persisted.

alter table api_keys drop constraint if exists api_keys_status_check;
alter table api_keys add constraint api_keys_status_check
    check (status in ('CREATED', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED'));

alter table api_keys add column if not exists suspended_at timestamptz;
alter table api_keys add column if not exists ip_allowlist text;
alter table api_keys add column if not exists request_count bigint not null default 0;
alter table api_keys add column if not exists last_used_ip varchar(45);
alter table api_keys add column if not exists rotated_from_id uuid references api_keys(id);

create index if not exists api_keys_rotation_idx on api_keys(rotated_from_id);

create table api_key_history (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    api_key_id uuid not null references api_keys(id),
    from_status varchar(24),
    to_status varchar(24) not null,
    action varchar(64) not null,
    actor_user_id uuid references users(id),
    reason varchar(500),
    created_at timestamptz not null default now()
);

create index if not exists api_key_history_key_idx on api_key_history(api_key_id, created_at desc);

create or replace function prevent_api_key_history_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'api_key_history is append-only';
end;
$$;

create trigger api_key_history_no_mutation
    before update or delete on api_key_history
    for each row execute function prevent_api_key_history_mutation();