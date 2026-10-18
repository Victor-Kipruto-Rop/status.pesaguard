alter table support_tickets
    drop constraint support_tickets_category_check,
    drop constraint support_tickets_status_check;

alter table support_tickets
    add column public_id varchar(24),
    add column resolved_at timestamptz,
    add column closed_at timestamptz,
    add column environment varchar(16),
    add column endpoint varchar(200),
    add column http_status integer,
    add column request_id varchar(100),
    add column delivery_id varchar(100),
    add column event_type varchar(100),
    add column authentication_method varchar(60),
    add column error_code varchar(80);

update support_tickets
set public_id = 'SUP-' || upper(substr(md5(id::text), 1, 16));

alter table support_tickets
    alter column public_id set not null,
    add constraint support_tickets_public_id_unique unique (public_id),
    add constraint support_tickets_category_check check (category in (
        'AUTHENTICATION', 'API', 'WEBHOOKS', 'PROJECTS', 'ENVIRONMENTS',
        'API_KEYS', 'INTEGRATIONS', 'SECURITY', 'BILLING', 'ACCOUNT',
        'PERFORMANCE', 'INCIDENT', 'API_ISSUE', 'BUG_REPORT',
        'ACCOUNT_ACCESS', 'PRODUCTION', 'OTHER')),
    add constraint support_tickets_status_check check (status in (
        'OPEN', 'IN_PROGRESS', 'WAITING_FOR_DEVELOPER',
        'WAITING_FOR_PESAGUARD', 'RESOLVED', 'CLOSED')),
    add constraint support_tickets_context_environment_check
        check (environment is null or environment in ('SANDBOX', 'PRODUCTION')),
    add constraint support_tickets_context_http_status_check
        check (http_status is null or http_status between 100 and 599),
    add constraint support_tickets_context_endpoint_check
        check (endpoint is null or (endpoint like '/%' and endpoint not like '//%')),
    add constraint support_tickets_context_request_id_check
        check (request_id is null or request_id ~ '^req_[A-Za-z0-9_-]{4,80}$');

create table support_categories (
    id varchar(40) primary key,
    name varchar(80) not null unique,
    sort_order integer not null unique
);

insert into support_categories (id, name, sort_order) values
    ('account', 'Account', 1),
    ('authentication', 'Authentication', 2),
    ('organizations', 'Organizations', 3),
    ('projects', 'Projects', 4),
    ('environments', 'Environments', 5),
    ('api-keys', 'API Keys', 6),
    ('webhooks', 'Webhooks', 7),
    ('api-errors', 'API Errors', 8),
    ('rate-limits', 'Rate Limits', 9),
    ('security', 'Security', 10),
    ('integrations', 'Integrations', 11),
    ('sandbox', 'Sandbox', 12),
    ('production', 'Production', 13),
    ('troubleshooting', 'Troubleshooting', 14);

create table support_articles (
    id uuid primary key,
    public_id varchar(80) not null unique,
    title varchar(180) not null,
    slug varchar(180) not null unique,
    summary varchar(500) not null,
    content text not null,
    category_id varchar(40) not null references support_categories(id),
    status varchar(16) not null,
    author_id uuid references users(id),
    published_at timestamptz,
    updated_at timestamptz not null,
    created_at timestamptz not null,
    tags text not null default '',
    keywords text not null default '',
    related_article_ids text not null default '',
    related_documentation_urls text not null default '',
    related_error_codes text not null default '',
    related_api_endpoints text not null default '',
    constraint support_articles_status_check check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

create index support_articles_published_idx
    on support_articles (category_id, published_at desc)
    where status = 'PUBLISHED';

create index support_articles_search_idx
    on support_articles using gin (to_tsvector(
        'simple',
        coalesce(title, '') || ' ' || coalesce(summary, '') || ' ' ||
        coalesce(content, '') || ' ' || coalesce(tags, '') || ' ' ||
        coalesce(keywords, '') || ' ' || coalesce(related_error_codes, '') || ' ' ||
        coalesce(related_api_endpoints, '')
    ));

create table support_article_feedback (
    id uuid primary key,
    article_id uuid not null references support_articles(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    helpful boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint support_article_feedback_user_unique unique (article_id, user_id)
);

insert into support_articles (
    id, public_id, title, slug, summary, content, category_id, status,
    published_at, updated_at, created_at, tags, keywords,
    related_documentation_urls, related_error_codes, related_api_endpoints
) values
('30000000-0000-4000-8000-000000000001', 'HELP-API-KEY-CREATE', 'How do I create an API key?', 'create-api-key', 'Create a scoped key in the right environment and store it securely.', 'Open API Keys in your developer workspace and create a key for the project and environment that will use it. Grant only the scopes the integration needs. Copy the secret when it is shown; it cannot be retrieved later. Store it in a secret manager, never in browser code or source control.', 'api-keys', 'PUBLISHED', now(), now(), now(), 'api key|credentials|secret|scope', 'create|generate|new key|sandbox|production', 'https://docs.pesaguard.victorkipruto.com', 'INVALID_API_KEY|401|403', '/api/v1/api-keys'),
('30000000-0000-4000-8000-000000000002', 'HELP-API-KEY-ROTATE', 'How do I rotate an API key?', 'rotate-api-key', 'Issue a replacement key, deploy it, verify traffic, then revoke the old key.', 'Create a replacement key with the same minimum required scopes. Deploy it through your secret manager, verify successful requests in the intended environment, and then revoke the previous credential. Avoid putting either key in logs, support tickets, or client-side applications.', 'api-keys', 'PUBLISHED', now(), now(), now(), 'rotate|api key|credentials|revocation', 'rotation|replace|revoke|rollover', 'https://docs.pesaguard.victorkipruto.com', 'INVALID_API_KEY|401', '/api/v1/api-keys'),
('30000000-0000-4000-8000-000000000003', 'HELP-AUTH-401', 'Why is my API request returning 401?', 'api-request-401', 'Check the environment, key format, and Authorization header without sharing the credential.', 'A 401 response means the request was not authenticated. Confirm that the key belongs to the environment you are calling, that the Authorization header uses the documented scheme, and that the key has not expired or been revoked. Never paste the key into a ticket. Use the request ID to locate a sanitized log entry.', 'authentication', 'PUBLISHED', now(), now(), now(), '401|authentication|api key|authorization', '401|invalid api key|authentication failed|unauthorized', 'https://docs.pesaguard.victorkipruto.com', '401|INVALID_API_KEY|AUTHENTICATION_FAILED', '/v1/transactions'),
('30000000-0000-4000-8000-000000000004', 'HELP-AUTH-403', 'Why is my API request returning 403?', 'api-request-403', 'A valid identity may still lack the scope, role, or environment access required.', 'A 403 response means the request was understood but is not permitted. Check the key scopes, project membership, resource ownership, and whether the credential is allowed to access the selected environment. Ask an organization administrator to grant only the specific permission required.', 'authentication', 'PUBLISHED', now(), now(), now(), '403|permissions|scopes|rbac', 'forbidden|access denied|scope|role', 'https://docs.pesaguard.victorkipruto.com', '403', '/v1/transactions'),
('30000000-0000-4000-8000-000000000005', 'HELP-RATE-LIMIT-429', 'Why am I receiving 429 responses?', 'api-rate-limit-429', 'Respect rate-limit headers and use bounded exponential backoff with jitter.', 'A 429 response indicates the applicable request limit was reached. Read the rate-limit response headers, honor Retry-After when present, and use exponential backoff with jitter. Avoid retrying non-idempotent requests without an idempotency strategy. If the pattern is unexpected, include request IDs and timestamps in your support ticket.', 'rate-limits', 'PUBLISHED', now(), now(), now(), '429|rate limit|retry-after|backoff', 'too many requests|throttle|rate limited', 'https://docs.pesaguard.victorkipruto.com', '429|RATE_LIMITED', '/v1/transactions'),
('30000000-0000-4000-8000-000000000006', 'HELP-WEBHOOK-SETUP', 'How do I configure a webhook?', 'configure-webhook', 'Register an HTTPS endpoint, subscribe to only the events you need, and test delivery.', 'Create a webhook endpoint for your project, choose the required event types, and use an HTTPS URL reachable by the delivery service. Validate the endpoint in a non-production environment first. Make handlers idempotent and return a success response promptly; process longer work asynchronously.', 'webhooks', 'PUBLISHED', now(), now(), now(), 'webhook|events|endpoint|delivery', 'create|configure|event subscription', 'https://docs.pesaguard.victorkipruto.com', 'WEBHOOK_DELIVERY_FAILED', '/v1/webhooks/endpoints'),
('30000000-0000-4000-8000-000000000007', 'HELP-WEBHOOK-SIGNATURE', 'How do I verify webhook signatures?', 'verify-webhook-signatures', 'Verify the signature against the unmodified request body using the endpoint secret.', 'Read the raw request bytes before parsing JSON, then calculate the documented HMAC using the endpoint signing secret and compare signatures using a constant-time comparison. Check the timestamp tolerance to reduce replay risk. Never log or submit the signing secret. Rotate it if it may have been exposed.', 'webhooks', 'PUBLISHED', now(), now(), now(), 'webhook|signature|hmac|security', 'invalid signature|verify|401|secret rotation', 'https://docs.pesaguard.victorkipruto.com', 'INVALID_SIGNATURE|401', '/v1/webhooks/endpoints'),
('30000000-0000-4000-8000-000000000008', 'HELP-WEBHOOK-FAIL', 'Why is my webhook delivery failing?', 'webhook-delivery-failing', 'Inspect delivery status, response code, latency, and retry history.', 'Open the webhook delivery log and inspect the attempt timestamp, response status, and latency. Confirm the endpoint is publicly reachable over HTTPS and returns a success status before the delivery timeout. Handle duplicate deliveries idempotently. Share the delivery ID with support, but never include signing secrets or authorization headers.', 'webhooks', 'PUBLISHED', now(), now(), now(), 'webhook|delivery|retry|logs', 'delivery failed|500|timeout|delivery id', 'https://docs.pesaguard.victorkipruto.com', 'WEBHOOK_DELIVERY_FAILED|500|429', '/v1/webhooks/endpoints'),
('30000000-0000-4000-8000-000000000009', 'HELP-WEBHOOK-RETRY', 'How do webhook retries work?', 'webhook-retries', 'Return a success response after accepting an event and make processing idempotent.', 'A delivery that does not receive a successful response may be retried according to the configured delivery policy. Return success only after safely accepting the event, persist an event identifier for deduplication, and move slow processing to a background worker. Use delivery history to distinguish a retry from a new event.', 'webhooks', 'PUBLISHED', now(), now(), now(), 'webhook|retry|idempotency|delivery', 'retry policy|duplicate delivery', 'https://docs.pesaguard.victorkipruto.com', 'WEBHOOK_DELIVERY_FAILED', '/v1/webhooks/endpoints'),
('30000000-0000-4000-8000-000000000010', 'HELP-SANDBOX', 'How do I configure sandbox?', 'configure-sandbox', 'Use sandbox credentials and environment URLs to test an integration safely.', 'Create or select a sandbox environment and use credentials issued specifically for that environment. Sandbox data is isolated from production. Verify the configured base URL, test representative success and error cases, and do not reuse production secrets in local development.', 'sandbox', 'PUBLISHED', now(), now(), now(), 'sandbox|environment|testing|credentials', 'test environment|simulation|base url', 'https://docs.pesaguard.victorkipruto.com', '401|404', '/v1/transactions'),
('30000000-0000-4000-8000-000000000011', 'HELP-PRODUCTION', 'How do I move an integration to production?', 'move-to-production', 'Complete production readiness and security checks before changing credentials.', 'Validate the integration in sandbox, review required scopes and webhook verification, and complete the production access checklist for your organization. Issue production credentials only after approval. Deploy secrets through a protected secret store and monitor error rates and latency after release.', 'production', 'PUBLISHED', now(), now(), now(), 'production|deployment|security|readiness', 'go live|launch|production access', 'https://docs.pesaguard.victorkipruto.com', '401|403|429', '/v1/transactions'),
('30000000-0000-4000-8000-000000000012', 'HELP-MFA', 'How do I enable MFA?', 'enable-mfa', 'Enable a second factor in account security settings and store recovery codes safely.', 'Open account security settings and follow the multi-factor authentication enrollment steps. Confirm the authenticator before signing out and store recovery codes in a secure location. Do not send one-time codes or recovery codes to support.', 'security', 'PUBLISHED', now(), now(), now(), 'mfa|authentication|account security', 'two factor|2fa|authenticator|recovery codes', 'https://docs.pesaguard.victorkipruto.com', 'AUTHENTICATION_FAILED', ''),
('30000000-0000-4000-8000-000000000013', 'HELP-SESSIONS', 'How do I manage developer sessions?', 'manage-developer-sessions', 'Review active sessions and revoke devices you no longer recognize.', 'Use the sessions page in account security settings to review recent access. Revoke sessions you do not recognize, then rotate any credentials that may have been exposed. If you cannot access the account, use the account recovery flow rather than sharing credentials with support.', 'security', 'PUBLISHED', now(), now(), now(), 'sessions|devices|account security', 'revoke session|sign out|device activity', 'https://docs.pesaguard.victorkipruto.com', 'AUTHENTICATION_FAILED', ''),
('30000000-0000-4000-8000-000000000014', 'HELP-INVITE-TEAM', 'How do I invite team members?', 'invite-team-members', 'Invite teammates to your organization and grant the least-privileged role.', 'Open organization members and send an invitation to the teammate’s work email. Choose a role that provides the minimum access needed. Invitations expire; if one has expired, revoke it and issue a new invitation. Never share an invitation token in a public channel.', 'organizations', 'PUBLISHED', now(), now(), now(), 'organization|members|roles|invitation', 'invite user|team access|rbac', 'https://docs.pesaguard.victorkipruto.com', '403', ''),
('30000000-0000-4000-8000-000000000015', 'HELP-REVOKE-ACCESS', 'How do I revoke access?', 'revoke-access', 'Remove organization membership or revoke the affected credential or session.', 'For a departing teammate, remove their organization membership and review active sessions and credentials they could access. For a possibly exposed API key, revoke it immediately and deploy a replacement. Review the audit log for follow-up activity and do not include the old secret in a ticket.', 'security', 'PUBLISHED', now(), now(), now(), 'revoke|access|api key|organization', 'remove access|revoke credential|offboarding', 'https://docs.pesaguard.victorkipruto.com', '401|403', '');

update support_articles set related_article_ids = 'api-request-401|api-request-403'
where slug = 'create-api-key';
update support_articles set related_article_ids = 'configure-webhook|webhook-retries'
where slug = 'verify-webhook-signatures';
update support_articles set related_article_ids = 'verify-webhook-signatures|webhook-retries'
where slug = 'webhook-delivery-failing';
update support_articles set related_article_ids = 'api-request-401|api-request-403'
where slug = 'api-rate-limit-429';
