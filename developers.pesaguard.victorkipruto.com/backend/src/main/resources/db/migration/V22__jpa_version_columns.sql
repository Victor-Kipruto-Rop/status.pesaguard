-- JPA optimistic-locking columns.
--
-- Several entities declare a @Version field for optimistic locking, but their
-- tables were created without the matching column. Hibernate schema validation
-- (ddl-auto: validate) treats that as a fatal startup error rather than a warning,
-- because a missing version column would silently disable optimistic locking: two
-- concurrent writers would both succeed and the second would overwrite the first.
--
-- Forward-only migration: no table is altered in place, so this is safe to apply
-- to an environment where earlier migrations have already run.
--
-- Each column starts at 0 and is NOT NULL so Hibernate always has a value to
-- compare against; a NULL version would make the first concurrent update pass
-- instead of being detected.

alter table environment_limits
    add column if not exists version bigint not null default 0;

alter table usage_buckets
    add column if not exists version bigint not null default 0;

alter table event_subscriptions
    add column if not exists version bigint not null default 0;

alter table organization_roles
    add column if not exists version bigint not null default 0;