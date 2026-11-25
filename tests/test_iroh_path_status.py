"""The daemon reports, and `phonxiety status` shows, which iroh path the phone uses."""

from __future__ import annotations

import unittest
from typing import Any

from phonxiety.cli import _status_lines
from phonxiety.device import DeviceHub


class FakeIrohLink:
    """An authenticated iroh link with a fixed selected-path kind."""

    peer = "iroh:abcd1234"

    def __init__(self, kind: str | None) -> None:
        self._kind = kind

    def route(self) -> str | None:
        return None

    def paths(self) -> list[str]:
        return []

    def selected_path_kind(self) -> str | None:
        return self._kind


class FakeAddressLink:
    """The TLS WebSocket link: it has no iroh path at all."""

    peer = "192.168.1.10"

    def route(self) -> str | None:
        return None

    def paths(self) -> list[str]:
        return []


def attached(link: Any) -> DeviceHub:
    hub = DeviceHub()
    hub._link = link
    hub._ready.set()
    return hub


def health(iroh_path: str | None, *, peer: str | None = "iroh:abcd1234") -> dict[str, Any]:
    return {
        "daemon": "ready",
        "device": {
            "connected": peer is not None,
            "device": "Pixel 6a",
            "peer": peer,
            "route": "direct 10.0.0.2, 5 ms" if peer else None,
            "audio_codec": "opus",
            "capabilities": {"call_control": True, "framework_audio": True},
            "active_call": None,
        },
        "link": {
            "endpoint_id": "endpoint-1",
            "address_port": 18763,
            "certificate": "a" * 64,
            "iroh_path": iroh_path,
        },
    }


def row(lines: list[str], name: str) -> str:
    for line in lines:
        if line.split(maxsplit=1)[0] == name:
            return line
    raise AssertionError(f"no {name} row in {lines}")


class IrohPathProducerTests(unittest.TestCase):
    """`DeviceHub.iroh_path` is what the daemon puts into `/health.link.iroh_path`."""

    def test_a_direct_path_is_reported_as_direct(self) -> None:
        self.assertEqual("direct", attached(FakeIrohLink("direct")).iroh_path)

    def test_a_relayed_path_is_reported_as_relay(self) -> None:
        self.assertEqual("relay", attached(FakeIrohLink("relay")).iroh_path)

    def test_an_unknown_path_is_null(self) -> None:
        self.assertIsNone(attached(FakeIrohLink(None)).iroh_path)

    def test_a_disconnected_hub_has_no_path(self) -> None:
        hub = attached(FakeIrohLink("direct"))
        hub._link = None
        self.assertIsNone(hub.iroh_path)

    def test_a_non_iroh_link_has_no_iroh_path(self) -> None:
        self.assertIsNone(attached(FakeAddressLink()).iroh_path)

    def test_the_path_follows_a_link_transition(self) -> None:
        hub = attached(FakeIrohLink("relay"))
        self.assertEqual("relay", hub.iroh_path)
        hub._link = FakeIrohLink("direct")
        self.assertEqual("direct", hub.iroh_path)
        hub._link = FakeIrohLink(None)
        self.assertIsNone(hub.iroh_path)

    def test_the_device_status_contract_is_unchanged(self) -> None:
        self.assertNotIn("iroh_path", attached(FakeIrohLink("direct")).status())


class IrohPathStatusLineTests(unittest.TestCase):
    """`phonxiety status` consumes `/health.link.iroh_path`."""

    def test_a_direct_path_is_printed(self) -> None:
        lines = _status_lines(health("direct"), color=False)
        self.assertIn("direct", row(lines, "iroh"))
        self.assertIn("iroh", row(lines, "path"))

    def test_a_relayed_path_is_printed(self) -> None:
        lines = _status_lines(health("relay"), color=False)
        self.assertIn("relay", row(lines, "iroh"))

    def test_an_unknown_path_is_printed_as_unknown(self) -> None:
        lines = _status_lines(health(None), color=False)
        self.assertIn("unknown", row(lines, "iroh"))

    def test_a_disconnected_phone_has_no_iroh_row(self) -> None:
        lines = _status_lines(health(None, peer=None), color=False)
        with self.assertRaises(AssertionError):
            row(lines, "iroh")
        self.assertIn("offline", row(lines, "path"))

    def test_a_non_iroh_link_has_no_iroh_row(self) -> None:
        payload = health(None, peer="192.168.1.10")
        lines = _status_lines(payload, color=False)
        with self.assertRaises(AssertionError):
            row(lines, "iroh")
        self.assertIn("lan", row(lines, "path"))


if __name__ == "__main__":
    unittest.main()
