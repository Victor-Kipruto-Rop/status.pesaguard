alter table notification_channel_deliveries
    drop constraint notification_channel_delivery_state_check;

alter table notification_channel_deliveries
    add constraint notification_channel_delivery_state_check
    check (state in (
        'PENDING', 'IN_PROGRESS', 'RETRY_SCHEDULED', 'ACCEPTED', 'DELIVERED',
        'FAILED', 'SUPPRESSED'
    ));
