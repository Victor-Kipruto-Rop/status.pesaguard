from __future__ import annotations

from datetime import datetime, timezone

import json
import sys
import types
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import text

from archival import ArchiveManager, ArchiveObjectStore, ArchivalPolicyError, RetentionPolicy
from database_service import DatabaseService, create_database_engine
from migration_service import MigrationService
from redis_service import RedisService


def test_database_service_sqlite_sessions_transaction_and_pool_metrics(tmp_path):
    service = DatabaseService(f"sqlite:///{tmp_path / 'service.db'}")
    try:
        with service.transaction() as session:
            session.execute(text("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT)"))
            session.execute(text("INSERT INTO records (id, value) VALUES (1, 'committed')"))

        with service.session() as session:
            assert session.execute(text("SELECT value FROM records WHERE id=1")).scalar_one() == "committed"
        snapshot = service.pool_snapshot()
        assert snapshot["pool_class"] == "NullPool"
        assert "status" in snapshot
    finally:
        service.close()


def test_database_service_rolls_back_failed_transaction_and_validates_pool_settings(monkeypatch, tmp_path):
    service = DatabaseService(f"sqlite:///{tmp_path / 'rollback.db'}")
    try:
        with service.transaction() as session:
            session.execute(text("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT)"))
        with pytest.raises(RuntimeError, match="abort"):
            with service.transaction() as session:
                session.execute(text("INSERT INTO records (id, value) VALUES (1, 'rolled-back')"))
                raise RuntimeError("abort")
        with service.session() as session:
            assert session.execute(text("SELECT count(*) FROM records")).scalar_one() == 0
    finally:
        service.close()

    monkeypatch.setenv("DB_POOL_SIZE", "0")
    with pytest.raises(ValueError, match="DB_POOL_SIZE"):
        create_database_engine("postgresql://user:password@localhost/db")
    monkeypatch.setenv("DB_POOL_SIZE", "101")
    with pytest.raises(ValueError, match="must not exceed 100"):
        create_database_engine("postgresql://user:password@localhost/db")
    monkeypatch.delenv("DB_POOL_SIZE")

    with pytest.raises(ValueError, match="database_url"):
        create_database_engine("")


def test_database_service_reuses_one_engine_per_instance_under_concurrency(tmp_path):
    service = DatabaseService(f"sqlite:///{tmp_path / 'shared.db'}")
    try:
        def select_one(_):
            with service.session() as session:
                return session.execute(text("SELECT 1")).scalar_one()

        with ThreadPoolExecutor(max_workers=8) as executor:
            assert list(executor.map(select_one, range(32))) == [1] * 32
    finally:
        service.close()


def test_redis_service_validates_url_uses_bounded_client_and_closes_owned_client(monkeypatch):
    class FakeClient:
        def __init__(self):
            self.closed = False

        def ping(self):
            return True

        def close(self):
            self.closed = True

    client = FakeClient()
    captured = {}

    def from_url(url, **kwargs):
        captured.update(url=url, **kwargs)
        return client

    monkeypatch.setitem(sys.modules, "redis", types.SimpleNamespace(from_url=from_url))
    service = RedisService(
        "rediss://cache.example.test:6380/1",
        socket_connect_timeout=1,
        socket_timeout=2,
    )
    assert service.ping() is True
    assert captured["socket_connect_timeout"] == 1
    assert captured["socket_timeout"] == 2
    service.close()
    assert client.closed

    for url in ("", "https://cache.example.test", "redis://cache.example.test:70000"):
        with pytest.raises(ValueError):
            RedisService(url)
    with pytest.raises(ValueError, match="timeouts"):
        RedisService("redis://localhost/0", socket_timeout=float("inf"))


def test_redis_service_does_not_close_injected_client():
    class FakeClient:
        closed = False

        def close(self):
            self.closed = True

    client = FakeClient()
    service = RedisService("redis://localhost/0", client=client)
    service.close()
    assert client.closed is False


def test_migration_service_runs_alembic_upgrade_and_rejects_empty_revision(tmp_path, monkeypatch):
    import migration_service

    config_file = tmp_path / "alembic.ini"
    config_file.write_text("[alembic]\nscript_location = .\n", encoding="utf-8")
    captured = {}

    def upgrade(config, revision):
        captured["revision"] = revision
        captured["database_url"] = config.get_main_option("sqlalchemy.url")

    monkeypatch.setattr(migration_service.command, "upgrade", upgrade)
    service = MigrationService(
        "postgresql://user:password@localhost/db?application_name=migrate%20test",
        config_path=config_file,
    )
    service.upgrade()
    assert captured["revision"] == "head"
    assert captured["database_url"] == "postgresql://user:password@localhost/db?application_name=migrate%20test"
    with pytest.raises(ValueError, match="revision"):
        service.upgrade(" ")


def test_archive_store_rejects_path_traversal_and_archived_payload_tampering(tmp_path):
    store = ArchiveObjectStore(str(tmp_path))
    for key in (
        "../escape.json", "/absolute.json", "folder\\escape.json",
        "folder/%2e%2e/file", "folder/.archive-target/file",
    ):
        with pytest.raises(ArchivalPolicyError):
            store.put(key, b"invalid")

    manager = ArchiveManager(RetentionPolicy(90, 730, 2555), store)
    transaction = {
        "tenant_id": "tenant/../other",
        "transaction_id": "../../tx",
        "transaction_time": "2025-01-01T00:00:00Z",
        "amount": "10.00",
    }
    manifest = manager.archive(transaction, now=datetime(2026, 9, 23, tzinfo=timezone.utc))
    assert manifest is not None
    assert store._resolve_key(manifest.object_key).is_relative_to(tmp_path.resolve())
    assert json.loads(manager.read_verified(manifest)) == transaction

    object_path = store._resolve_key(manifest.object_key)
    object_path.write_bytes(b"tampered")
    with pytest.raises(ArchivalPolicyError, match="hash"):
        manager.read_verified(manifest)


def test_archive_requires_valid_timestamp_and_is_idempotent(tmp_path):
    manager = ArchiveManager(RetentionPolicy(90, 730, 2555), ArchiveObjectStore(str(tmp_path)))
    with pytest.raises(ArchivalPolicyError, match="transaction_time"):
        manager.archive({"tenant_id": "tenant-a", "transaction_id": "tx"})

    transaction = {
        "tenant_id": "tenant-a",
        "transaction_id": "tx",
        "transaction_time": "2025-01-01T00:00:00Z",
    }
    now = datetime(2026, 9, 23, tzinfo=timezone.utc)
    first = manager.archive(transaction, now=now)
    second = manager.archive(transaction, now=now)
    assert first is not None and second is not None
    assert first.object_key == second.object_key
    assert first.payload_hash == second.payload_hash
    with pytest.raises(ArchivalPolicyError, match="immutable"):
        manager.store.put(first.object_key, b"different payload")
