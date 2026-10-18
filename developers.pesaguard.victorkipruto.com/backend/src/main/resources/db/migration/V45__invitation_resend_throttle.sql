alter table login_throttles
    drop constraint if exists login_throttles_type_check;

alter table login_throttles
    add constraint login_throttles_type_check check (
        subject_type in ('account', 'ip', 'oauth_token', 'invite_ip', 'invite_token', 'invitation_resend')
    );
