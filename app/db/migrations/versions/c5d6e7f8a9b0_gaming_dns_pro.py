"""gaming DNS: per-account speed limit and per-service daily usage

Revision ID: c5d6e7f8a9b0
Revises: 4841226542d5
Create Date: 2026-11-10 17:30:00

"""

import sqlalchemy as sa
from alembic import op
from app.db.compiles_types import SqliteCompatibleBigInteger as ID

revision = "c5d6e7f8a9b0"
down_revision = "4841226542d5"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("dns_accounts") as batch:
        batch.add_column(sa.Column("relay_rate_bps", sa.BigInteger(), nullable=False, server_default=sa.text("0")))
    op.create_table(
        "smartdns_service_usage",
        sa.Column("id", ID(), primary_key=True, autoincrement=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("account_id", ID(), sa.ForeignKey("dns_accounts.id", ondelete="CASCADE"), nullable=False),
        sa.Column("day", sa.String(10), nullable=False),
        sa.Column("domain", sa.String(253), nullable=False),
        sa.Column("bytes", sa.BigInteger(), nullable=False, server_default=sa.text("0")),
        sa.UniqueConstraint("account_id", "day", "domain"),
    )
    op.create_index("ix_smartdns_service_usage_account_id", "smartdns_service_usage", ["account_id"])


def downgrade() -> None:
    op.drop_index("ix_smartdns_service_usage_account_id", table_name="smartdns_service_usage")
    op.drop_table("smartdns_service_usage")
    with op.batch_alter_table("dns_accounts") as batch:
        batch.drop_column("relay_rate_bps")
