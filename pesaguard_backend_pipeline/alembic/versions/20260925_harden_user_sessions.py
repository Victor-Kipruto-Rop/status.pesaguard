"""Harden user sessions with lifecycle, activity, and expiry state."""

from alembic import op
import sqlalchemy as sa


revision = "20260925_harden_user_sessions"
down_revision = "20260925_create_user_sessions"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    inspector = sa.inspect(bind)
    columns = {column["name"] for column in inspector.get_columns("user_sessions")}
    constraints = {
        constraint["name"]
        for constraint in inspector.get_check_constraints("user_sessions")
    }
    additions = (
        sa.Column("organization_id", sa.String(), nullable=True),
        sa.Column("location_info", sa.JSON(), nullable=True),
        sa.Column("state", sa.String(length=16), nullable=False, server_default="ACTIVE"),
        sa.Column("last_activity_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("absolute_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("authentication_method", sa.String(length=64), nullable=True),
        sa.Column("mfa_verified", sa.Boolean(), nullable=False, server_default=sa.text("false")),
    )
    with op.batch_alter_table("user_sessions") as batch:
        for column in additions:
            if column.name not in columns:
                batch.add_column(column)
        if "ck_user_sessions_state" not in constraints:
            batch.create_check_constraint(
                "ck_user_sessions_state",
                "state IN ('ACTIVE', 'EXPIRED', 'REVOKED', 'SUSPENDED')",
            )
    if {"last_activity_at", "issued_at"} <= columns | {
        column.name for column in additions
    }:
        op.execute(
            sa.text(
                "UPDATE user_sessions "
                "SET last_activity_at = issued_at "
                "WHERE last_activity_at IS NULL"
            )
        )


def downgrade() -> None:
    inspector = sa.inspect(op.get_bind())
    columns = {column["name"] for column in inspector.get_columns("user_sessions")}
    constraints = {
        constraint["name"]
        for constraint in inspector.get_check_constraints("user_sessions")
    }
    removals = (
        "mfa_verified",
        "authentication_method",
        "absolute_expires_at",
        "expires_at",
        "last_activity_at",
        "state",
        "location_info",
        "organization_id",
    )
    with op.batch_alter_table("user_sessions") as batch:
        if "ck_user_sessions_state" in constraints:
            batch.drop_constraint("ck_user_sessions_state", type_="check")
        for column in removals:
            if column in columns:
                batch.drop_column(column)
