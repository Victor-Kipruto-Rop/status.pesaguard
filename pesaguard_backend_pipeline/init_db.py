"""
One-shot initialization script to construct database tables for PesaGuard.

Deployment / Usage:
  python3 init_db.py
"""

from __future__ import annotations

from environment import required_env

import logging
import os
import sys
from pathlib import Path

# Add project root directory dynamically to sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from migration_service import MigrationService

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("pesaguard.init_db")

DATABASE_URL = required_env("DATABASE_URL")


def main():
    MigrationService(DATABASE_URL).upgrade()
    logger.info("Database migrations applied successfully.")


if __name__ == "__main__":
    main()
