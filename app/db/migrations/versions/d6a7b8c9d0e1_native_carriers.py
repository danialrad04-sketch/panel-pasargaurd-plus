"""Add pinned native Backhaul, GOST and FRP carrier settings."""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "d6a7b8c9d0e1"
down_revision = "c5f6a7b8d9e0"
branch_labels = None
depends_on = None


def upgrade():
    kind = sa.JSON().with_variant(JSONB(none_as_null=True), "postgresql")
    with op.batch_alter_table("native_tunnels") as batch:
        batch.add_column(sa.Column("carrier_options", kind, nullable=True))
        batch.add_column(sa.Column("carrier_key", sa.String(512), nullable=True))
    op.get_bind().execute(
        sa.table("native_tunnels", sa.column("carrier_options", kind)).update().values(carrier_options={})
    )
    with op.batch_alter_table("native_tunnels") as batch:
        batch.alter_column("carrier_options", existing_type=kind, nullable=False)


def downgrade():
    count = (
        op.get_bind()
        .execute(sa.text("SELECT COUNT(*) FROM native_tunnels WHERE transport IN ('backhaul','gost','frp')"))
        .scalar()
    )
    if count:
        raise RuntimeError("Disable, apply and remove new carrier links before downgrading")
    with op.batch_alter_table("native_tunnels") as batch:
        batch.drop_column("carrier_options")
        batch.drop_column("carrier_key")
