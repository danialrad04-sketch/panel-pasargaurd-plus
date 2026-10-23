"""Optional native UDP/TCP/DoT worker: python -m app.dns.worker.

Shares PasarGuard's database, accounts, policies and quota ledger. No external
panel or controller, shell execution, deployment API or separate database.
"""

import asyncio
import signal
import ssl

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError

from app.db.models import DNSPolicy
from app.dns.service import DNSService, source_address
from app.utils.logger import get_logger
from config import dns_settings

logger = get_logger("dns-worker")
MAX_PENDING = 256


class UDPServer(asyncio.DatagramProtocol):
    def __init__(self, service):
        self.service = service
        self.transport = None
        self.tasks = set()

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        if not 12 <= len(data) <= 4096 or len(self.tasks) >= MAX_PENDING:
            return
        task = asyncio.create_task(self.answer(data, address))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def answer(self, data, address):
        try:
            result = await self.service.answer(data, source_ip=source_address(address[0]), udp=True)
            self.transport.sendto(result, address)
        except HTTPException, ValueError:
            # Unauthorized clients receive no UDP response.
            pass
        except Exception:
            logger.warning("DNS UDP request failed; no query contents were logged")


class TCPServer:
    def __init__(self, service):
        self.service, self.active = service, 0

    async def handle(self, reader, writer):
        if self.active >= MAX_PENDING:
            writer.close()
            return
        self.active += 1
        try:
            address = source_address(writer.get_extra_info("peername")[0])
            for _ in range(16):
                async with asyncio.timeout(8):
                    header = await reader.readexactly(2)
                    size = int.from_bytes(header, "big")
                    if not 12 <= size <= 4096:
                        return
                    data = await reader.readexactly(size)
                    result = await self.service.answer(data, source_ip=address)
                    writer.write(len(result).to_bytes(2, "big") + result)
                    await writer.drain()
        except HTTPException, ValueError, TimeoutError, asyncio.IncompleteReadError, ConnectionError:
            pass
        except Exception:
            logger.warning("DNS TCP request failed; no query contents were logged")
        finally:
            self.active -= 1
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), timeout=1)
            except OSError, TimeoutError:
                pass


async def serve():
    if not dns_settings.enabled:
        raise SystemExit("Enable DNS_ENABLED before starting the native DNS worker")
    context = None
    if dns_settings.dot_port is not None:
        if not dns_settings.certfile or not dns_settings.keyfile:
            raise SystemExit("DoT requires DNS_TLS_CERTFILE and DNS_TLS_KEYFILE")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(dns_settings.certfile, dns_settings.keyfile)
    service = DNSService()
    try:
        async with service.sessions() as db:
            await db.execute(select(DNSPolicy.id).limit(1))
    except SQLAlchemyError:
        raise SystemExit(
            "Native DNS schema unavailable; verify the panel database and run alembic upgrade head"
        ) from None
    handler = TCPServer(service)
    servers, datagrams, protocols = [], [], []
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    for signum in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(signum, stop.set)
    try:
        for address in dns_settings.listen_addresses:
            transport, protocol = await loop.create_datagram_endpoint(
                lambda: UDPServer(service), local_addr=(address, dns_settings.port)
            )
            datagrams.append(transport)
            protocols.append(protocol)
            servers.append(await asyncio.start_server(handler.handle, address, dns_settings.port, limit=8192))
            if context:
                servers.append(
                    await asyncio.start_server(
                        handler.handle, address, dns_settings.dot_port, ssl=context, ssl_handshake_timeout=5, limit=8192
                    )
                )
        logger.info("Native DNS worker listening; authorization uses PasarGuard source-IP leases")
        await stop.wait()
    finally:
        for server in servers:
            server.close()
            await server.wait_closed()
        for transport in datagrams:
            transport.close()
        tasks = [task for protocol in protocols for task in protocol.tasks]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)


if __name__ == "__main__":
    asyncio.run(serve())
