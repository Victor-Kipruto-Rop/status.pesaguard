"""Version-controlled transformation resource registry.

Stores datasets, reusable components, and workflow definitions as immutable
versions. Provides tenant-scoped search/discovery and dependency resolution
(topological execution order with cycle detection) for data preparation
resources. All reads and writes are tenant-scoped; definitions are opaque to
the registry (semantic validation is injected by callers via ``validator``).
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Mapping, Optional, Sequence

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from models import TransformationResource, TransformationResourceVersion

RESOURCE_TYPES = frozenset({"dataset", "component", "workflow"})


class RegistryError(ValueError):
    """Raised when a transformation resource operation cannot complete."""


class ResourceNotFound(RegistryError):
    """Raised when a tenant-scoped resource lookup finds nothing."""


class DependencyCycleError(RegistryError):
    """Raised when resource dependencies form a cycle."""


def _definition_hash(definition: Mapping[str, Any]) -> str:
    canonical = json.dumps(
        dict(definition), sort_keys=True, separators=(",", ":"), default=str
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ResourceSummary:
    name: str
    resource_type: str
    description: str
    tags: List[str]
    current_version: int
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class ResourceVersion:
    name: str
    resource_type: str
    version: int
    definition: Dict[str, Any]
    definition_hash: str
    created_at: datetime
    description: str = ""
    tags: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class ResourceVersionEntry:
    version: int
    definition_hash: str
    created_at: datetime


Validator = Callable[[Mapping[str, Any]], None]


class TransformationRegistry:
    """Tenant-scoped CRUD, search, versioning, and dependency resolution."""

    def __init__(self, session_factory: Callable[[], Session]):
        self.session_factory = session_factory

    # --- validation helpers -------------------------------------------------

    @staticmethod
    def _check_tenant(tenant_id: str) -> str:
        scoped = str(tenant_id or "").strip()
        if not scoped:
            raise RegistryError("tenant_id is required")
        return scoped

    @staticmethod
    def _check_name(name: str) -> str:
        cleaned = str(name or "").strip()
        if not cleaned or len(cleaned) > 128:
            raise RegistryError("resource name is required (max 128 characters)")
        return cleaned

    @staticmethod
    def _check_type(resource_type: str) -> str:
        normalized = str(resource_type or "").strip().lower()
        if normalized not in RESOURCE_TYPES:
            raise RegistryError(
                f"unsupported resource type {resource_type!r}; expected one of "
                f"{', '.join(sorted(RESOURCE_TYPES))}"
            )
        return normalized

    @staticmethod
    def dependencies(definition: Mapping[str, Any]) -> List[str]:
        """Extract declared dependencies (``depends_on``) from a definition."""
        raw = dict(definition).get("depends_on", [])
        if not isinstance(raw, (list, tuple)):
            raise RegistryError("depends_on must be a list of resource names")
        names = sorted({str(item).strip() for item in raw if str(item).strip()})
        return names

    # --- persistence helpers ------------------------------------------------

    @staticmethod
    def _to_summary(row: TransformationResource) -> ResourceSummary:
        return ResourceSummary(
            name=row.name,
            resource_type=row.resource_type,
            description=row.description or "",
            tags=list(row.tags or []),
            current_version=row.current_version,
            created_at=row.created_at,
            updated_at=row.updated_at,
        )

    @staticmethod
    def _to_version(
        resource: TransformationResource, row: TransformationResourceVersion
    ) -> ResourceVersion:
        return ResourceVersion(
            name=resource.name,
            resource_type=resource.resource_type,
            version=row.version,
            definition=dict(row.definition),
            definition_hash=row.definition_hash,
            created_at=row.created_at,
            description=resource.description or "",
            tags=list(resource.tags or []),
        )

    def _find_resource(
        self, session: Session, tenant_id: str, name: str, resource_type: Optional[str]
    ) -> TransformationResource:
        query = session.query(TransformationResource).filter(
            TransformationResource.tenant_id == tenant_id,
            TransformationResource.name == name,
        )
        if resource_type is not None:
            query = query.filter(TransformationResource.resource_type == resource_type)
        rows = query.all()
        if not rows:
            raise ResourceNotFound(f"resource not found: {name}")
        if len(rows) > 1:
            raise RegistryError(
                f"resource name {name!r} exists for multiple types; specify resource_type"
            )
        return rows[0]

    # --- CRUD and versioning ------------------------------------------------

    def create(
        self,
        *,
        tenant_id: str,
        resource_type: str,
        name: str,
        definition: Mapping[str, Any],
        description: str = "",
        tags: Sequence[str] = (),
        validator: Optional[Validator] = None,
    ) -> ResourceVersion:
        scoped_tenant = self._check_tenant(tenant_id)
        scoped_type = self._check_type(resource_type)
        scoped_name = self._check_name(name)
        if not isinstance(definition, Mapping):
            raise RegistryError("definition must be an object")
        if validator is not None:
            validator(definition)
        self.dependencies(definition)
        definition_dict = dict(definition)
        try:
            with self.session_factory() as session:
                existing = (
                    session.query(TransformationResource.id)
                    .filter(
                        TransformationResource.tenant_id == scoped_tenant,
                        TransformationResource.resource_type == scoped_type,
                        TransformationResource.name == scoped_name,
                    )
                    .first()
                )
                if existing is not None:
                    raise RegistryError(
                        f"resource already exists: {scoped_type}/{scoped_name}"
                    )
                resource = TransformationResource(
                    tenant_id=scoped_tenant,
                    resource_type=scoped_type,
                    name=scoped_name,
                    description=str(description or ""),
                    tags=[str(tag) for tag in tags],
                    current_version=1,
                )
                session.add(resource)
                session.flush()
                version_row = TransformationResourceVersion(
                    resource_id=resource.id,
                    tenant_id=scoped_tenant,
                    version=1,
                    definition=definition_dict,
                    definition_hash=_definition_hash(definition_dict),
                )
                session.add(version_row)
                session.commit()
                return self._to_version(resource, version_row)
        except IntegrityError as exc:
            raise RegistryError(
                "concurrent resource creation detected; retry the request"
            ) from exc

    def save_version(
        self,
        *,
        tenant_id: str,
        name: str,
        definition: Mapping[str, Any],
        resource_type: Optional[str] = None,
        description: Optional[str] = None,
        tags: Optional[Sequence[str]] = None,
        validator: Optional[Validator] = None,
    ) -> ResourceVersion:
        scoped_tenant = self._check_tenant(tenant_id)
        scoped_name = self._check_name(name)
        if not isinstance(definition, Mapping):
            raise RegistryError("definition must be an object")
        if validator is not None:
            validator(definition)
        self.dependencies(definition)
        definition_dict = dict(definition)
        try:
            with self.session_factory() as session:
                resource = self._find_resource(
                    session, scoped_tenant, scoped_name, resource_type
                )
                next_version = resource.current_version + 1
                version_row = TransformationResourceVersion(
                    resource_id=resource.id,
                    tenant_id=scoped_tenant,
                    version=next_version,
                    definition=definition_dict,
                    definition_hash=_definition_hash(definition_dict),
                )
                session.add(version_row)
                resource.current_version = next_version
                if description is not None:
                    resource.description = str(description)
                if tags is not None:
                    resource.tags = [str(tag) for tag in tags]
                session.flush()
                session.commit()
                return self._to_version(resource, version_row)
        except IntegrityError as exc:
            raise RegistryError(
                "concurrent version write detected; retry the request"
            ) from exc

    def get(
        self,
        *,
        tenant_id: str,
        name: str,
        resource_type: Optional[str] = None,
        version: Optional[int] = None,
    ) -> ResourceVersion:
        scoped_tenant = self._check_tenant(tenant_id)
        scoped_name = self._check_name(name)
        with self.session_factory() as session:
            resource = self._find_resource(
                session, scoped_tenant, scoped_name, resource_type
            )
            target_version = resource.current_version if version is None else int(version)
            row = (
                session.query(TransformationResourceVersion)
                .filter(
                    TransformationResourceVersion.resource_id == resource.id,
                    TransformationResourceVersion.tenant_id == scoped_tenant,
                    TransformationResourceVersion.version == target_version,
                )
                .one_or_none()
            )
            if row is None:
                raise ResourceNotFound(
                    f"version {target_version} not found for {scoped_name}"
                )
            return self._to_version(resource, row)

    # --- search and discovery -----------------------------------------------

    def search(
        self,
        *,
        tenant_id: str,
        resource_type: Optional[str] = None,
        query: Optional[str] = None,
        tags: Sequence[str] = (),
        limit: int = 50,
    ) -> List[ResourceSummary]:
        """Tenant-scoped discovery by type, name/description text, and tags."""
        scoped_tenant = self._check_tenant(tenant_id)
        bounded_limit = max(1, min(int(limit or 50), 200))
        wanted_tags = {str(tag).strip().lower() for tag in tags if str(tag).strip()}
        with self.session_factory() as session:
            rows_query = session.query(TransformationResource).filter(
                TransformationResource.tenant_id == scoped_tenant
            )
            if resource_type is not None:
                rows_query = rows_query.filter(
                    TransformationResource.resource_type == self._check_type(resource_type)
                )
            pattern = f"%{str(query or '').strip()}%" if query and str(query).strip() else None
            if pattern is not None:
                rows_query = rows_query.filter(
                    TransformationResource.name.ilike(pattern)
                    | TransformationResource.description.ilike(pattern)
                )
            rows_query = rows_query.order_by(
                TransformationResource.resource_type.asc(),
                TransformationResource.name.asc(),
            )
            # Tag filtering runs in-process (portable across SQLite/PostgreSQL).
            overfetch = bounded_limit * 5 if wanted_tags else bounded_limit
            summaries: List[ResourceSummary] = []
            for row in rows_query.limit(overfetch).all():
                if wanted_tags and not wanted_tags.issubset(
                    {str(tag).strip().lower() for tag in (row.tags or [])}
                ):
                    continue
                summaries.append(self._to_summary(row))
                if len(summaries) >= bounded_limit:
                    break
            return summaries

    def history(
        self,
        *,
        tenant_id: str,
        name: str,
        resource_type: Optional[str] = None,
        limit: int = 20,
    ) -> List[ResourceVersionEntry]:
        """Version history (newest first) without full definitions."""
        scoped_tenant = self._check_tenant(tenant_id)
        bounded_limit = max(1, min(int(limit or 20), 100))
        with self.session_factory() as session:
            resource = self._find_resource(session, scoped_tenant, self._check_name(name), resource_type)
            rows = (
                session.query(TransformationResourceVersion)
                .filter(
                    TransformationResourceVersion.resource_id == resource.id,
                    TransformationResourceVersion.tenant_id == scoped_tenant,
                )
                .order_by(TransformationResourceVersion.version.desc())
                .limit(bounded_limit)
                .all()
            )
            return [
                ResourceVersionEntry(
                    version=row.version,
                    definition_hash=row.definition_hash,
                    created_at=row.created_at,
                )
                for row in rows
            ]

    # --- dependency management ----------------------------------------------

    def resolve_execution_order(
        self,
        *,
        tenant_id: str,
        root: str,
        resource_type: str = "dataset",
    ) -> List[str]:
        """Return root plus transitive dependencies in execution order.

        Uses depth-first topological ordering; dependency cycles raise
        :class:`DependencyCycleError` with the offending path, and missing
        dependencies raise :class:`ResourceNotFound`.
        """
        scoped_tenant = self._check_tenant(tenant_id)
        scoped_root = self._check_name(root)
        scoped_type = self._check_type(resource_type)
        state: Dict[str, int] = {}
        order: List[str] = []
        path: List[str] = []

        def visit(name: str) -> None:
            status = state.get(name, 0)
            if status == 2:
                return
            if status == 1:
                cycle = path[path.index(name):] + [name]
                raise DependencyCycleError(
                    "dependency cycle detected: " + " -> ".join(cycle)
                )
            state[name] = 1
            path.append(name)
            with self.session_factory() as session:
                resource = self._find_resource(session, scoped_tenant, name, scoped_type)
                row = (
                    session.query(TransformationResourceVersion)
                    .filter(
                        TransformationResourceVersion.resource_id == resource.id,
                        TransformationResourceVersion.tenant_id == scoped_tenant,
                        TransformationResourceVersion.version == resource.current_version,
                    )
                    .one()
                )
                dependencies = self.dependencies(row.definition)
            for dependency in dependencies:
                visit(dependency)
            path.pop()
            state[name] = 2
            order.append(name)

        visit(scoped_root)
        return order