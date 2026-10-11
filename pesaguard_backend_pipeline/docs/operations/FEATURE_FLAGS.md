# Feature Flag Service

The feature flag layer is intentionally conservative:

- flag names are explicit and validated against a supported allow-list
- tenant overrides are scoped to one tenant and do not leak across tenant boundaries
- environment variables provide operational kill switches without code changes
- default values fail safe: disabled flags remain off unless explicitly enabled

## Resolution order

1. process-level override
2. environment override (`PESAGUARD_FLAG_<FLAG_NAME>`)
3. tenant override in `feature_flags`
4. default production-safe value

Example:

```python
from feature_flags import feature_flag_service

feature_flag_service.set_tenant_override("tenant-a", "alerting", False)
assert feature_flag_service.resolve("tenant-a", "alerting") is False
```

This service is designed for staged rollouts and emergency disablement without altering application code or cross-tenant data.
