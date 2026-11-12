"""reserve ports of tunnels discovered on servers the panel does not manage

Revision ID: d6e7f8a9b0c1
Revises: c5d6e7f8a9b0
Create Date: 2026-11-11 22:00:00

"""

import sqlalchemy as sa
from alembic import op

from app.db.compiles_types import SqliteCompatibleBigInteger as ID

revision = "d6e7f8a9b0c1"
down_revision = "c5d6e7f8a9b0"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "native_external_tunnels",
        sa.Column("id", ID(), primary_key=True, autoincrement=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("address", sa.String(256), nullable=False),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("kind", sa.String(32), nullable=False),
        sa.Column("ports", sa.JSON(), nullable=False),
        sa.Column("role", sa.String(16), nullable=True),
        sa.Column("peer", sa.String(300), nullable=True),
        sa.Column("summary", sa.JSON(), nullable=False),
        sa.Column("created_by", sa.String(64), nullable=True),
        sa.UniqueConstraint("address", "name"),
    )
    op.create_index("ix_native_external_tunnels_address", "native_external_tunnels", ["address"])


def downgrade() -> None:
    op.drop_index("ix_native_external_tunnels_address", table_name="native_external_tunnels")
    op.drop_table("native_external_tunnels")
