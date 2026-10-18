alter table login_throttles
    alter column subject_type type varchar(32);

alter table login_throttles
    drop constraint if exists login_throttles_type_check;

alter table login_throttles
    add constraint login_throttles_type_check check (
        subject_type in (
            'account', 'ip', 'oauth_token', 'invite_ip', 'invite_token', 'invitation_resend',
            'invite_create_user', 'invite_create_org', 'invite_create_recipient'
        )
    );
