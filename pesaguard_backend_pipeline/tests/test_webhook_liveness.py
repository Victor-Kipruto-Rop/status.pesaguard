import app as webhook_module


def test_webhook_liveness_does_not_probe_dependencies(monkeypatch):
    def unexpected_dependency_probe():
        raise AssertionError("liveness must not depend on downstream services")

    monkeypatch.setattr(
        webhook_module,
        "build_health_payload",
        unexpected_dependency_probe,
    )

    response = webhook_module.app.test_client().get("/live")

    assert response.status_code == 200
    assert response.get_json() == {"status": "ok"}
    assert response.headers["Cache-Control"] == "no-store"
