# Rate limiting and fail-closed protection

The PesaGuard rate limiting service uses a dual strategy:

- Redis-backed distributed token bucket for multi-instance production deployments
- in-memory token bucket fallback for local or single-process use

## Security contract

The service must fail closed when the configured protection policy requires it.

- `fail_closed=True` blocks requests when Redis is not configured or becomes unavailable
- production environments reject requests when the distributed limiter is required but missing
- rate-limit outages are recorded as operational security events and return `503` with `Retry-After: 1`

## Resolution semantics

1. If a Redis-backed limiter is available, it is used first.
2. If Redis fails and the caller requests fail-closed behavior, the request is denied.
3. If Redis is unavailable and the route is not in fail-closed mode, the service falls back to the in-memory bucket.
4. If production requires distributed enforcement and no Redis is configured, the request is denied immediately.

## Operational notes

- Use `REDIS_URL` to enable distributed limiting in deployment environments.
- Set `PESAGUARD_ENABLE_REDIS_RATE_LIMIT=1` to opt into distributed rate limiting if needed.
- Keep `fail_closed=True` on high-risk endpoints such as login, password changes, and OTP verification. 
