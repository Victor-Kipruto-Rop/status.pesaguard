"""Create the base authenticated-session table before hardening it."""

from alembic import op
import sqlalchemy as sa


revision = "20260925_create_user_sessions"
down_revision = "20260925_add_password_policies"
branch_labels = None
depends_on = None


def upgrade() -> None:
    if "user_sessions" in sa.inspect(op.get_bind()).get_table_names():
        return

    op.create_table(
        "user_sessions",
        sa.Column("id", sa.String(), nullable=False),
        sa.Column("tenant_id", sa.String(), nullable=False),
        sa.Column("user_id", sa.String(), nullable=True),
        sa.Column("device_id", sa.String(), nullable=True),
        sa.Column("user_agent", sa.String(), nullable=True),
        sa.Column("ip_address", sa.String(), nullable=True),
        sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column(
            "issued_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("session_metadata", sa.JSON(), nullable=True, server_default="{}"),
        sa.CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_user_sessions_tenant_id_nonempty",
        ),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade() -> None:
    """Keep the table because it may have existed before this migration."""
    pass
