alter table users add column username varchar(32);

with normalized as (
    select id,
           email,
           case
               when length(normalized_local) < 3 then 'developer'
               else left(normalized_local, 32)
           end as base
    from users
    cross join lateral (
        select trim(both '._-' from regexp_replace(
            split_part(lower(email), '@', 1),
            '[^a-z0-9._-]', '', 'g')) as normalized_local
    ) as local_part
),
ranked as (
    -- Global suffixes avoid collisions between a local-part like "alex-2" and
    -- the generated second username for two accounts whose local-part is "alex".
    select id,
           base,
           row_number() over (order by email, id) as row_num
    from normalized
)
update users as account
set username = left(ranked.base, 32 - length('-' || ranked.row_num::text))
               || '-' || ranked.row_num::text
from ranked
where account.id = ranked.id;

alter table users alter column username set not null;
alter table users add constraint users_username_format_check
    check (username ~ '^[a-z0-9][a-z0-9._-]{2,31}$');
create unique index users_username_lower_unique on users (lower(username));
