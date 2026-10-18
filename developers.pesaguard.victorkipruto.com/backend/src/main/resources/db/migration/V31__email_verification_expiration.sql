alter table users
    add column if not exists email_verification_issued_at timestamptz;
