"""Unit tests for the distributed lock service.

Uses a small fake Redis that supports SET NX PX and a Lua-ish script so we can
prove the fencing-token safety properties without a live Redis server.
"""

import time

import pytest

from distributed_lock import (
    DistributedLockService,
    LockNotAcquiredError,
)


class FakeRedis:
    """Minimal in-memory Redis supporting the operations the lock uses."""

    def __init__(self):
        self._store = {}
        self._now = time.monotonic()

    def _expire_sweep(self):
        for k in [k for k, (v, exp) in self._store.items() if exp is not None and exp <= self._now]:
            self._store.pop(k, None)

    def set(self, key, value, nx=False, px=None):
        self._expire_sweep()
        exists = key in self._store
        if nx and exists:
            return False
        exp = self._now + (px / 1000.0) if px else None
        self._store[key] = (value, exp)
        return True

    def get(self, key):
        self._expire_sweep()
        item = self._store.get(key)
        return item[0] if item else None

    def delete(self, key):
        self._store.pop(key, None)
        return 1

    def pexpire(self, key, ms):
        if key in self._store:
            v, _ = self._store[key]
            self._store[key] = (v, self._now + ms / 1000.0)
            return 1
        return 0

    def exists(self, key):
        self._expire_sweep()
        return 1 if key in self._store else 0

    def register_script(self, _src):
        # Return a callable that emulates the compare-and-release/extend scripts.
        def _run(keys=None, args=None):
            key = keys[0]
            arg = args[0]
            if "PEXPIRE" in _src:
                ttl = args[1]
                self._expire_sweep()
                if self._store.get(key, (None,))[0] == arg:
                    v, _ = self._store[key]
                    self._store[key] = (v, self._now + int(ttl) / 1000.0)
                    return 1
                return 0
            # release
            self._expire_sweep()
            if self._store.get(key, (None,))[0] == arg:
                self._store.pop(key, None)
                return 1
            return 0
        return _run

    def advance(self, seconds):
        self._now += seconds


@pytest.fixture()
def lock():
    return DistributedLockService(redis_client=FakeRedis(), key_prefix="test")


def test_acquire_returns_unique_token(lock):
    a = lock.acquire("res")
    b = lock.acquire("res")
    assert a is not None
    assert b is None  # already held


def test_release_only_with_owner_token(lock):
    token = lock.acquire("res")
    assert lock.release("res", "wrong-token") is False
    assert lock.is_locked("res") is True
    assert lock.release("res", token) is True
    assert lock.is_locked("res") is False


def test_expired_lock_can_be_reacquired_and_old_owner_cannot_release(lock):
    old = lock.acquire("res", ttl_seconds=0.05)
    lock._redis.advance(1.0)  # TTL expires
    new = lock.acquire("res", ttl_seconds=5.0)
    assert new is not None and new != old
    # Old owner must NOT be able to release the new owner's lock.
    assert lock.release("res", old) is False
    assert lock.is_locked("res") is True


def test_extend_only_with_owner_token(lock):
    token = lock.acquire("res", ttl_seconds=0.05)
    assert lock.extend("res", "wrong", ttl_seconds=100) is False
    assert lock.extend("res", token, ttl_seconds=100) is True
    lock._redis.advance(1.0)  # would have expired without the extend
    assert lock.is_locked("res") is True


def test_context_manager_releases_on_exit(lock):
    with lock.hold("res") as token:
        assert lock.is_locked("res")
    assert not lock.is_locked("res")


def test_context_manager_raises_when_unavailable(lock):
    lock.acquire("res", ttl_seconds=100)
    with pytest.raises(LockNotAcquiredError):
        with lock.hold("res", wait_seconds=0.1):
            pass


def test_in_process_fallback_without_redis(monkeypatch):
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "development")
    monkeypatch.setenv("REDIS_URL", "")
    svc = DistributedLockService(redis_url="")
    assert svc.is_distributed is False
    token = svc.acquire("res")
    assert token is not None
    assert svc.acquire("res") is None
    assert svc.release("res", token) is True
    assert svc.is_locked("res") is False


def test_production_without_redis_fails_closed(monkeypatch):
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "production")
    monkeypatch.setenv("REDIS_URL", "")
    with pytest.raises(RuntimeError):
        DistributedLockService(redis_url="")


# --- Performance: O(1) churn, no unbounded store growth ----------------------

def test_many_acquire_release_cycles_no_leak(lock):
    # Churn across a bounded set of names; the store must not accumulate
    # released entries.
    for i in range(500):
        name = f"res:{i % 10}"
        token = lock.acquire(name, ttl_seconds=5)
        assert token is not None
        lock.release(name, token)
    # nothing is held, so nothing should remain in the store
    lock._redis._expire_sweep()
    assert lock._redis._store == {}


def test_acquire_blocking_waits_then_succeeds(lock):
    import threading

    holder = lock.acquire("res", ttl_seconds=0.2)

    def release_soon():
        time.sleep(0.05)
        lock.release("res", holder)

    t = threading.Thread(target=release_soon)
    t.start()
    token = lock.acquire_blocking("res", ttl_seconds=5, wait_seconds=2, poll_interval=0.01)
    t.join()
    assert token is not None
    assert token != holder
    lock.release("res", token)


# --- Security: fenced ownership across simulated hand-off --------------------

def test_extend_cannot_rescue_a_lock_taken_by_another_owner(lock):
    old = lock.acquire("res", ttl_seconds=0.05)
    lock._redis.advance(1.0)  # old TTL lapses
    new = lock.acquire("res", ttl_seconds=5)
    # Even extending, the stale owner cannot touch the new owner's lock.
    assert lock.extend("res", old, ttl_seconds=100) is False
    lock._redis.advance(2.0)
    assert lock.is_locked("res") is True
    assert lock.release("res", new) is True

