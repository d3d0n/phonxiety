from __future__ import annotations

import asyncio
import json
import ssl
import tempfile
import unittest
from pathlib import Path

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import InvalidStatus

from phonxiety.device import AUDIO_HEADER, DeviceHub, WebSocketListener
from phonxiety.audio.opus import OpusEncoder
from phonxiety.tls import device_identity, server_context

TOKEN = "device-secret"
FRAME = b"\x01\x00" * 960


class DeviceLinkTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        cert, key, self.fingerprint = device_identity(Path(self.directory.name))
        self.hub = DeviceHub()
        self.listener = WebSocketListener(self.hub, TOKEN)
        await self.listener.start("127.0.0.1", 0, server_context(cert, key))
        self.url = f"wss://127.0.0.1:{self.listener.port}"
        self.client_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        self.client_tls.check_hostname = False
        self.client_tls.verify_mode = ssl.CERT_NONE

    async def asyncTearDown(self) -> None:
        await self.listener.close()
        self.directory.cleanup()

    def _connect(self, path: str = "/device", token: str = TOKEN) -> connect:
        return connect(
            self.url + path, ssl=self.client_tls, compression=None,
            additional_headers={"Authorization": f"Bearer {token}"},
        )

    async def _phone(self, socket: ClientConnection) -> None:
        await socket.send(json.dumps({"type": "hello", "version": 1, "device": "test", "capabilities": {}}))
        async with asyncio.timeout(2):
            while not self.hub.connected:
                await asyncio.sleep(0.01)

    async def test_identity_is_stable_across_restarts(self) -> None:
        self.assertEqual(device_identity(Path(self.directory.name))[2], self.fingerprint)

    async def test_rejects_wrong_token_and_foreign_path(self) -> None:
        for path, token, status in (("/device", "other", 401), ("/mcp", TOKEN, 404)):
            with self.assertRaises(InvalidStatus) as raised:
                async with self._connect(path, token):
                    pass
            self.assertEqual(raised.exception.response.status_code, status)
        self.assertFalse(self.hub.connected)

    async def test_stalled_link_drops_oldest_audio_and_keeps_the_drain_target_reachable(self) -> None:
        async with self._connect() as socket:
            await self._phone(socket)
            self.hub.begin_stream("call", 7)
            for _ in range(15):
                self.hub.send_audio("call", FRAME)
            self.assertEqual(self.hub.submitted_samples, 10 * 960)
            indexes = []
            async with asyncio.timeout(2):
                while len(indexes) < 10:
                    message = await socket.recv()
                    if isinstance(message, bytes):
                        magic, epoch, index = AUDIO_HEADER.unpack_from(message)
                        self.assertEqual((magic, epoch, message[AUDIO_HEADER.size:]), (b"PX01", 7, FRAME))
                        indexes.append(index)
                    else:
                        command = json.loads(message)
                        await socket.send(json.dumps({"type": "response", "id": command["id"], "ok": True}))
            self.assertEqual(indexes, [960 * position for position in range(5, 15)])

    async def test_late_echo_of_an_earlier_link_test_does_not_count(self) -> None:
        async with self._connect() as socket:
            await self._phone(socket)

            async def probe_frames(count: int, *, other_than: int | None = None) -> list[bytes]:
                seen: list[bytes] = []
                async with asyncio.timeout(2):
                    while len(seen) < count:
                        message = await socket.recv()
                        if isinstance(message, bytes) and AUDIO_HEADER.unpack_from(message)[1] != other_than:
                            seen.append(message)
                return seen

            first = asyncio.create_task(self.hub.link_test(0.05))
            stale = await probe_frames(1)
            await first
            second = asyncio.create_task(self.hub.link_test(0.05))
            current = await probe_frames(2, other_than=AUDIO_HEADER.unpack_from(stale[0])[1])
            await socket.send(stale[0])
            await socket.send(current[1])
            result = await second
            self.assertEqual(result["frames_echoed"], 1)


class OpusStreamTests(unittest.IsolatedAsyncioTestCase):
    class _Link:
        peer = "test"

        def paths(self) -> list[str]:
            return []

        def route(self) -> None:
            return None

    def _hub(self, offered: list[str]) -> DeviceHub:
        hub = DeviceHub(audio_codec="opus", opus_bitrate=32_000)
        hub.info = {"capabilities": {"audio_codecs": offered}}
        hub._link = self._Link()
        hub._ready.set()
        return hub

    async def test_phone_without_opus_gets_pcm(self) -> None:
        hub = self._hub(["pcm"])
        self.assertEqual(hub.begin_stream("call", 1), {"codec": "pcm"})
        hub.send_audio("call", FRAME)
        self.assertEqual(hub._outgoing.get_nowait()[2][:4], b"PX01")

    async def test_opus_frames_are_compact_counted_and_decoded(self) -> None:
        hub = self._hub(["pcm", "opus"])
        self.assertEqual(hub.begin_stream("call", 3), {"codec": "opus", "bitrate": 32_000})
        for _ in range(3):
            hub.send_audio("call", FRAME)
        frames = [hub._outgoing.get_nowait()[2] for _ in range(3)]
        self.assertEqual(hub.submitted_samples, 3 * 960)
        for position, frame in enumerate(frames):
            magic, epoch, index = AUDIO_HEADER.unpack_from(frame)
            self.assertEqual((magic, epoch, index), (b"PX02", 3, 960 * position))
            self.assertLess(len(frame) - AUDIO_HEADER.size, 400)

        decoded: list[int] = []

        async def on_audio(call_id: str, pcm: bytes) -> None:
            decoded.append(len(pcm))

        hub.on_audio = on_audio
        phone = OpusEncoder(32_000)
        for position in range(3):
            [packet] = phone.encode(FRAME)
            await hub._receive_audio(AUDIO_HEADER.pack(b"PX02", 3, 960 * position) + packet)
        self.assertEqual(decoded, [1920, 1920, 1920])


if __name__ == "__main__":
    unittest.main()
