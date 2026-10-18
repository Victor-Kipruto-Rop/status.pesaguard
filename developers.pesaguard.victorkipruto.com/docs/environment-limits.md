# Environment limits

Each environment owns one `environment_limits` row. Defaults are created with the
environment: 600 requests/minute, 100 requests/second, 5 API keys, 20 credentials,
and a 60-minute credential rotation reminder.

## Where each limit is enforced

| Limit | Enforced in | Notes |
| --- | --- | --- |
| `maxApiKeys` | Application | Enforced at API-key issuance. |
| `maxCredentials` | Application | Enforced at credential creation. |
| `credentialRotationIntervalMinutes` | Frontend reminder | Displayed as a suggested next rotation date; it does not block early or late rotation. |
| `requestsPerMinute` | API-key authentication | Enforced as an environment-wide sliding window across API-key-authenticated developer API requests. |
| `burstRequests` | API-key authentication | Enforced as a per-environment one-second sliding window across API-key-authenticated requests. |

The environment counters use the configured rate-limit store. If the store is
unavailable, API-key requests fail closed with `503 RATE_LIMITER_UNAVAILABLE`
and `Retry-After: 5`. Requests that do not authenticate with a developer API key
continue to use the separate IP-based baseline limit, which also fails closed if
its counter store is unavailable.

## Tier isolation

Limits are stored per environment, never per project or organization. Changing the
limits of DEVELOPMENT does not affect SANDBOX, STAGING, or PRODUCTION, and the
unique constraint on `(project_id, type)` guarantees a project cannot hold two
environments of the same tier to sidestep a limit.
