import importlib
import json

import pytest

import retention_policy
import retention_cleanup
from retention_policy import RetentionPolicyError, load_retention_policy


def test_configured_retention_policy_covers_required_categories():
    policy = load_retention_policy()
    assert {
        "transactions",
        "audit",
        "application_logs",
        "security_logs",
        "raw_provider_data",
        "analytics",
        "user_data",
    } <= set(policy.rules)
    assert policy.rule("transactions").storage.startswith("PostgreSQL")
    assert policy.rule("raw_provider_data").deletion_method
    assert policy.version == "1.0"


def test_retention_policy_rejects_incomplete_rules(tmp_path):
    path = tmp_path / "invalid.json"
    path.write_text(json.dumps({"categories": {"transactions": {"owner": "Finance"}}}), encoding="utf-8")
    with pytest.raises(RetentionPolicyError):
        load_retention_policy(str(path))


def test_retention_policy_requires_boolean_approval_setting(tmp_path):
    source = retention_policy.Path(retention_policy.__file__).with_name("retention_policy.json")
    document = json.loads(source.read_text(encoding="utf-8"))
    document["approval_required"] = "true"
    path = tmp_path / "invalid-approval.json"
    path.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(RetentionPolicyError, match="approval_required must be a boolean"):
        load_retention_policy(str(path))


def test_retention_cleanup_fails_closed_when_policy_loading_fails(monkeypatch):
    original_loader = retention_policy.load_retention_policy

    def reject_policy():
        raise RetentionPolicyError("configured retention policy is invalid")

    monkeypatch.setattr(retention_policy, "load_retention_policy", reject_policy)
    with pytest.raises(RetentionPolicyError, match="configured retention policy is invalid"):
        importlib.reload(retention_cleanup)

    monkeypatch.setattr(retention_policy, "load_retention_policy", original_loader)
    importlib.reload(retention_cleanup)


def test_retention_cleanup_rejects_invalid_batch_size_during_initialization(monkeypatch):
    monkeypatch.setenv("PESAGUARD_RETENTION_BATCH_SIZE", "0")

    with pytest.raises(ValueError, match="PESAGUARD_RETENTION_BATCH_SIZE must be greater than zero"):
        importlib.reload(retention_cleanup)

    monkeypatch.delenv("PESAGUARD_RETENTION_BATCH_SIZE")
    importlib.reload(retention_cleanup)