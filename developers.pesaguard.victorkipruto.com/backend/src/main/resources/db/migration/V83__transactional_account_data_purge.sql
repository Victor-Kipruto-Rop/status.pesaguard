-- Account deletion is a hard purge after the grace period, including rows in
-- append-only history tables. Their normal immutability guard remains active
-- for the application role; the purge function executes as the schema owner.
do $migration$
declare
    trigger_function record;
    function_body text;
begin
    function_body := $body$
begin
    if tg_op = 'DELETE'
       and current_user = pg_get_userbyid(
           (select relation.relowner
            from pg_class relation
            where relation.oid = tg_relid)) then
        return old;
    end if;
    raise exception '% is append-only', tg_table_name;
end;
$body$;

    for trigger_function in
        select distinct namespace.nspname, routine.proname
        from pg_trigger database_trigger
        join pg_proc routine on routine.oid = database_trigger.tgfoid
        join pg_namespace namespace on namespace.oid = routine.pronamespace
        where not database_trigger.tgisinternal
          and (database_trigger.tgtype & 8) = 8
          and left(routine.proname, 8) = 'prevent_'
          and right(routine.proname, 9) = '_mutation'
    loop
        execute format(
            'create or replace function %I.%I() returns trigger language plpgsql as %L',
            trigger_function.nspname,
            trigger_function.proname,
            function_body);
    end loop;
end;
$migration$;

create or replace function public.purge_account_data(p_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public, pg_temp
as $purge$
declare
    account_status text;
    requested_at timestamptz;
    organization_ids uuid[];
    scoped_table record;
    foreign_key record;
    relation_oid oid;
    join_predicate text;
    delete_guard text;
    added_rows bigint;
    deleted_rows bigint;
    remaining_rows bigint;
begin
    select account.status, account.deletion_requested_at
      into account_status, requested_at
      from public.users account
     where account.id = p_user_id
     for update;

    if not found
       or account_status <> 'PENDING_DELETION'
       or requested_at is null
       or requested_at > now() - interval '30 days' then
        return false;
    end if;

    select coalesce(array_agg(distinct organization.id), array[]::uuid[])
      into organization_ids
      from public.organizations organization
      left join public.organization_memberships membership
        on membership.organization_id = organization.id
     where organization.owner_user_id = p_user_id
        or membership.user_id = p_user_id;

    perform organization.id
      from public.organizations organization
     where organization.id = any (organization_ids)
     order by organization.id
     for update;

    -- Support content is platform-wide rather than organization data. Keep the
    -- content but remove its optional author reference before deleting the user.
    update public.support_articles
       set author_id = null
     where author_id = p_user_id;

    drop table if exists pg_temp.account_purge_targets;
    create temporary table account_purge_targets (
        relation_oid oid not null,
        row_tid tid not null,
        primary key (relation_oid, row_tid)
    ) on commit drop;

    insert into pg_temp.account_purge_targets (relation_oid, row_tid)
    select 'public.users'::regclass::oid, account.ctid
      from public.users account
     where account.id = p_user_id;

    insert into pg_temp.account_purge_targets (relation_oid, row_tid)
    select 'public.organizations'::regclass::oid, organization.ctid
      from public.organizations organization
     where organization.id = any (organization_ids)
    on conflict do nothing;

    for scoped_table in
        select relation.oid, namespace.nspname, relation.relname
          from pg_class relation
          join pg_namespace namespace on namespace.oid = relation.relnamespace
          join pg_attribute attribute on attribute.attrelid = relation.oid
         where namespace.nspname = 'public'
           and relation.relkind in ('r', 'p')
           and attribute.attname = 'organization_id'
           and attribute.atttypid = 'uuid'::regtype
           and not attribute.attisdropped
    loop
        execute format(
            'insert into pg_temp.account_purge_targets (relation_oid, row_tid)
             select $1, scoped_row.ctid
               from %I.%I scoped_row
              where scoped_row.organization_id = any ($2)
             on conflict do nothing',
            scoped_table.nspname,
            scoped_table.relname)
        using scoped_table.oid, organization_ids;
    end loop;

    loop
        added_rows := 0;
        for foreign_key in
            select constraint_row.conrelid,
                   constraint_row.confrelid,
                   constraint_row.conkey,
                   constraint_row.confkey,
                   child_namespace.nspname as child_schema,
                   child_relation.relname as child_table,
                   parent_namespace.nspname as parent_schema,
                   parent_relation.relname as parent_table
              from pg_constraint constraint_row
              join pg_class child_relation on child_relation.oid = constraint_row.conrelid
              join pg_namespace child_namespace on child_namespace.oid = child_relation.relnamespace
              join pg_class parent_relation on parent_relation.oid = constraint_row.confrelid
              join pg_namespace parent_namespace on parent_namespace.oid = parent_relation.relnamespace
             where constraint_row.contype = 'f'
               and child_namespace.nspname = 'public'
               and parent_namespace.nspname = 'public'
        loop
            select string_agg(
                       format('child_row.%I = parent_row.%I',
                           child_attribute.attname, parent_attribute.attname),
                       ' and ' order by child_key.ordinality)
              into join_predicate
              from unnest(foreign_key.conkey) with ordinality child_key(attnum, ordinality)
              join unnest(foreign_key.confkey) with ordinality parent_key(attnum, ordinality)
                using (ordinality)
              join pg_attribute child_attribute
                on child_attribute.attrelid = foreign_key.conrelid
               and child_attribute.attnum = child_key.attnum
              join pg_attribute parent_attribute
                on parent_attribute.attrelid = foreign_key.confrelid
               and parent_attribute.attnum = parent_key.attnum;

            execute format(
                'insert into pg_temp.account_purge_targets (relation_oid, row_tid)
                 select $1, child_row.ctid
                   from %I.%I child_row
                   join %I.%I parent_row on %s
                   join pg_temp.account_purge_targets parent_target
                     on parent_target.relation_oid = $2
                    and parent_target.row_tid = parent_row.ctid
                 on conflict do nothing',
                foreign_key.child_schema,
                foreign_key.child_table,
                foreign_key.parent_schema,
                foreign_key.parent_table,
                join_predicate)
            using foreign_key.conrelid, foreign_key.confrelid;
            get diagnostics added_rows = row_count;
            exit when added_rows > 0;
        end loop;
        exit when added_rows = 0;
    end loop;

    loop
        deleted_rows := 0;
        for relation_oid in
            select distinct target.relation_oid
              from pg_temp.account_purge_targets target
        loop
            delete_guard := '';
            for foreign_key in
                select constraint_row.conrelid,
                       constraint_row.conkey,
                       constraint_row.confkey,
                       child_namespace.nspname as child_schema,
                       child_relation.relname as child_table
                  from pg_constraint constraint_row
                  join pg_class child_relation on child_relation.oid = constraint_row.conrelid
                  join pg_namespace child_namespace on child_namespace.oid = child_relation.relnamespace
                 where constraint_row.contype = 'f'
                   and constraint_row.confrelid = relation_oid
                   and child_namespace.nspname = 'public'
            loop
                select string_agg(
                           format('child_row.%I = parent_row.%I',
                               child_attribute.attname, parent_attribute.attname),
                           ' and ' order by child_key.ordinality)
                  into join_predicate
                  from unnest(foreign_key.conkey) with ordinality child_key(attnum, ordinality)
                  join unnest(foreign_key.confkey) with ordinality parent_key(attnum, ordinality)
                    using (ordinality)
                  join pg_attribute child_attribute
                    on child_attribute.attrelid = foreign_key.conrelid
                   and child_attribute.attnum = child_key.attnum
                  join pg_attribute parent_attribute
                    on parent_attribute.attrelid = relation_oid
                   and parent_attribute.attnum = parent_key.attnum;

                delete_guard := delete_guard || format(
                    ' and not exists (
                         select 1
                           from %I.%I child_row
                           join %I.%I parent_row on %s
                          where parent_row.ctid = purge_target.row_tid)',
                    foreign_key.child_schema,
                    foreign_key.child_table,
                    'public',
                    (select relation.relname
                       from pg_class relation
                      where relation.oid = relation_oid),
                    join_predicate);
            end loop;

            execute format(
                'delete from %s purge_row
                  using pg_temp.account_purge_targets purge_target
                 where purge_target.relation_oid = $1
                   and purge_row.ctid = purge_target.row_tid%s',
                relation_oid::regclass,
                delete_guard)
            using relation_oid;
            get diagnostics added_rows = row_count;
            deleted_rows := deleted_rows + added_rows;

            execute format(
                'delete from pg_temp.account_purge_targets purge_target
                  where purge_target.relation_oid = $1
                    and not exists (
                        select 1 from %s remaining_row
                         where remaining_row.ctid = purge_target.row_tid)',
                relation_oid::regclass)
            using relation_oid;
        end loop;

        select count(*) into remaining_rows
          from pg_temp.account_purge_targets;
        exit when remaining_rows = 0;
        if deleted_rows = 0 then
            raise exception 'Account purge stopped because scoped records remain referenced';
        end if;
    end loop;

    return true;
end;
$purge$;
