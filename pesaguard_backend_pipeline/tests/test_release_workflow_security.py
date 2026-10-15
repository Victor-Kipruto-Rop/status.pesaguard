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
