alter table users
    add column if not exists deactivated_until timestamptz,
    add column if not exists password_change_challenge_id uuid,
    add column if not exists password_change_session_id uuid,
    add column if not exists password_change_code_hash varchar(64),
    add column if not exists password_change_issued_at timestamptz,
    add column if not exists password_change_attempts integer not null default 0;

create unique index if not exists users_password_change_challenge_unique
    on users(password_change_challenge_id)
    where password_change_challenge_id is not null;

alter table users
    add constraint users_password_change_challenge_check
        check (
            (password_change_challenge_id is null
                and password_change_session_id is null
                and password_change_code_hash is null
                and password_change_issued_at is null
                and password_change_attempts = 0)
            or
            (password_change_challenge_id is not null
                and password_change_session_id is not null
                and password_change_code_hash is not null
                and password_change_issued_at is not null
                and password_change_attempts >= 0)
        );

alter table users
    add constraint users_deactivation_expiry_check
        check (deactivated_until is null or status = 'DEACTIVATED');
