"""The iroh pairing QR: the certificate pin and the daemon's other addresses ride along."""

from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stdout
from types import SimpleNamespace
from unittest import mock
from urllib.parse import parse_qs, urlsplit

import segno

from phonxiety.cli import PAIRING_QR_MAX_VERSION, _cmd_pair, build_parser

PIN = "ab" * 32
LAN = ["wss://192.168.1.10:18763/device"]
TAILSCALE = ["wss://100.64.0.2:18763/device"]


def _settings() -> SimpleNamespace:
    return SimpleNamespace(device_token="device-token", app_token="host-app-token")


def _pairing(**overrides: object) -> dict[str, object]:
    pairing: dict[str, object] = {
        "iroh": "ticket-abc",
        "endpoint_id": "endpoint-1",
        "app_token": "daemon-app-token",
        "certificate": PIN,
        "lan": list(LAN),
        "tailscale": list(TAILSCALE),
    }
    pairing.update(overrides)
    return pairing


class PairIrohTests(unittest.TestCase):
    def run_pair(self, argv: list[str], pairing: dict[str, object]) -> tuple[str, mock.Mock]:
        args = build_parser().parse_args(["pair", *argv])
        output = io.StringIO()
        with mock.patch("phonxiety.cli._settings", return_value=_settings()), \
                mock.patch("phonxiety.cli._ensure_daemon"), \
                mock.patch("phonxiety.cli._daemon_get", return_value=pairing), \
                mock.patch("phonxiety.cli._render_pairing_qr") as render, \
                redirect_stdout(output):
            _cmd_pair(args)
        return output.getvalue(), render

    def test_the_iroh_qr_carries_the_pin_and_every_address(self) -> None:
        text, render = self.run_pair([], _pairing())

        query = parse_qs(urlsplit(render.call_args.args[0]).query)
        self.assertEqual(["ticket-abc"], query["iroh"])
        self.assertEqual(["device-token"], query["token"])
        self.assertEqual(["daemon-app-token"], query["app_token"])
        self.assertEqual([PIN], query["cert"])
        self.assertEqual(LAN, query["lan"])
        self.assertEqual(TAILSCALE, query["tailscale"])
        self.assertIn("QR payload:", text)

    def test_json_carries_the_same_pairing(self) -> None:
        text, render = self.run_pair(["--json"], _pairing())

        render.assert_not_called()
        data = json.loads(text)
        self.assertEqual("ticket-abc", data["iroh"])
        self.assertEqual("device-token", data["token"])
        self.assertEqual("daemon-app-token", data["app_token"])
        self.assertEqual(PIN, data["cert_sha256"])
        self.assertEqual(LAN, data["lan"])
        self.assertEqual(TAILSCALE, data["tailscale"])

    def test_a_payload_too_long_for_any_qr_is_still_trimmed_to_a_scannable_one(self) -> None:
        long = "wss://" + "a" * 300 + ":18763/device"
        lan = [f"wss://192.168.1.{index}:18763/device" for index in range(1, 3)]
        tailscale = [f"{long}{index}" for index in range(8)]

        text, render = self.run_pair([], _pairing(lan=lan, tailscale=tailscale))

        payload = render.call_args.args[0]
        self.assertLessEqual(segno.make_qr(payload, error="l").version, PAIRING_QR_MAX_VERSION)
        self.assertIn("QR payload after trimming:", text)

    def test_a_daemon_that_reports_no_addresses_still_pairs(self) -> None:
        old = {"iroh": "ticket-old", "endpoint_id": "endpoint-0", "app_token": "old-app-token"}
        text, render = self.run_pair(["--json"], old)

        render.assert_not_called()
        data = json.loads(text)
        self.assertEqual("ticket-old", data["iroh"])
        self.assertNotIn("cert_sha256", data)
        self.assertNotIn("lan", data)
        self.assertNotIn("tailscale", data)

    def test_a_grown_qr_drops_the_tailnet_tail_and_reports_both_sizes(self) -> None:
        lan = [f"wss://192.168.1.{index}:18763/device" for index in range(1, 16)]
        tailscale = [f"wss://100.64.0.{index}:18763/device" for index in range(1, 16)]

        text, render = self.run_pair([], _pairing(lan=lan, tailscale=tailscale))

        payload = render.call_args.args[0]
        query = parse_qs(urlsplit(payload).query)
        self.assertLessEqual(segno.make_qr(payload, error="l").version, PAIRING_QR_MAX_VERSION)
        self.assertTrue(query["lan"])
        self.assertNotIn("tailscale", query)
        self.assertIn("QR payload after trimming:", text)
        before = [line for line in text.splitlines() if line.startswith("QR payload:")]
        after = [line for line in text.splitlines() if line.startswith("QR payload after trimming:")]
        self.assertEqual(1, len(before))
        self.assertEqual(1, len(after))
        self.assertNotEqual(before[0], after[0])


if __name__ == "__main__":
    unittest.main()
