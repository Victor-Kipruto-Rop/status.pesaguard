alter table support_tickets
    add column resolved_by_operator uuid,
    add column resolution_note varchar(1000),
    add column operator_action_reason varchar(500);

alter table notification_preferences
    drop constraint notification_preferences_category_check;

alter table notification_preferences
    add constraint notification_preferences_category_check
    check (category in ('CREDENTIAL', 'WEBHOOK', 'USAGE', 'PRODUCTION', 'SECURITY', 'SUPPORT'));

alter table notifications
    drop constraint notifications_type_check;

alter table notifications
    add constraint notifications_type_check check (type in (
        'API_KEY_CREATED', 'API_KEY_ROTATED', 'API_KEY_REVOKED', 'CREDENTIAL_EXPIRING',
        'WEBHOOK_ENDPOINT_FAILING', 'WEBHOOK_REPEATED_DELIVERY_FAILURE', 'WEBHOOK_ENDPOINT_DISABLED',
        'QUOTA_WARNING', 'QUOTA_EXCEEDED', 'TRAFFIC_SPIKE',
        'PRODUCTION_REQUEST_RECEIVED', 'PRODUCTION_REVIEW_STARTED', 'PRODUCTION_APPROVED',
        'PRODUCTION_REJECTED', 'PRODUCTION_ACTIVATED', 'PRODUCTION_REACTIVATED',
        'PRODUCTION_SUSPENDED', 'PRODUCTION_REVOKED',
        'SUPPORT_TICKET_CREATED', 'SUPPORT_TICKET_RESOLVED',
        'SUSPICIOUS_ACTIVITY', 'CREDENTIAL_COMPROMISE', 'SESSION_REVOCATION'
    ));
