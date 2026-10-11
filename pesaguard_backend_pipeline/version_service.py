"""Safe release and runtime version information for health endpoints."""

from __future__ import annotations

import os
import re

from environment import runtime_environment


class VersionService:
    def __init__(self, *, service_name: str = "pesaguard"):
        if not service_name or not service_name.strip():
            raise ValueError("service_name is required")
        self.service_name = service_name.strip()

    def snapshot(self) -> dict[str, str]:
        release = (
            os.getenv("PESAGUARD_RELEASE")
            or os.getenv("GIT_SHA")
            or os.getenv("SENTRY_RELEASE")
            or "unknown"
        ).strip()
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}", release):
            release = "unknown"
        return {
            "service": self.service_name,
            "version": release,
            "environment": runtime_environment(),
        }
