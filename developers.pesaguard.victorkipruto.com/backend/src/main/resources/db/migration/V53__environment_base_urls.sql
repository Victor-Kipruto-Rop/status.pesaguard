alter table api_key_creation_idempotency
    add column base_url varchar(512);

update api_key_creation_idempotency idempotency
set base_url = case
    when environment.type::text = 'PRODUCTION' then 'https://api.pesaguard.victorkipruto.com'
    else 'https://sandbox-api.pesaguard.victorkipruto.com'
end
from api_keys api_key
join project_environments environment on environment.id = api_key.environment_id
where idempotency.api_key_id = api_key.id;

alter table api_key_creation_idempotency
    alter column base_url set not null;
