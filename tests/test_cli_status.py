"""`phonxiety status` names the path the phone reached the daemon over."""

from __future__ import annotations

import unittest
from typing import Any

from phonxiety.cli import _status_lines


def health(peer: str | None, *, connected: bool = True, route: str | None = None) -> dict[str, Any]:
    return {
        "daemon": "ready",
        "device": {
            "connected": connected,
            "device": "Pixel 6a",
            "peer": peer,
            "route": route,
            "audio_codec": "opus",
            "capabilities": {"call_control": True, "framework_audio": True},
            "active_call": None,
        },
        "link": {"endpoint_id": "endpoint-1", "address_port": 18763, "certificate": "a" * 64},
    }


def row(lines: list[str], name: str) -> str:
    for line in lines:
        if line.split(maxsplit=1)[0] == name:
            return line
    raise AssertionError(f"no {name} row in {lines}")


class StatusPathTests(unittest.TestCase):
    def test_a_tailnet_peer_is_a_tailscale_path(self) -> None:
        lines = _status_lines(health("100.64.0.9"), color=False)
        self.assertIn("tailscale", row(lines, "path"))

    def test_a_magic_dns_peer_is_a_tailscale_path(self) -> None:
        lines = _status_lines(health("daemon.tail1a2b3.ts.net"), color=False)
        self.assertIn("tailscale", row(lines, "path"))

    def test_a_private_peer_is_a_lan_path(self) -> None:
        lines = _status_lines(health("192.168.1.10"), color=False)
        self.assertIn("lan", row(lines, "path"))

    def test_an_iroh_peer_keeps_its_transport_and_route(self) -> None:
        lines = _status_lines(health("iroh:abcd1234", route="direct 10.0.0.2, 5 ms"), color=False)
        path = row(lines, "path")
        self.assertIn("iroh", path)
        self.assertIn("direct 10.0.0.2, 5 ms", path)

    def test_a_disconnected_phone_shows_an_offline_path(self) -> None:
        lines = _status_lines(health(None, connected=False), color=False)
        self.assertIn("offline", row(lines, "path"))

    def test_the_tailnet_peer_still_appears_next_to_the_path(self) -> None:
        lines = _status_lines(health("100.64.0.9"), color=False)
        self.assertIn("100.64.0.9", row(lines, "phone"))


if __name__ == "__main__":
    unittest.main()
