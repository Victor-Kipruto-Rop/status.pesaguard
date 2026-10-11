# Service Discovery Operations

`ServiceDiscovery` stores a separate expiring lease for each service instance.
Redis is required for production deployments that run more than one process;
the in-memory registry is process-local and must not be treated as distributed.

## Instance lifecycle

- Call `register(name, metadata=..., ttl=...)` during instance startup and
  retain the returned instance ID.
- Renew the lease with `heartbeat(name, instance_id, ttl=...)` well before its
  expiry. A missing, expired, or differently owned lease returns `False`.
- Call `deregister(name, instance_id)` during graceful shutdown. Deregistration
  is owner-checked and safe to retry.
- Use `discover_all(name)` when selecting among all healthy instances.
  `discover(name)` returns the first instance in stable instance-ID order; it
  is not a load balancer.
- TTLs must be positive finite seconds. Redis stores millisecond leases, so
  fractional-second values are supported.

Redis registration, heartbeat, and deregistration use atomic scripts. Each
instance lease has its own TTL; a service-level Redis set indexes those keys.
The instance and index keys share a Redis Cluster hash slot. Discovery removes
expired keys from the index as it encounters them. Metadata must be
non-sensitive routing/health information; never put credentials or tokens in
service metadata.

## Deployment and rollout

Production construction fails when `REDIS_URL` is absent or Redis cannot be
reached. Confirm the Redis endpoint is shared by all application workers and
that the service identity has permission to read/write the configured
`PESAGUARD_DISCOVERY_PREFIX` namespace and execute Redis scripts.

The registry is ephemeral. The per-instance key layout differs from the former
single-key-per-service layout; coordinate rollout of discovery clients and let
leases created by old versions expire before relying on the new multi-instance
registry. Validate liveness and registration against the deployed Redis
topology; no live Redis cluster was available for this change's tests.
