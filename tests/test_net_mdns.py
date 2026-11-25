"""The daemon's mDNS advertisement of its address link, against a fake responder."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

from phonxiety.net.tls import device_identity, server_context
from phonxiety.net.websocket import WebSocketListener
from phonxiety.net.mdns import (
    SERVICE_TYPE,
    TXT_CERT,
    TXT_PROTOCOL,
    MdnsAdvertiser,
    service_info,
)

PIN = "a" * 64


class FakeZeroconf:
    """Records what the advertiser asked of the real responder."""

    def __init__(self, *, fail_register: Exception | None = None, fail_close: bool = False) -> None:
        self.registered: list[Any] = []
        self.unregistered = 0
        self.closed = 0
        self.fail_register = fail_register
        self.fail_close = fail_close

    def register(self, info: Any) -> None:
        if self.fail_register is not None:
            raise self.fail_register
        self.registered.append(info)

    def unregister_all(self) -> None:
        self.unregistered += 1

    def close(self) -> None:
        self.closed += 1
        if self.fail_close:
            raise OSError("close failed")


class AdvertisementTests(unittest.TestCase):
    def advertiser(self, backend: FakeZeroconf, host: str | None = "192.168.1.10") -> MdnsAdvertiser:
        return MdnsAdvertiser(18763, PIN, 3, host=host, backend=backend)

    def test_start_publishes_the_port_the_pin_and_the_protocol(self) -> None:
        backend = FakeZeroconf()
        advertiser = self.advertiser(backend)
        self.assertTrue(advertiser.start())
        self.assertTrue(advertiser.started)
        (info,) = backend.registered
        self.assertTrue(info.name.endswith(f".{SERVICE_TYPE}"))
        self.assertEqual(18763, info.port)
        properties = info.properties
        self.assertEqual(PIN.encode(), properties[TXT_CERT.encode()])
        self.assertEqual(b"3", properties[TXT_PROTOCOL.encode()])

    def test_stop_withdraws_once(self) -> None:
        backend = FakeZeroconf()
        advertiser = self.advertiser(backend)
        advertiser.start()
        advertiser.stop()
        advertiser.stop()
        self.assertFalse(advertiser.started)
        self.assertEqual(1, backend.unregistered)
        self.assertEqual(1, backend.closed)

    def test_a_refused_registration_is_not_fatal(self) -> None:
        backend = FakeZeroconf(fail_register=OSError("no multicast"))
        advertiser = self.advertiser(backend)
        self.assertFalse(advertiser.start())
        self.assertFalse(advertiser.started)
        self.assertEqual(1, backend.closed)
        advertiser.stop()  # idempotent after a failure

    def test_a_failing_close_is_swallowed_on_stop(self) -> None:
        backend = FakeZeroconf(fail_close=True)
        advertiser = self.advertiser(backend)
        advertiser.start()
        advertiser.stop()
        self.assertFalse(advertiser.started)

    def test_start_twice_registers_once(self) -> None:
        backend = FakeZeroconf()
        advertiser = self.advertiser(backend)
        self.assertTrue(advertiser.start())
        self.assertTrue(advertiser.start())
        self.assertEqual(1, len(backend.registered))

    def test_service_info_advertises_the_local_address(self) -> None:
        info = service_info(18763, PIN, 3, "192.168.1.10")
        self.assertEqual(SERVICE_TYPE, info.type)
        self.assertIn("192.168.1.10", info.parsed_addresses())
        self.assertEqual(18763, info.port)


class MissingPackageTests(unittest.TestCase):
    def test_start_reports_false_when_the_responder_is_missing(self) -> None:
        advertiser = MdnsAdvertiser(18763, PIN, 3, host="192.168.1.10")
        with mock.patch("phonxiety.net.mdns.ZeroconfBackend", side_effect=ImportError("zeroconf")):
            self.assertFalse(advertiser.start())
        self.assertFalse(advertiser.started)


class ListenerAdvertisementTests(unittest.IsolatedAsyncioTestCase):
    """The address listener publishes itself, and withdraws the advertisement when it closes."""

    class _Hub:
        async def attach(self, link: Any) -> None:
            return None

    async def test_the_listener_advertises_with_its_port_and_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert, key, fingerprint = device_identity(Path(directory))
            context = server_context(cert, key)
            advertiser = mock.Mock()
            advertiser.start.return_value = True
            with mock.patch("phonxiety.net.websocket.MdnsAdvertiser", return_value=advertiser) as factory:
                listener = WebSocketListener(
                    self._Hub(), "token", certificate=fingerprint, protocol=2
                )
                await listener.start("127.0.0.1", 0, context)
                try:
                    factory.assert_called_once_with(listener.port, fingerprint, 2)
                    advertiser.start.assert_called_once_with()
                finally:
                    await listener.close()
            advertiser.stop.assert_called_once_with()

    async def test_no_advertisement_without_a_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert, key, _ = device_identity(Path(directory))
            context = server_context(cert, key)
            with mock.patch("phonxiety.net.websocket.MdnsAdvertiser") as factory:
                listener = WebSocketListener(self._Hub(), "token")
                await listener.start("127.0.0.1", 0, context)
                await listener.close()
            factory.assert_not_called()


if __name__ == "__main__":
    unittest.main()
