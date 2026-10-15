import json
from pathlib import Path

import pytest

from pesaguard_backend_pipeline.operations import restore_safety


def test_restore_target_requires_explicit_isolation_confirmation():
    with pytest.raises(ValueError, match="explicit isolated-target confirmation"):
        restore_safety.validate_restore_target(
            "postgresql://restore:secret@restore.example:5433/pesaguard_restore",
            confirmed_isolated_target=False,
        )


def test_restore_target_rejects_the_configured_application_database_even_when_confirmed():
    application_url = "postgresql://app:app-secret@db.example:5432/pesaguard"
    target_url = "postgresql://restore:restore-secret@db.example:5432/pesaguard"

    with pytest.raises(ValueError, match="matches the configured application database"):
        restore_safety.validate_restore_target(
            target_url,
            application_database_url=application_url,
            confirmed_isolated_target=True,
        )


@pytest.mark.parametrize(
    "target_url",
    [
        "",
        "sqlite:///tmp/restore.db",
        "postgresql:///missing-host",
        "postgresql://restore:secret@db.example/",
        "postgresql://restore:secret@db.example:notaport/database",
    ],
)
def test_restore_target_rejects_unsupported_or_incomplete_database_urls(target_url):
    with pytest.raises(ValueError):
        restore_safety.validate_restore_target(
            target_url,
            confirmed_isolated_target=True,
        )


def test_restore_target_descriptor_omits_credentials_and_query_parameters():
    target = restore_safety.validate_restore_target(
        "postgresql://private-user:private-password@restore.example:5433/restore_db?sslmode=require&token=private-token",
        confirmed_isolated_target=True,
    )

    assert target == "restore.example:5433/restore_db"
    assert "private-" not in target
    assert "token" not in target


def test_restore_drill_refuses_unconfirmed_and_application_targets_before_restoring(
    tmp_path: Path,
    monkeypatch,
):
    from pesaguard_backend_pipeline.operations import restore_drill

    backup = tmp_path / "backup.sql.gz"
    backup.write_bytes(b"backup")
    calls = []
    monkeypatch.setattr(restore_drill.subprocess, "run", lambda *args, **kwargs: calls.append(args))
    target_url = "postgresql://restore:secret@db.example:5432/restore_db"

    with pytest.raises(ValueError, match="explicit isolated-target confirmation"):
        restore_drill.run_restore_drill(backup, target_url, tmp_path / "unconfirmed.json")

    with pytest.raises(ValueError, match="matches the configured application database"):
        restore_drill.run_restore_drill(
            backup,
            target_url,
            tmp_path / "same-target.json",
            confirmed_isolated_target=True,
            application_database_url="postgresql://app:other-secret@db.example:5432/restore_db",
        )

    assert calls == []


def test_restore_drill_report_redacts_target_connection_secrets(tmp_path: Path, monkeypatch):
    from pesaguard_backend_pipeline.operations import restore_drill

    backup = tmp_path / "backup.sql.gz"
    backup.write_bytes(b"backup")
    output = tmp_path / "restore-drill.json"
    target_url = (
        "postgresql://private-user:private-password@restore.example:5433/"
        "restore_db?sslmode=require&token=private-token"
    )
    monkeypatch.setattr(
        restore_drill.subprocess,
        "run",
        lambda *args, **kwargs: type("Result", (), {"returncode": 0, "stderr": ""})(),
    )
    monkeypatch.setattr(
        restore_drill.validate_restore,
        "validate",
        lambda _url: {"status": "passed", "counts": {"transactions": 1}},
    )

    result = restore_drill.run_restore_drill(
        backup,
        target_url,
        output,
        confirmed_isolated_target=True,
    )

    report = output.read_text(encoding="utf-8")
    assert result["database_url_host"] == "restore.example:5433/restore_db"
    assert result["status"] == "passed"
    assert "private-user" not in report
    assert "private-password" not in report
    assert "private-token" not in report


def test_restore_drill_records_restore_command_failure(tmp_path: Path, monkeypatch):
    from pesaguard_backend_pipeline.operations import restore_drill

    backup = tmp_path / "backup.sql.gz"
    backup.write_bytes(b"backup")
    output = tmp_path / "restore-drill.json"
    monkeypatch.setattr(
        restore_drill.subprocess,
        "run",
        lambda *args, **kwargs: type(
            "Result",
            (),
            {"returncode": 1, "stderr": "restore failed safely"},
        )(),
    )

    result = restore_drill.run_restore_drill(
        backup,
        "postgresql://restore@restore.example/restore_db",
        output,
        confirmed_isolated_target=True,
        application_database_url="postgresql://app@production.example/pesaguard",
    )

    assert result["status"] == "failed"
    assert result["error"] == "restore failed safely"
    assert json.loads(output.read_text(encoding="utf-8"))["status"] == "failed"


def test_restore_drill_cli_requires_explicit_confirmation(monkeypatch):
    import sys

    from pesaguard_backend_pipeline.operations import restore_drill

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "restore_drill.py",
            "--backup-file",
            "backup.sql.gz",
            "--database-url",
            "postgresql://restore@restore.example/restore_db",
        ],
    )

    with pytest.raises(SystemExit) as error:
        restore_drill.main()

    assert error.value.code == 2


def test_sentinel_restore_drill_records_command_failure_and_missing_backup(
    tmp_path: Path,
    monkeypatch,
):
    from pesaguard_backend_pipeline.operations import run_restore_drill

    target_url = "postgresql://restore@restore.example/restore_db"
    app_url = "postgresql://app@production.example/pesaguard"
    with pytest.raises(FileNotFoundError):
        run_restore_drill.run(
            tmp_path / "missing.sql.gz",
            target_url,
            "SELECT 1",
            confirmed_isolated_target=True,
            application_database_url=app_url,
        )

    backup = tmp_path / "backup.sql.gz"
    backup.write_bytes(b"backup")
    monkeypatch.setattr(
        run_restore_drill.subprocess,
        "run",
        lambda *args, **kwargs: type(
            "Result",
            (),
            {"returncode": 1, "stderr": "restore failed", "stdout": ""},
        )(),
    )
    result = run_restore_drill.run(
        backup,
        target_url,
        "SELECT 1",
        confirmed_isolated_target=True,
        application_database_url=app_url,
    )
    assert result["passed"] is False
    assert result["restore_stderr"] == "restore failed"


def test_sentinel_restore_drill_cli_writes_a_confirmed_report(
    tmp_path: Path,
    monkeypatch,
):
    import sys

    from pesaguard_backend_pipeline.operations import run_restore_drill

    backup = tmp_path / "backup.sql.gz"
    backup.write_bytes(b"backup")
    output = tmp_path / "restore-result.json"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "run_restore_drill.py",
            str(backup),
            "--target-database-url",
            "postgresql://restore:secret@restore.example/restore_db",
            "--json-path",
            str(output),
            "--confirm-isolated-target",
        ],
    )
    monkeypatch.setattr(
        run_restore_drill.subprocess,
        "run",
        lambda command, **kwargs: type(
            "Result",
            (),
            {"returncode": 0, "stderr": "", "stdout": "1"},
        )(),
    )

    run_restore_drill.main()

    report = output.read_text(encoding="utf-8")
    assert json.loads(report)["passed"] is True
    assert "secret" not in report


def test_backup_restore_cli_requires_explicit_target_confirmation(monkeypatch):
    import sys

    from pesaguard_backend_pipeline import backup_postgres

    monkeypatch.setattr(sys, "argv", ["backup_postgres.py", "--restore", "backup.sql.gz"])

    with pytest.raises(SystemExit) as error:
        backup_postgres.main()

    assert error.value.code == 2


def test_backup_restore_cli_accepts_explicit_confirmation(monkeypatch):
    import sys

    from pesaguard_backend_pipeline import backup_postgres

    restored = []
    monkeypatch.setattr(
        backup_postgres,
        "restore_backup",
        lambda backup, *, confirm_target=False: restored.append((backup.name, confirm_target)),
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "backup_postgres.py",
            "--restore",
            "backup.sql.gz",
            "--confirm-restore-target",
        ],
    )

    backup_postgres.main()

    assert restored == [("backup.sql.gz", True)]


def test_restore_backup_function_requires_explicit_confirmation():
    from pesaguard_backend_pipeline import backup_postgres

    with pytest.raises(ValueError, match="requires --confirm-restore-target"):
        backup_postgres.restore_backup(Path("not-opened.sql.gz"))


def test_pitr_restore_requires_confirmation_before_changing_recovery_configuration(
    tmp_path: Path,
    monkeypatch,
):
    from pesaguard_backend_pipeline import backup_postgres

    pg_data = tmp_path / "pgdata"
    pg_data.mkdir()
    config = pg_data / "postgresql.auto.conf"
    config.write_text("# unchanged\n", encoding="utf-8")
    backup = tmp_path / "base.tar"
    backup.write_bytes(b"base backup")
    backup_postgres._write_manifest(backup)
    monkeypatch.setenv("PGDATA", str(pg_data))

    with pytest.raises(ValueError, match="requires --confirm-restore-target"):
        backup_postgres.pitr_restore(
            backup,
            target_time="2026-10-14 12:00:00",
        )

    assert config.read_text(encoding="utf-8") == "# unchanged\n"


def test_restore_target_comparison_uses_host_port_and_database_not_credentials():
    with pytest.raises(ValueError, match="matches the configured application database"):
        restore_safety.validate_restore_target(
            "postgresql://restore-user:one@DB.EXAMPLE.:5432/pesaguard",
            application_database_url="postgresql://app-user:two@db.example/pesaguard",
            confirmed_isolated_target=True,
        )


def test_sentinel_restore_drill_requires_confirmation_and_redacts_target_secrets(
    tmp_path: Path,
    monkeypatch,
):
    import json

    from pesaguard_backend_pipeline.operations import run_restore_drill

    backup = tmp_path / "backup.sql.gz"
    backup.write_bytes(b"backup")
    calls = []
    monkeypatch.setattr(
        run_restore_drill.subprocess,
        "run",
        lambda command, **kwargs: (
            calls.append(command)
            or type("Result", (), {"returncode": 0, "stderr": "", "stdout": "1"})()
        ),
    )
    target_url = "postgresql://private-user:private-password@restore.example:5433/restore_db"

    with pytest.raises(ValueError, match="explicit isolated-target confirmation"):
        run_restore_drill.run(backup, target_url, "SELECT 1")
    assert calls == []

    result = run_restore_drill.run(
        backup,
        target_url,
        "SELECT 1",
        confirmed_isolated_target=True,
    )

    assert result["passed"] is True
    assert result["target_database"] == "restore.example:5433/restore_db"
    assert calls[0][-1] == "--confirm-restore-target"
    assert "private-user" not in json.dumps(result)
    assert "private-password" not in json.dumps(result)
