import gzip
import io
import json
import sys
from datetime import datetime, timedelta, timezone

import pytest

from pesaguard_backend_pipeline import backup_postgres


class FakeStream:
    def __init__(self, value=b""):
        self.value = value
        self.closed = False

    def read(self, size=-1):
        if size < 0:
            value, self.value = self.value, b""
            return value
        value, self.value = self.value[:size], self.value[size:]
        return value

    def close(self):
        self.closed = True


class FakeDumpProcess:
    returncode = 0

    def __init__(self):
        self.stdout = FakeStream(
            b"-- PostgreSQL database dump\nCREATE TABLE sample (id integer);\n"
        )

    def communicate(self):
        return None, b""


class FakeGzipProcess:
    returncode = 0

    def __init__(self, output):
        self.output = output

    def communicate(self):
        self.output.write(
            gzip.compress(
                b"-- PostgreSQL database dump\nCREATE TABLE sample (id integer);\n"
            )
        )
        return None, b""


class FakePsqlProcess:
    def __init__(self, returncode=0):
        self.returncode = returncode
        self.stdin = io.BytesIO()

    def communicate(self):
        return b"", b"restore failed" if self.returncode else b""


def test_create_backup_streams_dump_into_compressed_manifested_artifact(
    tmp_path,
    monkeypatch,
):
    calls = []
    monkeypatch.setattr(backup_postgres, "DATABASE_URL", "postgresql://backup@db.example/pesaguard")
    monkeypatch.setattr(backup_postgres, "BACKUP_DIR", tmp_path)
    monkeypatch.setattr(backup_postgres, "STATUS_FILE", tmp_path / "status.json")
    monkeypatch.setattr(backup_postgres, "ENCRYPT_COMMAND", "")
    monkeypatch.setattr(backup_postgres, "UPLOAD_COMMAND", "")

    def fake_popen(command, **kwargs):
        calls.append(command)
        if command[0] == "pg_dump":
            return FakeDumpProcess()
        return FakeGzipProcess(kwargs["stdout"])

    monkeypatch.setattr(backup_postgres.subprocess, "Popen", fake_popen)
    monkeypatch.delenv("PESAGUARD_ENVIRONMENT", raising=False)
    monkeypatch.delenv("PESAGUARD_BACKUP_ENCRYPT_COMMAND_REQUIRED", raising=False)

    backup = backup_postgres.create_backup()

    assert backup.is_file()
    assert backup.name.endswith(".sql.gz")
    assert backup_postgres._verify_manifest(backup)
    assert len(calls) == 2
    assert calls[0][0] == "pg_dump"
    assert calls[1] == ["gzip", "-9"]
    assert json.loads((tmp_path / "status.json").read_text(encoding="utf-8"))["status"] == "succeeded"
    assert backup_postgres.test_backup_integrity(backup) is True


@pytest.mark.parametrize("returncode", [0, 1])
def test_restore_streams_verified_sql_gzip_to_psql_and_reports_failures(
    tmp_path,
    monkeypatch,
    returncode,
):
    artifact = tmp_path / "backup.sql.gz"
    artifact.write_bytes(
        gzip.compress(b"-- PostgreSQL database dump\nCREATE TABLE sample (id integer);\n")
    )
    backup_postgres._write_manifest(artifact)
    psql = FakePsqlProcess(returncode=returncode)
    commands = []
    monkeypatch.setattr(backup_postgres, "DATABASE_URL", "postgresql://restore@db.example/pesaguard_restore")
    monkeypatch.setattr(
        backup_postgres.subprocess,
        "Popen",
        lambda command, **kwargs: commands.append(command) or psql,
    )
    if returncode:
        with pytest.raises(SystemExit) as error:
            backup_postgres.restore_backup(artifact, confirm_target=True)
        assert error.value.code == 1
    else:
        backup_postgres.restore_backup(artifact, confirm_target=True)
        assert b"PostgreSQL database dump" in psql.stdin.getvalue()
    assert commands[0][0] == "psql"
    assert commands[0][-2:] == ["-f", "-"]


def test_backup_retention_removes_only_expired_timestamped_artifacts_and_manifests(
    tmp_path,
    monkeypatch,
):
    monkeypatch.setattr(backup_postgres, "BACKUP_DIR", tmp_path)
    monkeypatch.setattr(backup_postgres, "RETENTION_DAYS", 2)
    old_timestamp = (datetime.now(timezone.utc) - timedelta(days=4)).strftime("%Y%m%d_%H%M%S")
    recent_timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    old_backup = tmp_path / f"pesaguard_{old_timestamp}.sql.gz"
    old_backup.write_bytes(b"old")
    old_manifest = backup_postgres._write_manifest(old_backup)
    recent_backup = tmp_path / f"pesaguard_{recent_timestamp}.sql.gz"
    recent_backup.write_bytes(b"recent")
    malformed = tmp_path / "pesaguard_unknown.sql.gz"
    malformed.write_bytes(b"unknown")

    backup_postgres._cleanup_old_backups()

    assert not old_backup.exists()
    assert not old_manifest.exists()
    assert recent_backup.exists()
    assert malformed.exists()


def test_offsite_upload_requires_production_configuration_and_surfaces_failure(
    tmp_path,
    monkeypatch,
):
    artifact = tmp_path / "backup.sql.gz"
    artifact.write_bytes(b"backup")
    monkeypatch.setattr(backup_postgres, "UPLOAD_COMMAND", "")
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "production")
    with pytest.raises(RuntimeError, match="required for off-site"):
        backup_postgres._upload_offsite(artifact)

    monkeypatch.setattr(backup_postgres, "UPLOAD_COMMAND", "uploader {backup}")
    monkeypatch.setattr(
        backup_postgres.subprocess,
        "run",
        lambda *args, **kwargs: type(
            "Result",
            (),
            {"returncode": 1, "stderr": "remote storage rejected upload"},
        )(),
    )
    with pytest.raises(RuntimeError, match="remote storage rejected upload"):
        backup_postgres._upload_offsite(artifact)

    uploads = []
    monkeypatch.setattr(
        backup_postgres.subprocess,
        "run",
        lambda command, **kwargs: (
            uploads.append(command)
            or type(
                "Result",
                (),
                {"returncode": 0, "stderr": ""},
            )()
        ),
    )
    backup_postgres._upload_offsite(artifact)
    assert uploads == [["uploader", str(artifact)]]


def test_production_backup_refuses_missing_offsite_copy_configuration(
    monkeypatch,
    tmp_path,
):
    monkeypatch.setenv("PESAGUARD_ENVIRONMENT", "production")
    monkeypatch.setattr(backup_postgres, "ENCRYPT_COMMAND", "encrypt")
    monkeypatch.setattr(backup_postgres, "UPLOAD_COMMAND", "")
    monkeypatch.setattr(backup_postgres, "BACKUP_DIR", tmp_path)

    with pytest.raises(RuntimeError, match="PESAGUARD_BACKUP_UPLOAD_COMMAND"):
        backup_postgres.create_backup()


def test_restore_rejects_missing_and_tampered_backup_artifacts(tmp_path):
    with pytest.raises(SystemExit) as missing_error:
        backup_postgres.restore_backup(
            tmp_path / "missing.sql.gz",
            confirm_target=True,
        )
    assert missing_error.value.code == 1

    artifact = tmp_path / "tampered.sql.gz"
    artifact.write_bytes(b"backup")
    backup_postgres._write_manifest(artifact)
    artifact.write_bytes(b"changed")
    with pytest.raises(SystemExit) as tampered_error:
        backup_postgres.restore_backup(artifact, confirm_target=True)
    assert tampered_error.value.code == 1


def test_backup_cli_lists_artifacts_and_fails_when_no_test_backup_exists(
    tmp_path,
    monkeypatch,
):
    artifact = tmp_path / "pesaguard_20261014_100000.sql.gz"
    artifact.write_bytes(b"backup")
    monkeypatch.setattr(backup_postgres, "BACKUP_DIR", tmp_path)
    monkeypatch.setattr(sys, "argv", ["backup_postgres.py", "--list"])

    backup_postgres.main()
    assert artifact in backup_postgres._backup_artifacts()

    monkeypatch.setattr(backup_postgres, "BACKUP_DIR", tmp_path / "empty")
    monkeypatch.setattr(sys, "argv", ["backup_postgres.py", "--test"])
    with pytest.raises(SystemExit) as error:
        backup_postgres.main()
    assert error.value.code == 1


def test_integrity_verification_rejects_missing_corrupt_and_modified_artifacts(
    tmp_path,
):
    missing = tmp_path / "missing.sql.gz"
    assert backup_postgres.test_backup_integrity(missing) is False

    corrupt = tmp_path / "corrupt.sql.gz"
    corrupt.write_bytes(b"not gzip data")
    assert backup_postgres.test_backup_integrity(corrupt) is False

    artifact = tmp_path / "empty.sql.gz"
    artifact.write_bytes(gzip.compress(b"no SQL markers here\n"))
    assert backup_postgres.test_backup_integrity(artifact) is False

    valid = tmp_path / "valid.sql.gz"
    valid.write_bytes(gzip.compress(b"-- PostgreSQL database dump\nCREATE TABLE sample (id int);\n"))
    backup_postgres._write_manifest(valid)
    valid.write_bytes(valid.read_bytes() + b"tampered")
    assert backup_postgres.test_backup_integrity(valid) is False


@pytest.mark.parametrize(
    ("url", "message"),
    [
        ("sqlite:///local.db", "Unsupported database URL scheme"),
        ("postgresql://user:pass@host:notaport/db", "port"),
    ],
)
def test_backup_url_parser_rejects_unsupported_and_invalid_urls(url, message):
    with pytest.raises(ValueError, match=message):
        backup_postgres.parse_db_url(url)
