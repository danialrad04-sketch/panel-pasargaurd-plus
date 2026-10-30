"""Persist native node install and activation jobs, with fenced target ownership."""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "e7b8c9d0e1f2"
down_revision = "d6a7b8c9d0e1"
branch_labels = None
depends_on = None


def upgrade():
    kind = sa.JSON().with_variant(JSONB(none_as_null=True), "postgresql")
    op.create_table(
        "native_provision_jobs",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actor", sa.String(64), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("target", sa.String(256), nullable=False),
        sa.Column("active_target", sa.String(256), unique=True),
        sa.Column("payload", kind, nullable=False),
        sa.Column("credential_cipher", sa.Text()),
        sa.Column("node_id", sa.BigInteger().with_variant(sa.Integer(), "sqlite"), sa.ForeignKey("nodes.id", ondelete="SET NULL")),
        sa.Column("state", sa.String(32), nullable=False),
        sa.Column("progress", sa.Integer(), nullable=False),
        sa.Column("result", kind, nullable=False),
        sa.Column("lease", sa.String(36)),
        sa.Column("lease_until", sa.DateTime(timezone=True)),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("cancel_requested", sa.Boolean(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade():
    if (
        op.get_bind()
        .execute(sa.text("SELECT COUNT(*) FROM native_provision_jobs WHERE active_target IS NOT NULL"))
        .scalar()
    ):
        raise RuntimeError("Finish or cancel native provisioning jobs before downgrading")
    op.drop_table("native_provision_jobs")
