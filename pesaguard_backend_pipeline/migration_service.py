"""Explicit Alembic migration runner for deployment and recovery workflows."""

from __future__ import annotations

import logging
from pathlib import Path

from alembic import command
from alembic.config import Config

logger = logging.getLogger("pesaguard.migrations")


class MigrationService:
    def __init__(self, database_url: str, *, config_path: str | Path | None = None) -> None:
        if not isinstance(database_url, str) or not database_url.strip():
            raise ValueError("database_url is required")
        self.database_url = database_url
        self.config_path = Path(config_path) if config_path else (
            Path(__file__).resolve().parent / "alembic.ini"
        )
        if not self.config_path.is_file():
            raise FileNotFoundError("Alembic configuration file is unavailable")

    def upgrade(self, revision: str = "head") -> None:
        if not isinstance(revision, str) or not revision.strip():
            raise ValueError("migration revision is required")
        config = Config(str(self.config_path))
        config.set_main_option("sqlalchemy.url", self.database_url.replace("%", "%%"))
        logger.info("Applying database migrations through revision %s.", revision)
        command.upgrade(config, revision.strip())

    def current(self) -> None:
        config = Config(str(self.config_path))
        config.set_main_option("sqlalchemy.url", self.database_url.replace("%", "%%"))
        command.current(config, verbose=False)

    def downgrade(self, revision: str) -> None:
        if not isinstance(revision, str) or not revision.strip():
            raise ValueError("migration revision is required")
        config = Config(str(self.config_path))
        config.set_main_option("sqlalchemy.url", self.database_url.replace("%", "%%"))
        logger.warning("Downgrading database schema to revision %s.", revision)
        command.downgrade(config, revision.strip())
