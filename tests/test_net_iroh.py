from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from pathlib import Path

from phonxiety.device import DeviceHub
from phonxiety.net.framing import FrameError, frame, parse_header
from phonxiety.net.iroh import IrohLink, IrohListener

TOKEN = "device-secret"
HELLO = json.dumps({"type": "hello", "version": 1, "device": "test", "capabilities": {}})


class _Recv:
    def __init__(self, data: bytes) -> None:
        self.data = bytearray(data)
        self.idle = asyncio.Event()

    async def read_exact(self, size: int) -> bytes:
        if len(self.data) < size:
            # The peer stays silent: a real stream blocks here until it is closed.
            await self.idle.wait()
        chunk = bytes(self.data[:size])
        del self.data[:size]
        return chunk


class _Send:
    def __init__(self) -> None:
        self.written = bytearray()

    async def write_all(self, data: bytes) -> None:
        self.written += data


class _Stream:
    def __init__(self, incoming: bytes) -> None:
        self._recv, self._send = _Recv(incoming), _Send()

    def recv(self) -> _Recv:
        return self._recv

    def send(self) -> _Send:
        return self._send


class _Id:
    def fmt_short(self) -> str:
        return "abcdef0123"


class _Connection:
    def __init__(self, incoming: bytes) -> None:
        self.stream = _Stream(incoming)
        self.closed: tuple[int, bytes] | None = None

    def remote_id(self) -> _Id:
        return _Id()

    def paths(self) -> list:
        return []

    async def accept_bi(self) -> _Stream:
        return self.stream

    def close(self, code: int, reason: bytes) -> None:
        self.closed = (code, reason)


class _Incoming:
    def __init__(self, connection: _Connection) -> None:
        self._connection = connection

    async def accept(self) -> _Incoming:
        return self

    async def connect(self) -> _Connection:
        return self._connection


class FramingTests(unittest.TestCase):
    def test_text_and_binary_round_trip_through_the_header(self) -> None:
        for message in ("{\"type\":\"ping\"}", b"PX01" + bytes(12) + b"\x01\x00"):
            encoded = frame(message)
            kind, length = parse_header(encoded[:5])
            self.assertEqual((kind, encoded[5:]), (1 if isinstance(message, str) else 2, message.encode() if isinstance(message, str) else message))
            self.assertEqual(length, len(encoded) - 5)

    def test_rejects_unknown_kind_empty_and_oversized_frames(self) -> None:
        for header in (b"\x03\x00\x00\x00\x01", b"\x01\x00\x00\x00\x00", b"\x02\x00\x02\x00\x01"):
            with self.assertRaises(FrameError):
                parse_header(header)


class ListenerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.hub = DeviceHub()
        self.listener = IrohListener(self.hub, TOKEN, Path(self.directory.name))

    async def asyncTearDown(self) -> None:
        self.directory.cleanup()

    async def test_wrong_token_never_reaches_the_hub(self) -> None:
        connection = _Connection(frame(json.dumps({"type": "auth", "token": "other"})) + frame(HELLO))
        await self.listener._serve(_Incoming(connection))
        self.assertFalse(self.hub.connected)
        self.assertEqual(connection.closed, (4401, b"unauthorized"))

    async def test_hello_without_auth_frame_is_rejected(self) -> None:
        connection = _Connection(frame(HELLO))
        await self.listener._serve(_Incoming(connection))
        self.assertFalse(self.hub.connected)
        self.assertEqual(connection.closed, (4401, b"unauthorized"))

    async def test_authorized_phone_is_attached_and_receives_heartbeats(self) -> None:
        connection = _Connection(frame(json.dumps({"type": "auth", "token": TOKEN})) + frame(HELLO))
        serving = asyncio.create_task(self.listener._serve(_Incoming(connection)))
        async with asyncio.timeout(2):
            while not connection.stream.send().written:
                await asyncio.sleep(0.01)
        self.assertTrue(self.hub.connected)
        self.assertEqual(self.hub.status()["peer"], "iroh:abcdef0123")
        written = bytes(connection.stream.send().written)
        kind, length = parse_header(written[:5])
        self.assertEqual((kind, json.loads(written[5:5 + length])["command"]), (1, "ping"))
        serving.cancel()
        await asyncio.gather(serving, return_exceptions=True)
        self.assertFalse(self.hub.connected)


class LinkTests(unittest.IsolatedAsyncioTestCase):
    async def test_link_delivers_framed_messages_in_order(self) -> None:
        connection = _Connection(frame("first") + frame(b"\x00\x01"))
        link = IrohLink(connection, connection.stream)
        self.assertEqual([await link.recv(), await link.recv()], ["first", b"\x00\x01"])
        await link.send("reply")
        self.assertEqual(bytes(connection.stream.send().written), frame("reply"))


if __name__ == "__main__":
    unittest.main()
