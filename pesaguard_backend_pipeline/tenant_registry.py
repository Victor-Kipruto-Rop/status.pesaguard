"""Least-privilege access to the cross-tenant registry for system workers."""

from __future__ import annotations

from functools import lru_cache
from threading import Lock

from sqlalchemy import text
from sqlalchemy.orm import sessionmaker

from environment import required_env

_tenant_cursor = 0
_tenant_cursor_lock = Lock()


@lru_cache(maxsize=1)
def _registry_session_factory(database_url: str):
    from database_service import create_database_engine

    engine = create_database_engine(database_url)
    if engine.dialect.name != "postgresql":
        raise RuntimeError("tenant registry access requires PostgreSQL")
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def list_active_tenant_ids() -> list[str]:
    """Return active tenant IDs using a role that cannot read business tables."""
    database_url = required_env("PESAGUARD_TENANT_REGISTRY_DATABASE_URL")
    engine, session_factory = _registry_session_factory(database_url)
    with engine.connect() as connection:
        role = connection.execute(
            text(
                """
                SELECT
                    r.rolsuper AS is_superuser,
                    r.rolbypassrls AS bypasses_rls,
                    has_column_privilege(current_user, 'public.iam_tenants', 'tenant_id', 'SELECT')
                        AS reads_tenant_id,
                    has_column_privilege(current_user, 'public.iam_tenants', 'status', 'SELECT')
                        AS reads_tenant_status,
                    has_table_privilege(current_user, 'public.iam_tenants', 'SELECT')
                    OR has_table_privilege(current_user, 'public.iam_tenants', 'INSERT')
                    OR has_table_privilege(current_user, 'public.iam_tenants', 'UPDATE')
                    OR has_table_privilege(current_user, 'public.iam_tenants', 'DELETE')
                    OR has_table_privilege(current_user, 'public.iam_tenants', 'TRUNCATE')
                    OR has_table_privilege(current_user, 'public.iam_tenants', 'REFERENCES')
                    OR has_table_privilege(current_user, 'public.iam_tenants', 'TRIGGER')
                    OR EXISTS (
                        SELECT 1
                        FROM pg_attribute AS a
                        WHERE a.attrelid = 'public.iam_tenants'::regclass
                          AND a.attnum > 0 AND NOT a.attisdropped
                          AND a.attname NOT IN ('tenant_id', 'status')
                          AND (
                              has_column_privilege(current_user, a.attrelid, a.attnum, 'SELECT')
                              OR has_column_privilege(current_user, a.attrelid, a.attnum, 'INSERT')
                              OR has_column_privilege(current_user, a.attrelid, a.attnum, 'UPDATE')
                          )
                    ) AS extra_tenant_registry_access,
                    EXISTS (
                        SELECT 1 FROM pg_roles AS inherited
                        WHERE inherited.oid <> r.oid
                          AND pg_has_role(current_user, inherited.oid, 'MEMBER')
                    ) AS inherits_any_role,
                    EXISTS (
                        SELECT 1
                        FROM pg_class AS rel
                        JOIN pg_namespace AS ns ON ns.oid = rel.relnamespace
                        WHERE rel.relowner = r.oid
                          AND ns.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND ns.nspname NOT LIKE 'pg_toast%'
                    ) AS owns_database_objects,
                    EXISTS (
                        SELECT 1
                        FROM pg_class AS rel
                        JOIN pg_namespace AS ns ON ns.oid = rel.relnamespace
                        WHERE ns.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND ns.nspname NOT LIKE 'pg_toast%'
                          AND (
                              (rel.relkind IN ('r', 'p', 'v', 'm')
                               AND rel.relname NOT IN ('iam_tenants', 'alembic_version')
                               AND (
                                   has_table_privilege(current_user, rel.oid, 'SELECT')
                                   OR has_table_privilege(current_user, rel.oid, 'INSERT')
                                   OR has_table_privilege(current_user, rel.oid, 'UPDATE')
                                   OR has_table_privilege(current_user, rel.oid, 'DELETE')
                                   OR has_table_privilege(current_user, rel.oid, 'TRUNCATE')
                                   OR has_table_privilege(current_user, rel.oid, 'REFERENCES')
                                   OR has_table_privilege(current_user, rel.oid, 'TRIGGER')
                                   OR EXISTS (
                                       SELECT 1 FROM pg_attribute AS column_privilege
                                       WHERE column_privilege.attrelid = rel.oid
                                         AND column_privilege.attnum > 0
                                         AND NOT column_privilege.attisdropped
                                         AND (
                                             has_column_privilege(current_user, rel.oid, column_privilege.attnum, 'SELECT')
                                             OR has_column_privilege(current_user, rel.oid, column_privilege.attnum, 'INSERT')
                                             OR has_column_privilege(current_user, rel.oid, column_privilege.attnum, 'UPDATE')
                                         )
                                   )
                               ))
                              OR (rel.relkind = 'S'
                                  AND (
                                      has_sequence_privilege(current_user, rel.oid, 'USAGE')
                                      OR has_sequence_privilege(current_user, rel.oid, 'SELECT')
                                      OR has_sequence_privilege(current_user, rel.oid, 'UPDATE')
                                  ))
                          )
                    ) AS has_business_relation_access,
                    EXISTS (
                        SELECT 1
                        FROM pg_namespace AS ns
                        WHERE ns.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND ns.nspname NOT LIKE 'pg_toast%'
                          AND has_schema_privilege(current_user, ns.oid, 'CREATE')
                    ) AS can_create_in_user_schema,
                    EXISTS (
                        SELECT 1
                        FROM pg_proc AS proc
                        JOIN pg_namespace AS ns ON ns.oid = proc.pronamespace
                        WHERE ns.nspname NOT IN ('pg_catalog', 'information_schema')
                          AND ns.nspname NOT LIKE 'pg_toast%'
                          AND proc.prosecdef
                          AND has_function_privilege(current_user, proc.oid, 'EXECUTE')
                    ) AS can_execute_security_definer
                FROM pg_roles AS r
                WHERE r.rolname = current_user
                """
            )
        ).one()
        if (
            role.is_superuser
            or role.bypasses_rls
            or not role.reads_tenant_id
            or not role.reads_tenant_status
            or role.extra_tenant_registry_access
            or role.inherits_any_role
            or role.owns_database_objects
            or role.has_business_relation_access
            or role.can_create_in_user_schema
            or role.can_execute_security_definer
        ):
            raise RuntimeError(
                "tenant registry database role must be non-superuser, "
                "non-BYPASSRLS, SELECT-only on iam_tenants.tenant_id/status"
            )

    from models import TenantRecord

    with session_factory() as session:
        rows = (
            session.query(TenantRecord.tenant_id)
            .filter(TenantRecord.status == "active")
            .order_by(TenantRecord.tenant_id)
            .all()
        )
        tenant_ids = [tenant_id for (tenant_id,) in rows]
    if not tenant_ids:
        return []
    global _tenant_cursor
    with _tenant_cursor_lock:
        start = _tenant_cursor % len(tenant_ids)
        _tenant_cursor = start + 1
    return tenant_ids[start:] + tenant_ids[:start]
