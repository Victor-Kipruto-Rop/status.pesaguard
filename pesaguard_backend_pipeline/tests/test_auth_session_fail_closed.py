import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

import auth_rbac


def test_session_state_schema_failure_does_not_accept_legacy_active_session(monkeypatch):
    engine = create_engine("sqlite://")
    with engine.begin() as connection:
        connection.execute(text(
            "CREATE TABLE user_sessions ("
            "id TEXT PRIMARY KEY, user_id TEXT NOT NULL, "
            "tenant_id TEXT NOT NULL, active BOOLEAN NOT NULL)"
        ))
        connection.execute(text(
            "INSERT INTO user_sessions (id, user_id, tenant_id, active) "
            "VALUES ('legacy-session', 'user-a', 'tenant-a', true)"
        ))

    monkeypatch.setattr(auth_rbac, "_ensure_revocation_store_ready", lambda: None)
    monkeypatch.setattr(
        auth_rbac,
        "_RevocationSession",
        sessionmaker(bind=engine, expire_on_commit=False),
    )

    try:
        with pytest.raises(auth_rbac.AuthenticationUnavailable):
            auth_rbac._session_is_active("legacy-session", "user-a", "tenant-a")
    finally:
        engine.dispose()
