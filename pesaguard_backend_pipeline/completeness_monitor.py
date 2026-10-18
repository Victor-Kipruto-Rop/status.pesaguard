"""Completeness monitoring for scheduled file imports.

Detects missing and late files by comparing expected import schedules against
actual ImportJob records. Findings are persisted to DeadLetter for observability
and replay. No new database tables are required; existing ImportJob and
DeadLetter models are reused.

Usage:

    monitor = CompletenessMonitor(session, event_store)
    monitor.register_expected_import("tenant-a", "equity", "daily", "08:00")
    findings = monitor.check_completeness(now=datetime.now(timezone.utc))
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from event_store import EventStore
from models import ImportJob
from sqlalchemy.orm import Session


@dataclass(frozen=True)
class ExpectedImport:
    """A scheduled import expectation for one tenant/provider."""

    tenant_id: str
    provider: str
    schedule: str
    expected_time: str
    deadline_offset_minutes: int = 120


@dataclass(frozen=True)
class CompletenessFinding:
    """Result of a completeness check."""

    tenant_id: str
    provider: str
    expected_time: str
    check_time: str
    status: str
    import_id: Optional[str] = None
    received_at: Optional[str] = None
    minutes_late: Optional[int] = None


class CompletenessMonitor:
    """Monitors scheduled imports for missing and late files."""

    def __init__(self, session: Session, event_store: EventStore):
        self.session = session
        self.event_store = event_store
        self._expected: Dict[str, list[ExpectedImport]] = {}

    def register_expected_import(
        self,
        tenant_id: str,
        provider: str,
        schedule: str,
        expected_time: str,
        *,
        deadline_offset_minutes: int = 120,
    ) -> None:
        key = f"{tenant_id}:{provider}"
        entry = ExpectedImport(
            tenant_id=str(tenant_id).strip(),
            provider=str(provider).strip().lower(),
            schedule=str(schedule).strip().lower(),
            expected_time=str(expected_time).strip(),
            deadline_offset_minutes=int(deadline_offset_minutes),
        )
        self._expected.setdefault(key, []).append(entry)

    def _expected_for(self, tenant_id: str, provider: str) -> list[ExpectedImport]:
        return self._expected.get(f"{tenant_id}:{provider}", [])

    def _latest_import_for(
        self, tenant_id: str, provider: str, on_or_before: datetime
    ) -> Optional[ImportJob]:
        return (
            self.session.query(ImportJob)
            .filter(
                ImportJob.tenant_id == tenant_id,
                ImportJob.source == provider,
                ImportJob.created_at <= on_or_before,
            )
            .order_by(ImportJob.created_at.desc())
            .first()
        )

    def check_completeness(self, *, now: Optional[datetime] = None) -> list[CompletenessFinding]:
        check_time = now or datetime.now(timezone.utc)
        findings: list[CompletenessFinding] = []

        for key, expectations in self._expected.items():
            tenant_id, provider = key.split(":", 1)
            for expected in expectations:
                expected_time = self._parse_time(expected.expected_time)
                if expected_time is None:
                    continue
                expected_dt = check_time.replace(
                    hour=expected_time.hour,
                    minute=expected_time.minute,
                    second=0,
                    microsecond=0,
                )
                if expected_dt > check_time:
                    expected_dt -= timedelta(days=1)

                latest = self._latest_import_for(tenant_id, provider, check_time)
                deadline = expected_dt + timedelta(
                    minutes=expected.deadline_offset_minutes
                )

                if latest is None:
                    findings.append(
                        CompletenessFinding(
                            tenant_id=tenant_id,
                            provider=provider,
                            expected_time=expected_dt.isoformat(),
                            check_time=check_time.isoformat(),
                            status="missing",
                        )
                    )
                elif latest.created_at > deadline:
                    minutes_late = int(
                        (latest.created_at - deadline).total_seconds() / 60
                    )
                    findings.append(
                        CompletenessFinding(
                            tenant_id=tenant_id,
                            provider=provider,
                            expected_time=expected_dt.isoformat(),
                            check_time=check_time.isoformat(),
                            status="late",
                            import_id=latest.id,
                            received_at=latest.created_at.isoformat(),
                            minutes_late=minutes_late,
                        )
                    )
                else:
                    findings.append(
                        CompletenessFinding(
                            tenant_id=tenant_id,
                            provider=provider,
                            expected_time=expected_dt.isoformat(),
                            check_time=check_time.isoformat(),
                            status="ok",
                            import_id=latest.id,
                            received_at=latest.created_at.isoformat(),
                        )
                    )

        self._persist_findings(findings, check_time)
        return findings

    def _persist_findings(
        self, findings: list[CompletenessFinding], check_time: datetime
    ) -> None:
        from models import DeadLetter

        for finding in findings:
            if finding.status == "ok":
                continue
            payload = asdict(finding)
            reason = f"import_{finding.status}"
            detail = (
                f"{finding.provider} import for {finding.tenant_id} was "
                f"{finding.status}; expected {finding.expected_time}"
            )
            if finding.minutes_late is not None:
                detail += f", {finding.minutes_late} minutes late"
            try:
                self.event_store.write_dead_letter(
                    payload=payload,
                    reason=reason,
                    error_detail=detail,
                    tenant_id=finding.tenant_id,
                )
            except Exception:
                pass

    @staticmethod
    def _parse_time(value: str) -> Optional[datetime]:
        try:
            parts = str(value).strip().split(":")
            if len(parts) != 2:
                return None
            return datetime(
                1970, 1, 1, hour=int(parts[0]), minute=int(parts[1]), tzinfo=timezone.utc
            )
        except (ValueError, IndexError):
            return None

    def missing_imports(self, *, now: Optional[datetime] = None) -> list[CompletenessFinding]:
        return [f for f in self.check_completeness(now=now) if f.status == "missing"]

    def late_imports(self, *, now: Optional[datetime] = None) -> list[CompletenessFinding]:
        return [f for f in self.check_completeness(now=now) if f.status == "late"]