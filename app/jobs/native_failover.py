"""Native node worker owns monitoring; database claims cover worker replicas."""

from app.maintenance.jobs import scheduled_backup
from app.scheduler import scheduler
from app.smartdns.service import maintain
from app.tunnel.failover import monitor
from app.tunnel.health import collect
from config import runtime_settings

if runtime_settings.role.runs_node:
    scheduler.add_job(
        monitor,
        "interval",
        seconds=60,
        coalesce=True,
        max_instances=1,
        id="native-tunnel-failover",
        replace_existing=True,
    )

if runtime_settings.role.runs_panel:
    scheduler.add_job(
        scheduled_backup,
        "interval",
        minutes=5,
        coalesce=True,
        max_instances=1,
        id="native-encrypted-backup",
        replace_existing=True,
    )

if runtime_settings.role.runs_node:
    scheduler.add_job(
        collect,
        "interval",
        seconds=60,
        coalesce=True,
        max_instances=1,
        id="native-carrier-health",
        replace_existing=True,
    )
if runtime_settings.role.runs_panel:
    scheduler.add_job(
        maintain,
        "interval",
        seconds=15,
        coalesce=True,
        max_instances=1,
        id="native-smartdns-grants",
        replace_existing=True,
    )
