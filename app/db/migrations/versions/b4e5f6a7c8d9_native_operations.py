"""Native carrier health, failover policy and SmartDNS relay accounting."""

import sqlalchemy as sa
from alembic import op
from app.db.compiles_types import SqliteCompatibleBigInteger as ID

revision = "b4e5f6a7c8d9"
down_revision = "a3d4e5f6b7c8"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("native_tunnels") as batch:
        batch.add_column(sa.Column("health_enabled", sa.Boolean(), nullable=False, server_default=sa.false()))
    with op.batch_alter_table("dns_accounts") as batch:
        for name, kind, default in [
            ("relay_enabled", sa.Boolean(), "0"),
            ("relay_byte_limit", sa.BigInteger(), "0"),
            ("relay_rps", sa.Integer(), "200"),
            ("relay_rate_second", sa.BigInteger(), "0"),
            ("relay_rate_count", sa.Integer(), "0"),
        ]:
            batch.add_column(
                sa.Column(
                    name,
                    kind,
                    nullable=False,
                    server_default=sa.false() if isinstance(kind, sa.Boolean) else sa.text(default),
                )
            )
    with op.batch_alter_table("dns_usage_periods") as batch:
        batch.add_column(sa.Column("relay_bytes", sa.BigInteger(), nullable=False, server_default="0"))
        batch.add_column(sa.Column("relay_reservations", sa.BigInteger(), nullable=False, server_default="0"))
        batch.add_column(sa.Column("relay_reserved", sa.BigInteger(), nullable=False, server_default="0"))
        batch.add_column(sa.Column("relay_uncertain_bytes", sa.BigInteger(), nullable=False, server_default="0"))
    op.create_table(
        "tunnel_health_state",
        sa.Column("tunnel_id", ID(), sa.ForeignKey("native_tunnels.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("checked_at", sa.DateTime(timezone=True)),
        sa.Column("lease", sa.String(36)),
        sa.Column("lease_until", sa.DateTime(timezone=True)),
        sa.Column("failures", sa.Integer(), nullable=False),
        sa.Column("successes", sa.Integer(), nullable=False),
        sa.Column("result", sa.JSON(), nullable=False),
    )
    op.create_table(
        "tunnel_failover_policies",
        sa.Column("source_node_id", ID(), sa.ForeignKey("nodes.id", ondelete="RESTRICT"), primary_key=True),
        sa.Column("candidates", sa.JSON(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False),
        sa.Column("suspended", sa.Boolean(), nullable=False),
        sa.Column("failure_threshold", sa.Integer(), nullable=False),
        sa.Column("cooldown_seconds", sa.Integer(), nullable=False),
        sa.Column("switched_at", sa.DateTime(timezone=True)),
        sa.Column("lease", sa.String(36)),
        sa.Column("lease_until", sa.DateTime(timezone=True)),
        sa.Column("last_result", sa.JSON(), nullable=False),
    )
    op.create_table(
        "smartdns_routes",
        sa.Column("id", ID(), primary_key=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("node_id", ID(), sa.ForeignKey("nodes.id", ondelete="RESTRICT"), nullable=False, index=True),
        sa.Column("policy_id", ID(), sa.ForeignKey("dns_policies.id", ondelete="RESTRICT"), nullable=False, index=True),
        sa.Column("domain", sa.String(253), nullable=False),
        sa.Column("public_address", sa.String(45), nullable=False),
        sa.Column("bind_address", sa.String(45), nullable=False),
        sa.Column("tcp_ports", sa.JSON(), nullable=False),
        sa.Column("udp_ports", sa.JSON(), nullable=False),
        sa.Column("egress_tunnel_id", ID(), sa.ForeignKey("native_tunnels.id", ondelete="RESTRICT"), nullable=True),
        sa.Column("mode", sa.String(16), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
    )
    op.create_table(
        "smartdns_node_state",
        sa.Column("node_id", ID(), sa.ForeignKey("nodes.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("applied_revision", sa.Integer(), nullable=False),
        sa.Column("lease", sa.String(36)),
        sa.Column("lease_until", sa.DateTime(timezone=True)),
        sa.Column("result", sa.JSON(), nullable=False),
    )
    op.create_table(
        "smartdns_grants",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("account_id", ID(), sa.ForeignKey("dns_accounts.id", ondelete="CASCADE"), nullable=False, index=True),
        sa.Column("period", sa.Integer(), nullable=False),
        sa.Column("node_id", sa.Integer(), nullable=False),
        sa.Column("route_id", sa.Integer(), nullable=False),
        sa.Column("fingerprint", sa.String(64), nullable=False),
        sa.Column("amount", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False, index=True),
        sa.Column("used", sa.Integer(), nullable=False),
        sa.Column("closed", sa.Boolean(), nullable=False),
        sa.Column("uncertain", sa.Boolean(), nullable=False),
    )

    op.create_table(
        "native_maintenance_jobs",
        sa.Column("name", sa.String(64), primary_key=True),
        sa.Column("lease", sa.String(36)),
        sa.Column("lease_until", sa.DateTime(timezone=True)),
        sa.Column("last_run", sa.DateTime(timezone=True)),
        sa.Column("last_result", sa.JSON(), nullable=False),
    )

    op.bulk_insert(
        sa.table("native_maintenance_jobs", sa.column("name", sa.String), sa.column("last_result", sa.JSON)),
        [{"name": "backup", "last_result": {}}],
    )


def downgrade():
    bind = op.get_bind()
    if (
        bind.execute(sa.text("SELECT COUNT(*) FROM smartdns_routes")).scalar()
        or bind.execute(sa.text("SELECT COUNT(*) FROM tunnel_failover_policies")).scalar()
    ):
        raise RuntimeError("Disable/apply/remove SmartDNS routes and remove failover policies before downgrade")
    if bind.execute(sa.text("SELECT COUNT(*) FROM dns_accounts WHERE relay_enabled")).scalar():
        raise RuntimeError("Disable SmartDNS accounts before downgrade")
    for name in (
        "native_maintenance_jobs",
        "smartdns_grants",
        "smartdns_node_state",
        "smartdns_routes",
        "tunnel_failover_policies",
        "tunnel_health_state",
    ):
        op.drop_table(name)
    with op.batch_alter_table("dns_usage_periods") as batch:
        batch.drop_column("relay_uncertain_bytes")
        batch.drop_column("relay_reserved")
        batch.drop_column("relay_reservations")
        batch.drop_column("relay_bytes")
    with op.batch_alter_table("dns_accounts") as batch:
        for name in ("relay_rate_count", "relay_rate_second", "relay_rps", "relay_byte_limit", "relay_enabled"):
            batch.drop_column(name)

    with op.batch_alter_table("native_tunnels") as batch:
        batch.drop_column("health_enabled")
