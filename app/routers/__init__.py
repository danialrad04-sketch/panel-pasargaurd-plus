from fastapi import APIRouter

from . import (
    admin,
    admin_role,
    api_key,
    client_template,
    core,
    dns,
    group,
    home,
    host,
    hwid,
    maintenance,
    node,
    passkey,
    provision,
    settings,
    setup,
    smartdns,
    subscription,
    system,
    tunnel,
    user,
    user_template,
)

api_router = APIRouter()

routers = [
    home.router,
    maintenance.router,
    admin.router,
    api_key.router,
    admin_role.router,
    setup.router,
    system.router,
    dns.router,
    smartdns.router,
    tunnel.router,
    provision.router,
    settings.router,
    group.router,
    core.router,
    client_template.router,
    host.router,
    node.router,
    user.router,
    subscription.router,
    user_template.router,
    hwid.router,
    passkey.router,
]

for router in routers:
    api_router.include_router(router)

__all__ = ["api_router"]
