"""Bind subscription ingress hosts to native node tunnels."""

import sqlalchemy as sa
from alembic import op

from app.db.compiles_types import SqliteCompatibleBigInteger as ID

revision = "a3d4e5f6b7c8"
down_revision = "f2c3d4e5a6b7"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("hosts") as batch:
        batch.add_column(sa.Column("native_tunnel_id", ID(), nullable=True))
        batch.create_foreign_key(
            "fk_hosts_native_tunnel_id", "native_tunnels", ["native_tunnel_id"], ["id"], ondelete="RESTRICT"
        )
        batch.create_index("ix_hosts_native_tunnel_id", ["native_tunnel_id"])


def downgrade():
    if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM hosts WHERE native_tunnel_id IS NOT NULL")).scalar():
        raise RuntimeError("Remove tunnel subscription hosts before downgrading their binding")
    with op.batch_alter_table("hosts") as batch:
        batch.drop_index("ix_hosts_native_tunnel_id")
        batch.drop_constraint("fk_hosts_native_tunnel_id", type_="foreignkey")
        batch.drop_column("native_tunnel_id")
