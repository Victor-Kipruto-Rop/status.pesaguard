from fastapi.testclient import TestClient


def test_liveness_succeeds_without_checking_external_dependencies(monkeypatch):
    from pesaguard_backend_pipeline import fastapi_app

    def dependency_probe_must_not_run():
        raise AssertionError("Liveness must not probe dependencies")

    monkeypatch.setattr(fastapi_app, "build_health_payload", dependency_probe_must_not_run)

    response = TestClient(fastapi_app.app).get("/live")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert response.headers["Cache-Control"] == "no-store"


def test_readiness_returns_sanitized_health_when_dependencies_are_ready(monkeypatch):
    from pesaguard_backend_pipeline import fastapi_app

    monkeypatch.setattr(fastapi_app, "build_health_payload", lambda: {
        "status": "ok",
        "service": "pesaguard",
        "checks": {
            "database": {"status": "ok"},
            "kafka": {"status": "ok", "broker": "internal-broker"},
        },
    })

    response = TestClient(fastapi_app.app).get("/ready")

    assert response.status_code == 200
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json() == {
        "status": "ok",
        "service": "pesaguard",
        "checks": {
            "database": {"status": "ok"},
            "kafka": {"status": "ok"},
        },
    }
    assert b"internal-broker" not in response.content


def test_readiness_returns_service_unavailable_for_degraded_dependencies(monkeypatch):
    from pesaguard_backend_pipeline import fastapi_app

    monkeypatch.setattr(fastapi_app, "build_health_payload", lambda: {
        "status": "degraded",
        "service": "pesaguard",
        "checks": {
            "database": {"status": "ok"},
            "kafka": {"status": "failed", "error": "internal broker details"},
        },
    })

    response = TestClient(fastapi_app.app).get("/ready")

    assert response.status_code == 503
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json() == {
        "status": "degraded",
        "service": "pesaguard",
        "checks": {
            "database": {"status": "ok"},
            "kafka": {"status": "failed"},
        },
    }
    assert b"internal broker details" not in response.content


def test_health_reports_dependency_failure_with_service_unavailable(monkeypatch):
    from pesaguard_backend_pipeline import fastapi_app

    monkeypatch.setattr(fastapi_app, "build_health_payload", lambda: {
        "status": "failed",
        "service": "pesaguard",
        "checks": {
            "database": {"status": "failed", "error": "private database details"},
        },
    })

    response = TestClient(fastapi_app.app).get("/health")

    assert response.status_code == 503
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json() == {
        "status": "failed",
        "service": "pesaguard",
        "checks": {"database": {"status": "failed"}},
    }
    assert b"private database details" not in response.content
