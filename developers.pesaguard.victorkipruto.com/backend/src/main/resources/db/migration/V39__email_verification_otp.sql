alter table users
    add column if not exists email_verification_attempts integer not null default 0;

alter table users
    add constraint users_email_verification_attempts_check
    check (email_verification_attempts >= 0);
