"""Paqet engine configuration and authenticated native node extension port."""

from uuid import uuid4

import sqlalchemy as sa
from alembic import op

revision = "f2c3d4e5a6b7"
down_revision = "e1b2c3d4a5f6"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("nodes", sa.Column("tunnel_api_port", sa.Integer(), nullable=False, server_default="62052"))
    op.add_column(
        "native_tunnels", sa.Column("transport", sa.String(32), nullable=False, server_default="shadowsocks-2022")
    )
    op.add_column("native_tunnels", sa.Column("paqet_options", sa.JSON(), nullable=False, server_default="{}"))
    op.add_column("native_tunnels", sa.Column("paqet_key", sa.String(512)))
    op.add_column("native_tunnels", sa.Column("engine_id", sa.String(32)))
    bind = op.get_bind()
    for row in bind.execute(sa.text("SELECT id FROM native_tunnels")).all():
        bind.execute(
            sa.text("UPDATE native_tunnels SET engine_id=:engine_id WHERE id=:id"),
            {"engine_id": uuid4().hex, "id": row[0]},
        )
    with op.batch_alter_table("native_tunnels") as batch:
        batch.alter_column("engine_id", existing_type=sa.String(32), nullable=False)
        batch.create_unique_constraint("uq_native_tunnels_engine_id", ["engine_id"])
        batch.alter_column("paqet_options", server_default=None)


def downgrade():
    active = op.get_bind().execute(sa.text("SELECT COUNT(*) FROM native_tunnels WHERE transport = 'paqet'")).scalar()
    if active:
        raise RuntimeError("Disable, apply and remove Paqet tunnels before downgrading this migration")
    with op.batch_alter_table("native_tunnels") as batch:
        batch.drop_constraint("uq_native_tunnels_engine_id", type_="unique")
        for name in ("engine_id", "paqet_key", "paqet_options", "transport"):
            batch.drop_column(name)
    with op.batch_alter_table("nodes") as batch:
        batch.drop_column("tunnel_api_port")
