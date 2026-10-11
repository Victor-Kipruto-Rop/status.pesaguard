"""Unit tests for service discovery (in-memory fallback path).

Covers registration, heartbeat refresh, expiry-based health pruning, ownership
checks on heartbeat/deregister, and the production fail-closed guarantee.
"""

import pytest
import time

from service_discovery import ServiceDiscovery


@pytest.fixture()
def discovery(monkeypatch):
    # Force the in-memory fallback (no Redis).
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "development")
    monkeypatch.setenv("REDIS_URL", "")
    return ServiceDiscovery(redis_url="", default_ttl=0.05)


def test_register_and_discover(discovery):
    iid = discovery.register("api", metadata={"version": "1.0"})
    assert discovery.is_healthy("api")
    found = discovery.discover("api")
    assert found["instance_id"] == iid
    assert found["metadata"]["version"] == "1.0"


def test_heartbeat_refreshes_health(discovery):
    iid = discovery.register("api")
    # Heartbeating keeps it alive well past the original 0.05s TTL.
    time.sleep(0.02)
    assert discovery.heartbeat("api", iid) is True
    assert discovery.is_healthy("api")


def test_heartbeat_from_non_owner_is_rejected(discovery):
    iid = discovery.register("api")
    assert discovery.heartbeat("api", "someone-else") is False


def test_expired_instance_is_pruned_from_discovery(discovery):
    discovery.register("api")
    import time
    time.sleep(0.06)  # TTL lapses
    assert discovery.discover("api") is None
    assert discovery.is_healthy("api") is False


def test_deregister_requires_owner(discovery):
    iid = discovery.register("api")
    assert discovery.deregister("api", "someone-else") is False
    assert discovery.deregister("api", iid) is True
    assert discovery.discover("api") is None


def test_multiple_instances_are_discoverable_and_independently_removable(discovery):
    first = discovery.register("api", instance_id="worker-a", metadata={"zone": "a"})
    second = discovery.register("api", instance_id="worker-b", metadata={"zone": "b"})

    assert first != second
    instances = discovery.discover_all("api")
    assert {instance["instance_id"] for instance in instances} == {"worker-a", "worker-b"}
    assert discovery.deregister("api", "worker-a") is True
    assert [instance["instance_id"] for instance in discovery.discover_all("api")] == ["worker-b"]
    assert discovery.discover("api")["instance_id"] == "worker-b"


def test_instance_registration_rejects_invalid_lease(discovery):
    with pytest.raises(ValueError, match="positive finite"):
        discovery.register("api", ttl=0)

def test_duplicate_instance_id_is_rejected(discovery):
    discovery.register("api", instance_id="worker-a")
    with pytest.raises(ValueError, match="already registered"):
        discovery.register("api", instance_id="worker-a")


def test_redis_registry_supports_multiple_instances_and_fractional_ttl():
    class FakeRedis:
        def __init__(self):
            self.entries = {}
            self.sets = {}

        def set(self, key, value, nx=False, px=None):
            if nx and self.get(key) is not None:
                return False
            self.entries[key] = (value, time.monotonic() + px / 1000)
            return True

        def get(self, key):
            value = self.entries.get(key)
            if value is None:
                return None
            if value[1] <= time.monotonic():
                self.entries.pop(key, None)
                return None
            return value[0].encode()

        def smembers(self, key):
            return {member.encode() for member in self.sets.get(key, set())}

        def sadd(self, key, member):
            self.sets.setdefault(key, set()).add(member)

        def srem(self, key, member):
            self.sets.get(key, set()).discard(member)

        def scard(self, key):
            return len(self.sets.get(key, set()))

        def delete(self, key):
            self.entries.pop(key, None)
            self.sets.pop(key, None)

        def register_script(self, script):
            def run(keys=None, args=None):
                if "last_heartbeat" in script:
                    key = keys[0]
                    value = self.get(key)
                    if value is None:
                        return 0
                    data = __import__("json").loads(value)
                    if data["instance_id"] != args[0]:
                        return 0
                    data["last_heartbeat"] = float(args[1])
                    self.entries[key] = (
                        __import__("json").dumps(data),
                        time.monotonic() + int(args[2]) / 1000,
                    )
                    return 1
                if "SADD" in script:
                    key, index_key = keys
                    if self.get(key) is not None:
                        return 0
                    self.entries[key] = (args[0], time.monotonic() + int(args[1]) / 1000)
                    self.sadd(index_key, key)
                    return 1
                key, index_key = keys
                if "EXISTS" in script:
                    if self.get(key) is not None:
                        return 0
                    self.srem(index_key, key)
                    if self.scard(index_key) == 0:
                        self.delete(index_key)
                    return 1
                if "SREM" in script:
                    value = self.get(key)
                    if value is None:
                        return 0
                    data = __import__("json").loads(value)
                    if data.get("instance_id") != args[0]:
                        return 0
                    self.delete(key)
                    self.srem(index_key, key)
                    if self.scard(index_key) == 0:
                        self.delete(index_key)
                    return 1
                return 0

            return run

    redis = FakeRedis()
    discovery = ServiceDiscovery(redis_client=redis, default_ttl=0.05)
    first = discovery.register("api", instance_id="worker-a")
    second = discovery.register("api", instance_id="worker-b", ttl=0.05)

    instance_slot = discovery._key("api", first).split("{", 1)[1].split("}", 1)[0]
    index_slot = discovery._index_key("api").split("{", 1)[1].split("}", 1)[0]
    assert instance_slot == index_slot
    assert {item["instance_id"] for item in discovery.discover_all("api")} == {first, second}
    assert discovery.heartbeat("api", second, ttl=0.05) is True
    assert discovery.deregister("api", first) is True
    assert [item["instance_id"] for item in discovery.discover_all("api")] == [second]
    time.sleep(0.06)
    assert discovery.heartbeat("api", second, ttl=0.05) is False
    assert discovery.discover_all("api") == []
    assert redis.scard(discovery._index_key("api")) == 0


def test_production_without_redis_fails_closed(monkeypatch):
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "production")
    monkeypatch.setenv("REDIS_URL", "")
    with pytest.raises(RuntimeError):
        ServiceDiscovery(redis_url="")
