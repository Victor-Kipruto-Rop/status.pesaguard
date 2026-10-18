"""Release workflow contract checks for blocking security and database gates."""

from pathlib import Path

import yaml


BACKEND_ROOT = Path(__file__).resolve().parents[1]


def test_security_workflow_blocks_bandit_findings_and_scans_repository_secrets():
    workflow_path = BACKEND_ROOT / ".github" / "workflows" / "dependency-scan.yml"
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    jobs = workflow["jobs"]
    python_steps = jobs["python-scan"]["steps"]
    bandit_step = next(
        step for step in python_steps
        if step.get("name") == "Scan Python code for security flaws (bandit)"
    )
    report_step = next(
        step for step in python_steps
        if step.get("name") == "Upload Bandit Security Report"
    )

    assert "|| true" not in bandit_step["run"]
    assert report_step["if"].startswith("always()")
    assert jobs["secret-scan"]["permissions"]["contents"] == "read"
    assert jobs["secret-scan"]["steps"][-1]["uses"] == "gitleaks/gitleaks-action@v2"


def test_ci_runs_postgres_integration_contracts_with_application_role():
    workflow_path = BACKEND_ROOT / ".github" / "workflows" / "ci.yml"
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    test_step = next(
        step for step in workflow["jobs"]["test"]["steps"]
        if step.get("name") == "Run test suite"
    )

    assert test_step["env"]["PESAGUARD_RUN_POSTGRES_INTEGRATION"] == "1"
    assert "role%3Dpesaguard_app" in test_step["env"]["PESAGUARD_POSTGRES_TEST_URL"]
    assert test_step["env"]["PESAGUARD_RUN_REDIS_INTEGRATION"] == "1"
    assert test_step["env"]["PESAGUARD_RUN_KAFKA_INTEGRATION"] == "1"
    assert "PESAGUARD_LIVE_E2E" not in test_step["env"]
    names = [step.get("name", "") for step in workflow["jobs"]["test"]["steps"]]
    assert "Start disposable Redpanda broker" in names
    assert names.index("Start disposable Redpanda broker") < names.index("Run test suite")


def test_deploying_release_gate_provisions_and_tests_required_services():
    workflow_path = BACKEND_ROOT.parent / ".github" / "workflows" / "backend-release.yml"
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    verify = workflow["jobs"]["verify"]
    steps = verify["steps"]
    step_names = [step.get("name", "") for step in steps]

    assert {"postgres", "redis"}.issubset(verify["services"])
    assert verify["env"]["PESAGUARD_RUN_POSTGRES_INTEGRATION"] == "1"
    assert verify["env"]["PESAGUARD_RUN_REDIS_INTEGRATION"] == "1"
    assert verify["env"]["PESAGUARD_RUN_KAFKA_INTEGRATION"] == "1"
    assert "PESAGUARD_LIVE_E2E" not in verify["env"]
    assert "Provision isolated PostgreSQL roles" in step_names
    assert "Start disposable Redpanda broker" in step_names
    test_step = next(
        step for step in steps if step.get("name") == "Run complete backend test suite"
    )
    assert test_step["working-directory"] == "pesaguard_backend_pipeline"
    assert test_step["run"].startswith("pytest -c pytest.ini")
    assert step_names.index("Provision isolated PostgreSQL roles") < step_names.index(
        "Apply migrations to PostgreSQL"
    )
    assert step_names.index("Start disposable Redpanda broker") < step_names.index(
        "Run complete backend test suite"
    )
    compile_step = next(step for step in steps if step.get("name") == "Compile backend")
    assert "compileall" in compile_step["run"]
    assert "tmp" in compile_step["run"]


def test_staging_deploys_only_from_staging_and_production_is_manual():
    workflow_path = BACKEND_ROOT.parent / ".github" / "workflows" / "backend-release.yml"
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    # PyYAML's YAML 1.1 loader converts the key `on` to True.
    triggers = workflow.get("on", workflow.get(True, {}))
    manual_inputs = triggers["workflow_dispatch"]["inputs"]

    assert manual_inputs["promote_production"]["type"] == "boolean"
    assert manual_inputs["promote_production"]["default"] is False
    staging = workflow["jobs"]["staging"]
    production = workflow["jobs"]["production"]
    assert "refs/heads/staging" in staging["if"]
    assert "refs/heads/main" not in staging["if"]
    assert "workflow_dispatch" in production["if"]
    assert "refs/heads/staging" in production["if"]
    assert "inputs.promote_production" in production["if"]
    assert production["needs"] == ["build-scan-sbom", "staging"]


def test_release_frontend_gate_builds_the_tracked_dashboard():
    workflow_path = BACKEND_ROOT.parent / ".github" / "workflows" / "backend-release.yml"
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    frontend = workflow["jobs"]["frontend"]
    working_directory = frontend["defaults"]["run"]["working-directory"]

    assert working_directory == "dashboard.pesaguard.victorkipruto.com"
    assert (BACKEND_ROOT.parent / working_directory / "package-lock.json").is_file()
    assert (BACKEND_ROOT.parent / working_directory / "package.json").is_file()
    assert frontend["steps"][1]["with"]["cache-dependency-path"] == (
        "dashboard.pesaguard.victorkipruto.com/package-lock.json"
    )
