"""Native DNS accounts, policies, period ledgers, source leases and service audit.

Revision ID: d0a1e2f3b4c5
Revises: c8d2e4f6a1b3
"""

from alembic import op
import sqlalchemy as sa
from app.db.compiles_types import SqliteCompatibleBigInteger as ID

revision = "d0a1e2f3b4c5"
down_revision = "c8d2e4f6a1b3"
branch_labels = None
depends_on = None


def pk():
    return sa.Column("id", ID(), primary_key=True, autoincrement=True)


def created():
    return sa.Column("created_at", sa.DateTime(timezone=True), nullable=False)


def upgrade():
    op.create_table(
        "dns_policies",
        pk(),
        created(),
        sa.Column("name", sa.String(80), nullable=False, unique=True),
        sa.Column("rules", sa.JSON(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
    )
    op.create_table(
        "dns_accounts",
        pk(),
        created(),
        sa.Column("user_id", ID(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True),
        sa.Column("policy_id", ID(), sa.ForeignKey("dns_policies.id", ondelete="RESTRICT"), nullable=False),
        sa.Column("credential_seed", sa.String(64), nullable=False),
        sa.Column("token_hash", sa.String(64), unique=True),
        sa.Column("enabled", sa.Boolean(), nullable=False),
        sa.Column("query_limit", sa.BigInteger(), nullable=False),
        sa.Column("qps", sa.Integer(), nullable=False),
        sa.Column("period", sa.Integer(), nullable=False),
        sa.Column("rate_second", sa.BigInteger(), nullable=False),
        sa.Column("rate_count", sa.Integer(), nullable=False),
    )
    op.create_index("ix_dns_accounts_policy_id", "dns_accounts", ["policy_id"])
    op.create_table(
        "dns_usage_periods",
        pk(),
        created(),
        sa.Column("account_id", ID(), sa.ForeignKey("dns_accounts.id", ondelete="CASCADE"), nullable=False),
        sa.Column("period", sa.Integer(), nullable=False),
        *[
            sa.Column(name, sa.BigInteger(), nullable=False)
            for name in ("queries", "request_bytes", "response_bytes", "errors", "blocked")
        ],
        sa.Column("ended_at", sa.DateTime(timezone=True)),
        sa.UniqueConstraint("account_id", "period"),
    )
    op.create_table(
        "dns_ip_leases",
        sa.Column("ip", sa.String(45), primary_key=True),
        sa.Column("account_id", ID(), sa.ForeignKey("dns_accounts.id", ondelete="CASCADE"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ix_dns_ip_leases_account_id", "dns_ip_leases", ["account_id"])
    op.create_table(
        "service_audit",
        pk(),
        created(),
        sa.Column("actor", sa.String(64), nullable=False),
        sa.Column("action", sa.String(64), nullable=False),
        sa.Column("target_id", sa.String(64), nullable=False),
        sa.Column("details", sa.JSON(), nullable=False),
    )


def downgrade():
    for table in ("service_audit", "dns_ip_leases", "dns_usage_periods", "dns_accounts", "dns_policies"):
        op.drop_table(table)
