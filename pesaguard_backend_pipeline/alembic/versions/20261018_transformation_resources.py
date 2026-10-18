"""Add version-controlled transformation resource registry tables."""

from alembic import op
import sqlalchemy as sa


revision = "20261018_transformation_resources"
down_revision = "20261018_complete_tenant_rls"
branch_labels = None
depends_on = None


def upgrade() -> None:
    tables = set(sa.inspect(op.get_bind()).get_table_names())
    if "transformation_resources" not in tables:
        op.create_table(
            "transformation_resources",
            sa.Column("id", sa.String(), primary_key=True),
            sa.Column("tenant_id", sa.String(), nullable=False),
            sa.Column("resource_type", sa.String(length=32), nullable=False),
            sa.Column("name", sa.String(length=128), nullable=False),
            sa.Column("description", sa.Text(), nullable=True),
            sa.Column("tags", sa.JSON(), nullable=False, server_default=sa.text("'[]'")),
            sa.Column("current_version", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
            sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
            sa.UniqueConstraint("tenant_id", "resource_type", "name", name="uq_transformation_resource_tenant_type_name"),
            sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_transformation_resources_tenant_id_nonempty"),
            sa.CheckConstraint("name IS NOT NULL AND name <> ''", name="ck_transformation_resources_name_nonempty"),
            sa.CheckConstraint("resource_type IN ('dataset', 'component', 'workflow')", name="ck_transformation_resources_type"),
            sa.CheckConstraint("current_version >= 1", name="ck_transformation_resources_version_positive"),
        )
        op.create_index(
            "ix_transformation_resources_tenant_type",
            "transformation_resources",
            ["tenant_id", "resource_type", "name"],
        )
    if "transformation_resource_versions" not in tables:
        op.create_table(
            "transformation_resource_versions",
            sa.Column("id", sa.String(), primary_key=True),
            sa.Column("resource_id", sa.String(), nullable=False),
            sa.Column("tenant_id", sa.String(), nullable=False),
            sa.Column("version", sa.Integer(), nullable=False),
            sa.Column("definition", sa.JSON(), nullable=False),
            sa.Column("definition_hash", sa.String(length=64), nullable=False),
            sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
            sa.ForeignKeyConstraint(
                ["resource_id"],
                ["transformation_resources.id"],
                name="fk_transformation_versions_resource",
            ),
            sa.UniqueConstraint("resource_id", "version", name="uq_transformation_resource_version"),
            sa.CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_transformation_versions_tenant_id_nonempty"),
            sa.CheckConstraint("version >= 1", name="ck_transformation_versions_version_positive"),
        )
        op.create_index(
            "ix_transformation_versions_tenant_resource",
            "transformation_resource_versions",
            ["tenant_id", "resource_id", "version"],
        )


def downgrade() -> None:
    # Resource definitions are operational configuration history. Keep the
    # tables on application rollback; a later retention migration can remove
    # them safely.
    pass