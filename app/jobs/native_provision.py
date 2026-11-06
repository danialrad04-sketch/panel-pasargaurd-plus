from app.provisioning.worker import drain
from app.scheduler import scheduler
from config import runtime_settings

if runtime_settings.role.runs_node:
    scheduler.add_job(
        drain,
        "interval",
        seconds=5,
        coalesce=True,
        max_instances=2,
        id="native-automatic-provisioning",
        replace_existing=True,
    )
