"""Create tenant-managed OIDC provider configuration."""

from alembic import op
import sqlalchemy as sa


revision = "20261002_create_oidc_providers"
down_revision = "20261001_add_user_devices"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if "oidc_providers" in sa.inspect(op.get_bind()).get_table_names():
        return

    op.create_table(
        "oidc_providers",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("provider_name", sa.String(), nullable=False),
        sa.Column("issuer", sa.String(), nullable=False),
        sa.Column("client_id", sa.String(), nullable=True),
        sa.Column("client_secret", sa.String(), nullable=True),
        sa.Column("authorization_endpoint", sa.String(), nullable=True),
        sa.Column("token_endpoint", sa.String(), nullable=True),
        sa.Column("userinfo_endpoint", sa.String(), nullable=True),
        sa.Column("jwks_uri", sa.String(), nullable=True),
        sa.Column("scopes", sa.JSON(), nullable=False, server_default="[]"),
        sa.Column("allowed_roles", sa.JSON(), nullable=False, server_default="[]"),
        sa.Column("auto_provision", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("claim_mapping", sa.JSON(), nullable=True, server_default="{}"),
        sa.Column("provider_metadata", sa.JSON(), nullable=True),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_oidc_providers_tenant_id_nonempty",
        ),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade() -> None:
    """Retain provider configuration because this revision may adopt an existing table."""
