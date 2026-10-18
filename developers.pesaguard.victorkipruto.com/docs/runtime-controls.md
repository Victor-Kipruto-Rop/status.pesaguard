# Runtime feature flags and maintenance mode

The runtime status endpoint is `GET /api/v1/platform/status`. It returns maintenance messaging and the feature availability map used by the portal navigation. Its response is public and contains operational state only.

Set `PESAGUARD_MAINTENANCE_MODE=true` to show the maintenance page to signed-in portal users and reject API mutations with a structured `503 MAINTENANCE_MODE` response. The message and optional timestamps are configured with `PESAGUARD_MAINTENANCE_MESSAGE`, `PESAGUARD_MAINTENANCE_STARTS_AT`, and `PESAGUARD_MAINTENANCE_RECOVERY_AT`. Authentication, email verification, password recovery, logout, invitation acceptance, payment provider callbacks, and support ticket submission continue to work while maintenance is active.

Feature flags default to enabled. Set the corresponding `PESAGUARD_FEATURE_*` variable to `false` to hide the related portal navigation and deny its API routes with `404 FEATURE_UNAVAILABLE`. Current flags cover webhooks, events, usage, OAuth, sandbox, production access, support, notifications, billing, audit, and organization exports. These checks run on the server; hiding a navigation item does not grant or remove authorization by itself.

The API status response is cached only by application memory during a request. Updating deployment configuration requires the normal application restart/rollout. Use UTC ISO-8601 values for maintenance timestamps.
