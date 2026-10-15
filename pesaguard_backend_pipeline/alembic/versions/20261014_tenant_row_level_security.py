"""Enable fail-closed tenant row policies for tenant-owned PostgreSQL tables."""

from alembic import op
import sqlalchemy as sa
import os


revision = "20261014_tenant_row_level_security"
down_revision = "20261014_settlement_integrity"
branch_labels = None
depends_on = None


# Tables without tenant_id are not selected by _tenant_tables. No tenant-
# bearing application table is exempted from RLS.
_AUTH_BOOTSTRAP_TABLES = frozenset()


def _tenant_tables(bind) -> list[str]:
    inspector = sa.inspect(bind)
    tables = []
    for table_name in inspector.get_table_names():
        if table_name in _AUTH_BOOTSTRAP_TABLES:
            continue
        columns = {column["name"] for column in inspector.get_columns(table_name)}
        if "tenant_id" in columns:
            tables.append(table_name)
    return sorted(tables)


def _add_communication_tenant_integrity(bind) -> None:
    inspector = sa.inspect(bind)
    tables = set(inspector.get_table_names())
    if "communication_notifications" not in tables:
        return

    if "communication_attempts" in tables:
        attempt_columns = {column["name"] for column in inspector.get_columns("communication_attempts")}
        if "tenant_id" not in attempt_columns:
            op.add_column(
                "communication_attempts",
                sa.Column("tenant_id", sa.String(length=128), nullable=True),
            )
            op.execute(
                "UPDATE communication_attempts AS attempt "
                "SET tenant_id = notification.tenant_id "
                "FROM communication_notifications AS notification "
                "WHERE notification.id = attempt.notification_id"
            )
            op.alter_column("communication_attempts", "tenant_id", nullable=False)

    unique_names = {
        constraint["name"]
        for constraint in inspector.get_unique_constraints("communication_notifications")
    }
    if "uq_communication_notification_id_tenant" not in unique_names:
        op.create_unique_constraint(
            "uq_communication_notification_id_tenant",
            "communication_notifications",
            ["id", "tenant_id"],
        )

    constraints_by_table = {
        table_name: {
            constraint["name"]
            for constraint in sa.inspect(bind).get_foreign_keys(table_name)
        }
        for table_name in ("communication_outbox_entries", "communication_attempts", "communication_delivery_reports")
        if table_name in tables
    }
    references = (
        ("communication_outbox_entries", "fk_communication_outbox_notification_tenant"),
        ("communication_attempts", "fk_communication_attempt_notification_tenant"),
        ("communication_delivery_reports", "fk_communication_delivery_notification_tenant"),
    )
    for table_name, constraint_name in references:
        if table_name not in constraints_by_table or constraint_name in constraints_by_table[table_name]:
            continue
        op.create_foreign_key(
            constraint_name,
            table_name,
            "communication_notifications",
            ["notification_id", "tenant_id"],
            ["id", "tenant_id"],
        )


def _require_role(bind, role_name: str, *, non_login: bool = False) -> None:
    role = bind.execute(
        sa.text(
            "SELECT rolsuper, rolbypassrls, rolcanlogin "
            "FROM pg_roles WHERE rolname = :role_name"
        ),
        {"role_name": role_name},
    ).one_or_none()
    if role is None or role[0] or role[1] or (non_login and role[2]):
        login_requirement = " NOLOGIN" if non_login else ""
        raise RuntimeError(
            f"Database role {role_name!r} must exist, be non-superuser, "
            f"non-BYPASSRLS, and{login_requirement}."
        )


def _require_narrow_api_key_lookup_privileges(bind, role_name: str) -> None:
    unsafe_privileges = bind.execute(
        sa.text(
            """
            SELECT EXISTS (
                SELECT 1
                FROM pg_class AS c
                WHERE c.oid = 'public.api_key_records'::regclass
                  AND (
                    c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :role_name)
                    OR has_table_privilege(:role_name, c.oid, 'SELECT')
                    OR has_table_privilege(:role_name, c.oid, 'INSERT')
                    OR has_table_privilege(:role_name, c.oid, 'UPDATE')
                    OR has_table_privilege(:role_name, c.oid, 'DELETE')
                    OR has_table_privilege(:role_name, c.oid, 'TRUNCATE')
                    OR has_table_privilege(:role_name, c.oid, 'REFERENCES')
                    OR has_table_privilege(:role_name, c.oid, 'TRIGGER')
                    OR EXISTS (
                        SELECT 1
                        FROM pg_attribute AS a
                        WHERE a.attrelid = c.oid
                          AND a.attnum > 0
                          AND NOT a.attisdropped
                          AND (
                            (
                              a.attname NOT IN (
                                'id', 'tenant_id', 'key_hash', 'key_prefix',
                                'scopes', 'api_metadata', 'expires_at',
                                'active', 'revoked_at'
                              )
                              AND has_column_privilege(
                                :role_name, a.attrelid, a.attnum, 'SELECT'
                              )
                            )
                            OR (
                              a.attname = 'last_used_at'
                              AND has_column_privilege(
                                :role_name, a.attrelid, a.attnum, 'SELECT'
                              )
                            )
                            OR (
                              a.attname <> 'last_used_at'
                              AND has_column_privilege(
                                :role_name, a.attrelid, a.attnum, 'UPDATE'
                              )
                            )
                            OR has_column_privilege(
                              :role_name, a.attrelid, a.attnum, 'INSERT'
                            )
                          )
                    )
                  )
            )
            """
        ),
        {"role_name": role_name},
    ).scalar_one()
    if unsafe_privileges:
        raise RuntimeError(
            "PESAGUARD_API_KEY_LOOKUP_DB_ROLE must not own api_key_records or "
            "have table-wide or unapproved column privileges"
        )


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        raise RuntimeError(
            "tenant row-level security migration requires PostgreSQL; "
            f"received {bind.dialect.name}"
        )

    preparer = bind.dialect.identifier_preparer
    _add_communication_tenant_integrity(bind)
    registry_role_name = os.getenv("PESAGUARD_TENANT_REGISTRY_DB_ROLE", "").strip()
    lookup_role_name = os.getenv("PESAGUARD_API_KEY_LOOKUP_DB_ROLE", "").strip()
    for table_name in _tenant_tables(bind):
        table = preparer.quote(table_name)
        policy = preparer.quote("pesaguard_tenant_isolation")
        restrictive_policy = preparer.quote("pesaguard_tenant_guard")
        tenant_predicate = (
            "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')"
        )
        trusted_roles = []
        if table_name == "iam_tenants" and registry_role_name:
            trusted_roles.append(registry_role_name)
        if table_name == "api_key_records" and lookup_role_name:
            trusted_roles.append(lookup_role_name)
        policy_exceptions = " OR ".join(
            "current_user = '" + role_name.replace("'", "''") + "'"
            for role_name in trusted_roles
        )
        restrictive_predicate = (
            f"({tenant_predicate}"
            + (f" OR {policy_exceptions}" if policy_exceptions else "")
            + ")"
        )
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
        op.execute(
            f"CREATE POLICY {policy} ON {table} "
            f"USING ({tenant_predicate}) WITH CHECK ({tenant_predicate})"
        )
        op.execute(
            f"CREATE POLICY {restrictive_policy} ON {table} AS RESTRICTIVE "
            f"USING ({restrictive_predicate}) WITH CHECK ({restrictive_predicate})"
        )

    if "iam_tenants" in sa.inspect(bind).get_table_names():
        worker_role = registry_role_name
        if not worker_role:
            raise RuntimeError(
                "PESAGUARD_TENANT_REGISTRY_DB_ROLE must name the least-privilege "
                "worker role allowed to enumerate tenant IDs"
            )
        _require_role(bind, worker_role)
        worker_has_memberships = bind.execute(
            sa.text(
                "SELECT EXISTS (SELECT 1 FROM pg_roles AS inherited "
                "WHERE inherited.oid <> (SELECT oid FROM pg_roles WHERE rolname = :role_name) "
                "AND pg_has_role(:role_name, inherited.oid, 'MEMBER'))"
            ),
            {"role_name": worker_role},
        ).scalar_one()
        worker_owns_tenant_table = bind.execute(
            sa.text(
                "SELECT c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :role_name) "
                "FROM pg_class AS c WHERE c.oid = 'public.iam_tenants'::regclass"
            ),
            {"role_name": worker_role},
        ).scalar_one()
        if worker_has_memberships or worker_owns_tenant_table:
            raise RuntimeError(
                "PESAGUARD_TENANT_REGISTRY_DB_ROLE must not inherit privileges "
                "from another role or own iam_tenants"
            )
        role = preparer.quote(worker_role)
        table = preparer.quote("iam_tenants")
        policy = preparer.quote("pesaguard_tenant_registry_worker")
        op.execute(f"GRANT USAGE ON SCHEMA public TO {role}")
        op.execute(f"GRANT SELECT (tenant_id, status) ON {table} TO {role}")
        op.execute(
            f"CREATE POLICY {policy} ON {table} TO {role} "
            "USING (true) WITH CHECK (false)"
        )

    tables = set(sa.inspect(bind).get_table_names())
    if "api_key_records" in tables:
        app_role_name = os.getenv("PESAGUARD_APPLICATION_DB_ROLE", "").strip()
        if not app_role_name or not lookup_role_name:
            raise RuntimeError(
                "PESAGUARD_APPLICATION_DB_ROLE and PESAGUARD_API_KEY_LOOKUP_DB_ROLE "
                "must name the application and non-login API-key lookup roles"
            )
        registry_role_name = os.getenv("PESAGUARD_TENANT_REGISTRY_DB_ROLE", "").strip()
        if lookup_role_name == app_role_name or lookup_role_name == registry_role_name:
            raise RuntimeError("API-key lookup role must be distinct from application and registry roles")
        _require_role(bind, app_role_name)
        _require_role(bind, lookup_role_name, non_login=True)
        _require_narrow_api_key_lookup_privileges(bind, lookup_role_name)
        inherited_lookup_role = bind.execute(
            sa.text("SELECT pg_has_role(:app_role, :lookup_role, 'MEMBER')"),
            {"app_role": app_role_name, "lookup_role": lookup_role_name},
        ).scalar_one()
        lookup_role_has_memberships = bind.execute(
            sa.text(
                "SELECT EXISTS (SELECT 1 FROM pg_roles AS inherited "
                "WHERE inherited.oid <> (SELECT oid FROM pg_roles WHERE rolname = :role_name) "
                "AND pg_has_role(:role_name, inherited.oid, 'MEMBER'))"
            ),
            {"role_name": lookup_role_name},
        ).scalar_one()
        app_inherits_bypass_role = bind.execute(
            sa.text(
                "SELECT EXISTS (SELECT 1 FROM pg_roles AS elevated "
                "WHERE (elevated.rolsuper OR elevated.rolbypassrls) "
                "AND pg_has_role(:app_role, elevated.oid, 'MEMBER'))"
            ),
            {"app_role": app_role_name},
        ).scalar_one()
        app_inherits_registry_role = bool(registry_role_name) and bind.execute(
            sa.text("SELECT pg_has_role(:app_role, :registry_role, 'MEMBER')"),
            {"app_role": app_role_name, "registry_role": registry_role_name},
        ).scalar_one()
        if (
            inherited_lookup_role
            or lookup_role_has_memberships
            or app_inherits_bypass_role
            or app_inherits_registry_role
        ):
            raise RuntimeError(
                "Application role must not inherit a superuser/BYPASSRLS role or "
                "the API-key lookup role, and the API-key lookup role must not "
                "inherit other roles"
            )
        app_role = preparer.quote(app_role_name)
        lookup_role = preparer.quote(lookup_role_name)
        table = preparer.quote("api_key_records")
        policy = preparer.quote("pesaguard_api_key_lookup")
        op.execute(f"GRANT USAGE ON SCHEMA public TO {lookup_role}")
        op.execute(
            "GRANT SELECT (id, tenant_id, key_hash, key_prefix, scopes, api_metadata, "
            "expires_at, active, revoked_at), UPDATE (last_used_at) "
            f"ON {table} TO {lookup_role}"
        )
        op.execute(
            f"CREATE POLICY {policy} ON {table} TO {lookup_role} "
            "USING (true) WITH CHECK (true)"
        )
        op.execute(
            "CREATE OR REPLACE FUNCTION public.pesaguard_authenticate_api_key(input_hash text) "
            "RETURNS TABLE (id varchar, tenant_id varchar, key_prefix varchar, scopes json, "
            "api_metadata json, expires_at timestamptz) "
            "LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public AS $$ "
            "UPDATE public.api_key_records AS api_key "
            "SET last_used_at = pg_catalog.now() "
            "WHERE api_key.key_hash = input_hash AND api_key.active "
            "AND api_key.revoked_at IS NULL "
            "AND (api_key.expires_at IS NULL OR api_key.expires_at > pg_catalog.now()) "
            "RETURNING api_key.id, api_key.tenant_id, api_key.key_prefix, api_key.scopes, "
            "api_key.api_metadata, api_key.expires_at $$"
        )
        op.execute(
            "ALTER FUNCTION public.pesaguard_authenticate_api_key(text) "
            f"OWNER TO {lookup_role}"
        )
        op.execute(
            "REVOKE ALL ON FUNCTION public.pesaguard_authenticate_api_key(text) FROM PUBLIC"
        )
        op.execute(
            "GRANT EXECUTE ON FUNCTION public.pesaguard_authenticate_api_key(text) "
            f"TO {app_role}"
        )

    if {
        "api_key_lifecycle_idempotency_records",
        "api_key_records",
    }.issubset(tables):
        table = preparer.quote("api_key_lifecycle_idempotency_records")
        policy = preparer.quote("pesaguard_api_key_idempotency_tenant")
        tenant_predicate = (
            "EXISTS (SELECT 1 FROM api_key_records AS api_key "
            "WHERE api_key.id = api_key_lifecycle_idempotency_records.key_id "
            "AND api_key.tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))"
        )
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
        op.execute(
            f"CREATE POLICY {policy} ON {table} "
            f"USING ({tenant_predicate}) WITH CHECK ({tenant_predicate})"
        )


def downgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        raise RuntimeError(
            "tenant row-level security migration requires PostgreSQL; "
            f"received {bind.dialect.name}"
        )

    preparer = bind.dialect.identifier_preparer
    tables = set(sa.inspect(bind).get_table_names())
    for table_name in _tenant_tables(bind):
        table = preparer.quote(table_name)
        policy = preparer.quote("pesaguard_tenant_isolation")
        restrictive_policy = preparer.quote("pesaguard_tenant_guard")
        op.execute(f"DROP POLICY IF EXISTS {policy} ON {table}")
        op.execute(f"DROP POLICY IF EXISTS {restrictive_policy} ON {table}")
        op.execute(f"ALTER TABLE {table} NO FORCE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} DISABLE ROW LEVEL SECURITY")
    if "iam_tenants" in sa.inspect(bind).get_table_names():
        table = preparer.quote("iam_tenants")
        policy = preparer.quote("pesaguard_tenant_registry_worker")
        op.execute(f"DROP POLICY IF EXISTS {policy} ON {table}")
        worker_role = os.getenv("PESAGUARD_TENANT_REGISTRY_DB_ROLE", "").strip()
        if worker_role:
            # The table and role values are quoted as SQL identifiers by the dialect preparer.
            op.execute(
                f"REVOKE SELECT (tenant_id, status) ON {table} "  # nosec B608
                f"FROM {preparer.quote(worker_role)}"
            )
            op.execute(
                f"REVOKE USAGE ON SCHEMA public FROM {preparer.quote(worker_role)}"
            )
    if "api_key_lifecycle_idempotency_records" in tables:
        table = preparer.quote("api_key_lifecycle_idempotency_records")
        policy = preparer.quote("pesaguard_api_key_idempotency_tenant")
        op.execute(f"DROP POLICY IF EXISTS {policy} ON {table}")
        op.execute(f"ALTER TABLE {table} NO FORCE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} DISABLE ROW LEVEL SECURITY")
    if "api_key_records" in tables:
        lookup_role = os.getenv("PESAGUARD_API_KEY_LOOKUP_DB_ROLE", "").strip()
        table = preparer.quote("api_key_records")
        op.execute("DROP FUNCTION IF EXISTS public.pesaguard_authenticate_api_key(text)")
        op.execute(f"DROP POLICY IF EXISTS {preparer.quote('pesaguard_api_key_lookup')} ON {table}")
        if lookup_role:
            # The table and role values are quoted as SQL identifiers by the dialect preparer.
            op.execute(
                "REVOKE SELECT (id, tenant_id, key_hash, key_prefix, scopes, api_metadata, "  # nosec B608
                "expires_at, active, revoked_at), UPDATE (last_used_at) "
                f"ON {table} FROM {preparer.quote(lookup_role)}"
            )
            op.execute(
                f"REVOKE USAGE ON SCHEMA public FROM {preparer.quote(lookup_role)}"
            )
    communication_constraints = (
        ("communication_outbox_entries", "fk_communication_outbox_notification_tenant"),
        ("communication_attempts", "fk_communication_attempt_notification_tenant"),
        ("communication_delivery_reports", "fk_communication_delivery_notification_tenant"),
    )
    for table_name, constraint_name in communication_constraints:
        if table_name in tables and any(
            foreign_key["name"] == constraint_name
            for foreign_key in sa.inspect(bind).get_foreign_keys(table_name)
        ):
            op.drop_constraint(constraint_name, table_name, type_="foreignkey")
    if "communication_notifications" in tables:
        if any(
            unique["name"] == "uq_communication_notification_id_tenant"
            for unique in sa.inspect(bind).get_unique_constraints("communication_notifications")
        ):
            op.drop_constraint(
                "uq_communication_notification_id_tenant",
                "communication_notifications",
                type_="unique",
            )
    if "communication_attempts" in tables and "tenant_id" in {
        column["name"] for column in sa.inspect(bind).get_columns("communication_attempts")
    }:
        op.drop_column("communication_attempts", "tenant_id")
