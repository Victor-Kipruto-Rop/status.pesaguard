alter table developer_preferences
    add column theme varchar(16) not null default 'SYSTEM',
    add column version bigint not null default 0,
    add constraint developer_preferences_theme_check
        check (theme in ('SYSTEM', 'LIGHT', 'DARK'));
