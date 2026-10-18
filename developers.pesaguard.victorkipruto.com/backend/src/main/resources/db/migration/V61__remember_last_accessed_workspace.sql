alter table users
    add column last_accessed_workspace_id uuid references organizations(id) on delete set null;
