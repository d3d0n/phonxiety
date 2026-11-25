from __future__ import annotations

import asyncio
import json
import unittest

from phonxiety.net.framing import HEADER_BYTES, parse_header
from phonxiety.net.tunnel import TunnelListener, open_stream


class _Send:
    def __init__(self) -> None:
        self.written = bytearray()
        self.finished = False

    async def write_all(self, data: bytes) -> None:
        self.written += data

    async def finish(self) -> None:
        self.finished = True


class _Recv:
    def __init__(self) -> None:
        self.chunks: asyncio.Queue[bytes] = asyncio.Queue()

    async def read(self, size: int) -> bytes:
        return await self.chunks.get()  # b"" ends the stream


class _Phone:
    """Answers each tunnel with an adbd-like echo of what it received, prefixed."""

    def __init__(self, available: bool = True) -> None:
        self.available = available
        self.opened: list[tuple[_Send, _Recv]] = []

    async def open_tunnel(self, service: str) -> tuple[_Send, _Recv]:
        if not self.available:
            raise RuntimeError("tunnel_unsupported_by_phone")
        pair = (_Send(), _Recv())
        self.opened.append(pair)
        return pair


class TunnelListenerTests(unittest.IsolatedAsyncioTestCase):
    async def test_bytes_cross_both_ways_and_the_phone_ending_closes_the_client(self) -> None:
        phone = _Phone()
        listener = TunnelListener(phone, "adb")
        await listener.start("127.0.0.1", 0)
        reader, writer = await asyncio.open_connection("127.0.0.1", listener.port)
        writer.write(b"CNXN host::")
        await writer.drain()
        async with asyncio.timeout(2):
            while not phone.opened or bytes(phone.opened[0][0].written) != b"CNXN host::":
                await asyncio.sleep(0.01)
        send, recv = phone.opened[0]
        await recv.chunks.put(b"CNXN device::")
        async with asyncio.timeout(2):
            self.assertEqual(await reader.readexactly(len(b"CNXN device::")), b"CNXN device::")
        await recv.chunks.put(b"")  # the phone's adbd hung up
        async with asyncio.timeout(2):
            self.assertEqual(await reader.read(), b"")
        writer.close()
        await listener.close()

    async def test_a_client_is_turned_away_when_the_phone_offers_no_tunnel(self) -> None:
        listener = TunnelListener(_Phone(available=False), "adb")
        await listener.start("127.0.0.1", 0)
        reader, writer = await asyncio.open_connection("127.0.0.1", listener.port)
        async with asyncio.timeout(2):
            self.assertEqual(await reader.read(), b"")
        writer.close()
        await listener.close()


class OpenStreamTests(unittest.IsolatedAsyncioTestCase):
    async def test_a_tunnel_stream_starts_with_its_service_header(self) -> None:
        send, recv = _Send(), _Recv()

        class _Stream:
            def send(self) -> _Send:
                return send

            def recv(self) -> _Recv:
                return recv

        class _Connection:
            async def open_bi(self) -> _Stream:
                return _Stream()

        await open_stream(_Connection(), "adb")
        kind, length = parse_header(bytes(send.written[:HEADER_BYTES]))
        self.assertEqual((kind, json.loads(send.written[HEADER_BYTES:HEADER_BYTES + length])), (1, {"type": "tunnel", "service": "adb"}))


if __name__ == "__main__":
    unittest.main()
