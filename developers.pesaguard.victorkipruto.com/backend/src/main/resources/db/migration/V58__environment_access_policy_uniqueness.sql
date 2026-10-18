delete from environment_access_policies policy
using (
    select id
    from (
        select id, row_number() over (
            partition by environment_id, subject_type, subject_role
            order by updated_at desc, created_at desc, id desc
        ) as duplicate_number
        from environment_access_policies
    ) ranked
    where duplicate_number > 1
) duplicates
where policy.id = duplicates.id;

create unique index if not exists environment_access_policies_role_unique_idx
    on environment_access_policies(environment_id, subject_type, subject_role);
