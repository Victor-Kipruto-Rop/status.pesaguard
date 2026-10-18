alter table api_keys add column if not exists last_used_country varchar(2);
alter table api_keys add column if not exists last_used_device varchar(16);
