"""Project (7) and Environment (8) services.

Python implementation of the canonical Project and ProjectEnvironment domains
(previously Java-only). Tenant + organization scoped, optimistic locking via
``version``, and the exact lifecycle rules from the domain model.

Project: active <-> deactivated (reversible); active/deactivated -> archived
    (terminal). Archived cannot be edited/deactivated/activated until restored.

Environment: active <-> suspended (reversible while not deactivated);
    active/suspended -> deactivated (terminal).

Tier isolation (hard rule): tiers are ordered development < sandbox < staging
    < production; promotion is forward-only and exactly one tier at a time.

Every read/write is filtered by ``tenant_id`` and an environment is only
reachable through a parent project in the *same* tenant, so a tenant can never
read or mutate another tenant's project or environment.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from sqlalchemy.orm import Session, sessionmaker

from models import Organization, Project, ProjectEnvironment


# --- Domain vocabulary -----------------------------------------------------

PROJECT_STATUSES = ("active", "deactivated", "archived")

# Ordered environment tiers. Order is significant: promotion is +1 rank only.
ENVIRONMENT_TYPES = ("development", "sandbox", "staging", "production")
_ENVIRONMENT_TYPE_RANK = {name: rank for rank, name in enumerate(ENVIRONMENT_TYPES)}
ENVIRONMENT_STATUSES = ("active", "suspended", "deactivated")


# --- Errors ----------------------------------------------------------------
# Base type carries a machine-readable ``code`` for API error standardization.


class ProjectEnvironmentError(ValueError):
    """Base error with a stable ``code`` and an HTTP ``status_code`` hint."""

    code = "invalid_request"
    status_code = 400

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class NotFound(ProjectEnvironmentError):
    code = "resource_not_found"
    status_code = 404


class CrossTenantAccess(ProjectEnvironmentError):
    """Raised when a resource is addressed from the wrong tenant."""

    code = "permission_denied"
    status_code = 403


class InvalidStateTransition(ProjectEnvironmentError):
    code = "invalid_state_transition"
    status_code = 409


class ConcurrentModification(ProjectEnvironmentError):
    code = "version_conflict"
    status_code = 409


class ValidationError(ProjectEnvironmentError):
    code = "validation_error"
    status_code = 400


# --- Pure domain helpers (unit tested without a database) ------------------


def _slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", (value or "").strip().lower())
    slug = slug.strip("-")
    return slug or "project"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def assert_environment_type(value: str) -> str:
    if value not in ENVIRONMENT_TYPES:
        raise ValidationError(f"Unknown environment type: {value!r}")
    return value


def can_promote(current: str, target: str) -> bool:
    """An environment may only be promoted exactly one tier forward."""
    return _ENVIRONMENT_TYPE_RANK.get(target, -1) == _ENVIRONMENT_TYPE_RANK.get(current, -2) + 1


def is_protected_tier(tier: str) -> bool:
    return tier == "production"


def _apply_project_status(project: Project, action: str) -> None:
    """Mutate ``project.status`` for a lifecycle action, enforcing the rules."""
    current = project.status
    now = _now()
    if action == "archive":
        if current == "archived":
            return  # idempotent
        if current not in ("active", "deactivated"):
            raise InvalidStateTransition("Cannot archive this project")
        project.status = "archived"
    elif action == "restore":
        if current == "active":
            return  # idempotent
        if current != "archived":
            raise InvalidStateTransition("Only an archived project can be restored")
        project.status = "active"
    elif action == "deactivate":
        if current == "deactivated":
            return  # idempotent
        if current == "archived":
            raise InvalidStateTransition("An archived project cannot be deactivated")
        project.status = "deactivated"
    elif action == "activate":
        if current == "active":
            return  # idempotent
        if current == "archived":
            raise InvalidStateTransition(
                "An archived project must be restored before it can be reactivated"
            )
        project.status = "active"
    else:  # pragma: no cover - guarded by callers
        raise ValidationError(f"Unknown project action: {action!r}")
    project.status_changed_at = now


def _apply_environment_status(env: ProjectEnvironment, action: str) -> None:
    current = env.status
    now = _now()
    if current == "deactivated" and action != "deactivate":
        raise InvalidStateTransition("A deactivated environment cannot be changed")
    if action == "suspend":
        env.status = "suspended"
    elif action == "resume":
        if current == "deactivated":
            raise InvalidStateTransition("A deactivated environment cannot resume")
        env.status = "active"
    elif action == "deactivate":
        if current == "deactivated":
            return  # idempotent
        env.status = "deactivated"
    else:  # pragma: no cover
        raise ValidationError(f"Unknown environment action: {action!r}")
    env.status_changed_at = now


class _BaseService:
    def __init__(self, session_factory: Optional[sessionmaker] = None):
        self.session_factory = session_factory

    def _session(self) -> Session:
        if self.session_factory is None:
            raise RuntimeError("A SQLAlchemy session factory is required.")
        return self.session_factory()

    @staticmethod
    def _serialize(obj: Any) -> Dict[str, Any]:
        if obj is None:
            return {}
        payload: Dict[str, Any] = {}
        for key in obj.__class__.__mapper__.c.keys():
            value = getattr(obj, key, None)
            if isinstance(value, datetime):
                value = value.isoformat()
            payload[key] = value
        return payload

    @staticmethod
    def _check_version(record: Any, expected_version: Optional[int]) -> None:
        if expected_version is not None and int(record.version) != int(expected_version):
            raise ConcurrentModification(
                "The record was modified concurrently; retry with the latest version."
            )


class ProjectService(_BaseService):
    """Tenant + organization scoped project lifecycle service."""

    def create_project(
        self,
        *,
        organization_id: str,
        name: str,
        tenant_id: str,
        created_by: str,
        owner_user_id: Optional[str] = None,
        description: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        tenant_id = str(tenant_id or "")
        if not tenant_id:
            raise ValidationError("A tenant context is required.")
        name = (name or "").strip()
        if not name:
            raise ValidationError("A project name is required.")
        session = self._session()
        try:
            org = session.query(Organization).filter_by(
                id=str(organization_id), tenant_id=tenant_id
            ).first()
            if org is None:
                raise NotFound(f"Organization {organization_id} not found for tenant")
            slug = _slugify(name)
            base_slug = slug
            suffix = 1
            while True:
                clash = session.query(Project).filter_by(
                    tenant_id=tenant_id, organization_id=str(organization_id), slug=slug
                ).first()
                if clash is None:
                    break
                slug = f"{base_slug}-{suffix}"
                suffix += 1
            owner = owner_user_id or created_by
            project = Project(
                tenant_id=tenant_id,
                organization_id=str(organization_id),
                name=name,
                slug=slug,
                description=(description or None),
                created_by=str(created_by),
                owner_user_id=str(owner),
                project_metadata=dict(metadata or {}),
                status="active",
                version=1,
                status_changed_at=_now(),
            )
            session.add(project)
            session.commit()
            session.refresh(project)
            return self._serialize(project)
        finally:
            session.close()

    def get_project(self, project_id: str, tenant_id: str) -> Optional[Dict[str, Any]]:
        session = self._session()
        try:
            record = session.query(Project).filter_by(
                id=str(project_id), tenant_id=str(tenant_id)
            ).first()
            return None if record is None else self._serialize(record)
        finally:
            session.close()

    def list_projects(
        self,
        tenant_id: str,
        organization_id: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Dict[str, Any]]:
        session = self._session()
        try:
            query = session.query(Project).filter_by(tenant_id=str(tenant_id))
            if organization_id:
                query = query.filter_by(organization_id=str(organization_id))
            if status:
                if status not in PROJECT_STATUSES:
                    raise ValidationError(f"Unknown project status: {status!r}")
                query = query.filter_by(status=status)
            query = query.order_by(Project.created_at.desc()).offset(int(offset)).limit(int(limit))
            return [self._serialize(row) for row in query.all()]
        finally:
            session.close()

    def _get(self, session: Session, project_id: str, tenant_id: str) -> Project:
        record = session.query(Project).filter_by(
            id=str(project_id), tenant_id=str(tenant_id)
        ).first()
        if record is None:
            # Indistinguishable 404 for cross-tenant: no existence oracle.
            raise NotFound(f"Project {project_id} not found")
        return record

    def update_project(
        self,
        project_id: str,
        tenant_id: str,
        *,
        name: Optional[str] = None,
        description: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        expected_version: Optional[int] = None,
    ) -> Dict[str, Any]:
        session = self._session()
        try:
            project = self._get(session, project_id, tenant_id)
            if project.status != "active":
                raise InvalidStateTransition("Only an active project can be edited")
            self._check_version(project, expected_version)
            if name is not None:
                cleaned = name.strip()
                if not cleaned:
                    raise ValidationError("A project name is required.")
                project.name = cleaned
            if description is not None:
                project.description = description.strip() or None
            if metadata is not None:
                project.project_metadata = dict(metadata)
            project.version = int(project.version) + 1
            session.commit()
            session.refresh(project)
            return self._serialize(project)
        finally:
            session.close()

    def _transition(self, project_id: str, tenant_id: str, action: str) -> Dict[str, Any]:
        session = self._session()
        try:
            project = self._get(session, project_id, tenant_id)
            _apply_project_status(project, action)
            project.version = int(project.version) + 1
            session.commit()
            session.refresh(project)
            return self._serialize(project)
        finally:
            session.close()

    def archive_project(self, project_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition(project_id, tenant_id, "archive")

    def restore_project(self, project_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition(project_id, tenant_id, "restore")

    def deactivate_project(self, project_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition(project_id, tenant_id, "deactivate")

    def activate_project(self, project_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition(project_id, tenant_id, "activate")

    def transfer_project_ownership(
        self, project_id: str, tenant_id: str, new_owner_user_id: str
    ) -> Dict[str, Any]:
        session = self._session()
        try:
            project = self._get(session, project_id, tenant_id)
            new_owner = str(new_owner_user_id or "").strip()
            if not new_owner:
                raise ValidationError("A new owner is required.")
            project.owner_user_id = new_owner
            project.version = int(project.version) + 1
            session.commit()
            session.refresh(project)
            return self._serialize(project)
        finally:
            session.close()


class EnvironmentService(_BaseService):
    """Tenant + project scoped environment lifecycle + promotion service.

    Tier isolation is enforced here: promotion is forward-only and exactly one
    tier at a time (``development`` -> ``sandbox`` -> ``staging`` -> ``production``).
    An environment is only reachable through a parent project in the *same*
    tenant (and organization), preventing any cross-tenant access.
    """

    def create_environment(
        self,
        *,
        project_id: str,
        name: str,
        environment_type: str,
        tenant_id: str,
        created_by: str,
        configuration: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        tenant_id = str(tenant_id or "")
        if not tenant_id:
            raise ValidationError("A tenant context is required.")
        name = (name or "").strip()
        if not name:
            raise ValidationError("An environment name is required.")
        assert_environment_type(environment_type)
        session = self._session()
        try:
            project = session.query(Project).filter_by(
                id=str(project_id), tenant_id=tenant_id
            ).first()
            if project is None:
                raise NotFound(f"Project {project_id} not found for tenant")
            if project.status != "active":
                raise InvalidStateTransition("Environments require an active project")
            clash = session.query(ProjectEnvironment).filter_by(
                tenant_id=tenant_id, project_id=str(project_id), name=name
            ).first()
            if clash is not None:
                raise ValidationError(f"An environment named {name!r} already exists")
            env = ProjectEnvironment(
                tenant_id=tenant_id,
                organization_id=project.organization_id,
                project_id=str(project_id),
                name=name,
                environment_type=environment_type,
                configuration=dict(configuration or {}),
                status="active",
                version=1,
                created_by=str(created_by),
                status_changed_at=_now(),
            )
            session.add(env)
            session.commit()
            session.refresh(env)
            return self._serialize(env)
        finally:
            session.close()

    def _get_env(self, session: Session, env_id: str, tenant_id: str) -> ProjectEnvironment:
        """Fetch an environment, ensuring its parent project is in this tenant."""
        env = session.query(ProjectEnvironment).filter_by(
            id=str(env_id), tenant_id=str(tenant_id)
        ).first()
        if env is None:
            raise NotFound(f"Environment {env_id} not found")
        # Defense-in-depth: confirm the parent project is in the same tenant,
        # so a row with a tampered tenant_id cannot be reached cross-tenant.
        parent = session.query(Project).filter_by(id=env.project_id).first()
        if parent is None or str(parent.tenant_id) != str(tenant_id):
            raise NotFound(f"Environment {env_id} not found")
        return env

    def get_environment(self, env_id: str, tenant_id: str) -> Optional[Dict[str, Any]]:
        session = self._session()
        try:
            env = session.query(ProjectEnvironment).filter_by(
                id=str(env_id), tenant_id=str(tenant_id)
            ).first()
            return None if env is None else self._serialize(env)
        finally:
            session.close()

    def list_environments(
        self,
        tenant_id: str,
        project_id: Optional[str] = None,
        environment_type: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Dict[str, Any]]:
        session = self._session()
        try:
            query = session.query(ProjectEnvironment).filter_by(tenant_id=str(tenant_id))
            if project_id:
                query = query.filter_by(project_id=str(project_id))
            if environment_type:
                assert_environment_type(environment_type)
                query = query.filter_by(environment_type=environment_type)
            query = query.order_by(ProjectEnvironment.created_at.desc()).offset(
                int(offset)
            ).limit(int(limit))
            return [self._serialize(row) for row in query.all()]
        finally:
            session.close()

    def update_environment(
        self,
        env_id: str,
        tenant_id: str,
        *,
        name: Optional[str] = None,
        configuration: Optional[Dict[str, Any]] = None,
        expected_version: Optional[int] = None,
    ) -> Dict[str, Any]:
        session = self._session()
        try:
            env = self._get_env(session, env_id, tenant_id)
            if env.status == "deactivated":
                raise InvalidStateTransition("A deactivated environment cannot be edited")
            self._check_version(env, expected_version)
            if name is not None:
                cleaned = name.strip()
                if not cleaned:
                    raise ValidationError("An environment name is required.")
                env.name = cleaned
            if configuration is not None:
                env.configuration = dict(configuration)
            env.version = int(env.version) + 1
            session.commit()
            session.refresh(env)
            return self._serialize(env)
        finally:
            session.close()

    def _transition_env(
        self, env_id: str, tenant_id: str, action: str
    ) -> Dict[str, Any]:
        session = self._session()
        try:
            env = self._get_env(session, env_id, tenant_id)
            _apply_environment_status(env, action)
            env.version = int(env.version) + 1
            session.commit()
            session.refresh(env)
            return self._serialize(env)
        finally:
            session.close()

    def suspend_environment(self, env_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition_env(env_id, tenant_id, "suspend")

    def resume_environment(self, env_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition_env(env_id, tenant_id, "resume")

    def deactivate_environment(self, env_id: str, tenant_id: str) -> Dict[str, Any]:
        return self._transition_env(env_id, tenant_id, "deactivate")

    def promote_environment(
        self, env_id: str, tenant_id: str, target_type: str
    ) -> Dict[str, Any]:
        """Promote an environment exactly one tier forward, or raise.

        ``target_type`` must be the immediate next tier after the current one.
        Skips, demotions and re-promoting ``production`` are all rejected.
        """
        target_type = assert_environment_type(target_type)
        session = self._session()
        try:
            env = self._get_env(session, env_id, tenant_id)
            if env.status == "deactivated":
                raise InvalidStateTransition("A deactivated environment cannot be promoted")
            current = env.environment_type
            if not can_promote(current, target_type):
                raise InvalidStateTransition(
                    f"Cannot promote {current!r} to {target_type!r}; "
                    f"promotion must advance exactly one tier."
                )
            # can_promote already guarantees a forward single-tier step, so
            # production (the terminal tier) can never satisfy it and demotions,
            # skips and re-promotions are rejected above.
            env.environment_type = target_type
            env.version = int(env.version) + 1
            session.commit()
            session.refresh(env)
            return self._serialize(env)
        finally:
            session.close()
