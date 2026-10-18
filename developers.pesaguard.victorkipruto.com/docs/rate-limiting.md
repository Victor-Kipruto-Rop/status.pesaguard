# Rate Limits & Quotas

Rate limiting is enforced in two request paths: a global IP-based baseline filter and environment-specific per-minute and per-second limits for authenticated API keys. Requests that pass through the limiter receive rate-limit headers. Requests that exceed a configured budget are rejected with `429 RATE_LIMITED`.

The counter store is selected with `PESAGUARD_RATELIMIT_STORE`. The default is `redis`, which shares atomic counters across application instances. `in-memory` is available for a single-instance development deployment; it does not share counters between nodes.

## Enforced policies

- The global baseline is 600 requests per minute per client IP. Health probes are excluded so load balancer checks remain meaningful.
- Each API-key request consumes both the environment's `requests_per_minute` sliding window and `burst_requests` one-second sliding window.
- Environment limits are managed through the environment settings API. Limits are tenant- and environment-scoped; the client cannot choose a different environment's counter.
- The rate-limit filter returns `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and `Retry-After` where applicable.

If the counter store is missing or unavailable, enforcement fails closed with `503 RATE_LIMITER_UNAVAILABLE` and `Retry-After: 5`. Traffic does not bypass a configured limit during a storage outage. Read-only previews also report the limiter as unavailable rather than displaying an unlimited budget.

## Scope and algorithms

Supported counter scopes include API key, project, environment, organization, endpoint, IP, user, and webhook. Policies compose: a request is allowed only when every applicable policy allows it. Consumption from an earlier policy is not rolled back when a later policy denies the request.

Token buckets support burst control. Sliding windows support exact rolling request counts. Daily and monthly quota period types exist, but plan quotas are not yet wired into API request admission.

## Limits of current coverage

- The baseline IP policy is a platform default, not customer-configurable policy CRUD.
- Environment request-per-minute and burst limits are enforced on API-key authenticated routes. Session-authenticated developer portal routes use the global IP baseline.
- Daily/monthly plan quotas are not currently enforced by the request limiter.
- Webhook deliveries use a separate bounded retry policy and do not consume API-key request limits.
- The in-memory store is single-node only. Use Redis for multi-instance deployments.
