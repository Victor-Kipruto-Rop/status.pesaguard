"""Restore one backup into an isolated PostgreSQL target and validate it."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from pesaguard_backend_pipeline.operations import restore_safety, validate_restore


def run_restore_drill(
    backup_file: Path,
    database_url: str,
    output: Path,
    *,
    confirmed_isolated_target: bool = False,
    application_database_url: str | None = None,
) -> dict[str, object]:
    """Restore and validate a backup without touching the production database."""
    target = restore_safety.validate_restore_target(
        database_url,
        application_database_url=application_database_url or os.getenv("DATABASE_URL"),
        confirmed_isolated_target=confirmed_isolated_target,
    )
    started = time.monotonic()
    result: dict[str, object] = {
        "started_at": datetime.now(timezone.utc).isoformat(),
        "backup_file": str(backup_file),
        "database_url_host": target,
        "status": "failed",
    }
    if not backup_file.is_file():
        result["error"] = "backup file does not exist"
    else:
        environment = os.environ.copy()
        environment["DATABASE_URL"] = database_url
        command = [
            sys.executable,
            str(Path(__file__).parents[1] / "backup_postgres.py"),
            "--restore",
            str(backup_file),
            "--confirm-restore-target",
        ]
        restored = subprocess.run(command, env=environment, capture_output=True, text=True, check=False)
        if restored.returncode != 0:
            result["error"] = restored.stderr[-2000:] or "restore command failed"
        else:
            validation = validate_restore.validate(database_url)
            result["validation"] = validation
            result["status"] = "passed" if validation["status"] == "passed" else "failed"
    result["duration_seconds"] = round(time.monotonic() - started, 3)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backup-file", type=Path, required=True)
    parser.add_argument("--database-url", default=os.getenv("RESTORE_DATABASE_URL", ""))
    parser.add_argument("--output", type=Path, default=Path("restore-drill.json"))
    parser.add_argument(
        "--confirm-isolated-target",
        action="store_true",
        help="confirm that the target is a disposable, isolated recovery database",
    )
    args = parser.parse_args()
    if not args.database_url:
        parser.error("--database-url or RESTORE_DATABASE_URL is required")
    if not args.confirm_isolated_target:
        parser.error("--confirm-isolated-target is required for restore drills")
    result = run_restore_drill(
        args.backup_file,
        args.database_url,
        args.output,
        confirmed_isolated_target=args.confirm_isolated_target,
    )
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())