from __future__ import annotations

import asyncio

import pytest

from pc_controller.src.network.time_server import TimeSyncServer


@pytest.mark.timeout(5)
def test_time_sync_server_echoes_monotonic_ns():
    class ClientProtocol(asyncio.DatagramProtocol):
        def __init__(self, addr):
            self.addr = addr
            self.transport = None
            self.future = asyncio.get_running_loop().create_future()

        def connection_made(self, transport):
            self.transport = transport
            transport.sendto(b"x", self.addr)

        def datagram_received(self, data, addr):
            try:
                self.future.set_result(int(data.decode("ascii")))
            finally:
                if self.transport:
                    self.transport.close()

    async def _run():
        server = TimeSyncServer(host="127.0.0.1", port=0)
        await server.start()
        try:
            loop = asyncio.get_running_loop()
            transport, protocol = await loop.create_datagram_endpoint(
                lambda: ClientProtocol((server.host, server.port)),
                local_addr=("127.0.0.1", 0),
            )
            try:
                reply = await asyncio.wait_for(protocol.future, timeout=2.0)
                assert isinstance(reply, int) and reply > 0
            finally:
                transport.close()
        finally:
            await server.stop()

    asyncio.run(_run())
