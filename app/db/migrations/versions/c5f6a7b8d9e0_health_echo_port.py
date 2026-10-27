"""Separate internal health listener from NAT-forwarded node management ports."""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "c5f6a7b8d9e0"
down_revision = "b4e5f6a7c8d9"
branch_labels = None
depends_on = None
JSON_COLUMNS = {
    "tunnel_health_state": ("result",),
    "tunnel_failover_policies": ("candidates", "last_result"),
    "smartdns_routes": ("tcp_ports", "udp_ports"),
    "smartdns_node_state": ("result",),
    "native_maintenance_jobs": ("last_result",),
}


def upgrade():

    column_type = sa.JSON().with_variant(JSONB(none_as_null=True), "postgresql")
    with op.batch_alter_table("smartdns_node_state") as batch:
        batch.add_column(sa.Column("dns_config", column_type, nullable=True))
        batch.add_column(sa.Column("runtime_result", column_type, nullable=True))
        batch.add_column(sa.Column("checked_at", sa.DateTime(timezone=True), nullable=True))
    op.get_bind().execute(
        sa.table("smartdns_node_state", sa.column("dns_config", column_type)).update().values(dns_config={})
    )
    op.get_bind().execute(
        sa.table("smartdns_node_state", sa.column("runtime_result", column_type)).update().values(runtime_result={})
    )
    with op.batch_alter_table("smartdns_node_state") as batch:
        batch.alter_column("dns_config", existing_type=column_type, nullable=False)
        batch.alter_column("runtime_result", existing_type=column_type, nullable=False)
    with op.batch_alter_table("nodes") as batch:
        batch.add_column(sa.Column("health_echo_port", sa.Integer(), nullable=False, server_default="62053"))
    op.get_bind().execute(sa.text("UPDATE smartdns_node_state SET revision=revision+1, applied_revision=0"))
    op.get_bind().execute(
        sa.text("UPDATE native_tunnels SET revision=revision+1, applied_revision=0 WHERE health_enabled")
    )
    op.get_bind().execute(sa.text("UPDATE tunnel_failover_policies SET suspended=true WHERE enabled"))
    if op.get_bind().dialect.name == "postgresql":
        for table, columns in JSON_COLUMNS.items():
            for column in columns:
                op.alter_column(table, column, type_=JSONB(none_as_null=True), postgresql_using=f"{column}::jsonb")


def downgrade():
    import json

    for row in op.get_bind().execute(sa.text("SELECT dns_config,revision,applied_revision FROM smartdns_node_state")):
        value = json.loads(row[0]) if isinstance(row[0], str) else row[0]
        if value and (value.get("enabled") or row[1] != row[2]):
            raise RuntimeError("Disable and apply node DNS listeners before dropping their configuration")
    if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM native_tunnels WHERE health_enabled")).scalar():
        raise RuntimeError("Disable and apply carrier monitoring before dropping its internal port metadata")
    with op.batch_alter_table("smartdns_node_state") as batch:
        batch.drop_column("dns_config")
        batch.drop_column("runtime_result")
        batch.drop_column("checked_at")
    if op.get_bind().dialect.name == "postgresql":
        for table, columns in JSON_COLUMNS.items():
            for column in columns:
                op.alter_column(table, column, type_=sa.JSON(), postgresql_using=f"{column}::json")
    with op.batch_alter_table("nodes") as batch:
        batch.drop_column("health_echo_port")
