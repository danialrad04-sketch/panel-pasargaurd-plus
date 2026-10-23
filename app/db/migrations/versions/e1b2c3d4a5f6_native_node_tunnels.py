"""Native encrypted node tunnels and serialized deployment lease.

Revision ID: e1b2c3d4a5f6
Revises: d0a1e2f3b4c5
"""

import sqlalchemy as sa
from alembic import op

from app.db.compiles_types import SqliteCompatibleBigInteger as ID

revision = "e1b2c3d4a5f6"
down_revision = "d0a1e2f3b4c5"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "native_tunnels",
        sa.Column("id", ID(), primary_key=True, autoincrement=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("name", sa.String(80), nullable=False, unique=True),
        sa.Column("source_node_id", ID(), sa.ForeignKey("nodes.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("exit_node_id", ID(), sa.ForeignKey("nodes.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("shared_key", sa.String(44), nullable=False),
        sa.Column("inbound_tags", sa.JSON(), nullable=False),
        sa.Column("domains", sa.JSON(), nullable=False),
        sa.Column("port", sa.Integer(), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("applied_revision", sa.Integer(), nullable=False),
    )
    for column in ("source_node_id", "exit_node_id"):
        op.create_index(f"ix_native_tunnels_{column}", "native_tunnels", [column])
    table = op.create_table(
        "tunnel_deployment",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("lease", sa.String(36)),
        sa.Column("lease_until", sa.DateTime(timezone=True)),
        sa.Column("last_result", sa.JSON(), nullable=False),
    )
    op.bulk_insert(table, [{"id": 1, "last_result": {}}])


def downgrade():
    op.drop_table("tunnel_deployment")
    op.drop_table("native_tunnels")
