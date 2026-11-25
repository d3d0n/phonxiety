"""Tailnet addresses, the path kind they imply, and the addresses the daemon reports."""

from __future__ import annotations

import unittest
from unittest import mock

from phonxiety.net import tailnet
from phonxiety.net.tailnet import (
    addresses_for,
    is_tailnet_address,
    local_addresses,
    parse_address,
    path_kind,
    tailscale_addresses,
)


class TailnetAddressTests(unittest.TestCase):
    def test_tailnet_ranges(self) -> None:
        self.assertTrue(is_tailnet_address("100.64.0.1"))
        self.assertTrue(is_tailnet_address("100.127.255.254"))
        self.assertFalse(is_tailnet_address("100.63.0.1"))
        self.assertFalse(is_tailnet_address("100.128.0.1"))
        self.assertTrue(is_tailnet_address("fd7a:115c:a1e0::1"))
        self.assertTrue(is_tailnet_address("[fd7a:115c:a1e0:ab12::7]"))
        self.assertFalse(is_tailnet_address("fd00::1"))
        self.assertTrue(is_tailnet_address("daemon.tail1a2b3.ts.net"))
        self.assertTrue(is_tailnet_address("DAEMON.TS.NET."))
        self.assertFalse(is_tailnet_address("daemon.example.com"))
        self.assertFalse(is_tailnet_address(None))

    def test_zone_suffix_is_stripped(self) -> None:
        address = parse_address("fe80::1%wlan0")
        self.assertIsNotNone(address)
        self.assertEqual(str(address), "fe80::1")

    def test_path_kind_names_the_transport(self) -> None:
        self.assertEqual(path_kind("iroh:abcd1234"), "iroh")
        self.assertEqual(path_kind("100.64.0.9"), "tailscale")
        self.assertEqual(path_kind("fd7a:115c:a1e0::9"), "tailscale")
        self.assertEqual(path_kind("192.168.1.10"), "lan")
        self.assertEqual(path_kind("10.1.2.3"), "lan")
        self.assertEqual(path_kind("127.0.0.1"), "lan")
        self.assertEqual(path_kind("8.8.8.8"), "address")
        self.assertEqual(path_kind("not-an-address"), "address")
        self.assertEqual(path_kind(None), "none")


class ReportedAddressTests(unittest.TestCase):
    def setUp(self) -> None:
        tailnet._ADDRESS_CACHE.clear()

    def tearDown(self) -> None:
        tailnet._ADDRESS_CACHE.clear()

    def test_addresses_for_groups_lan_and_tailnet_urls(self) -> None:
        with mock.patch.object(tailnet, "local_addresses", return_value=["192.168.1.10"]), \
                mock.patch.object(tailnet, "tailscale_addresses", return_value=["100.64.0.2", "fd7a:115c:a1e0::2"]):
            addresses = addresses_for(18763)
        self.assertEqual(["wss://192.168.1.10:18763/device"], addresses["lan"])
        self.assertEqual(
            ["wss://100.64.0.2:18763/device", "wss://[fd7a:115c:a1e0::2]:18763/device"],
            addresses["tailscale"],
        )

    def test_addresses_are_cached_for_the_ttl(self) -> None:
        with mock.patch.object(tailnet, "local_addresses", return_value=["192.168.1.10"]) as local, \
                mock.patch.object(tailnet, "tailscale_addresses", return_value=[]):
            addresses_for(18763)
            addresses_for(18763)
        self.assertEqual(1, local.call_count)
        # The answer is a copy: a caller editing it cannot poison the cache.
        addresses_for(18763)["lan"].append("wss://wrong")
        self.assertNotIn("wss://wrong", addresses_for(18763)["lan"])

    def test_tailscale_addresses_without_the_binary(self) -> None:
        with mock.patch("phonxiety.net.tailnet.shutil.which", return_value=None):
            self.assertEqual([], tailscale_addresses())

    def test_tailscale_addresses_read_both_families(self) -> None:
        results = {"-4": "100.64.0.2\n", "-6": "fd7a:115c:a1e0::2\n"}

        class _Result:
            def __init__(self, stdout: str) -> None:
                self.stdout = stdout
                self.returncode = 0

        def fake_run(arguments, **_kwargs):  # type: ignore[no-untyped-def]
            return _Result(results[arguments[-1]])

        with mock.patch("phonxiety.net.tailnet.shutil.which", return_value="/usr/bin/tailscale"), \
                mock.patch("phonxiety.net.tailnet.subprocess.run", side_effect=fake_run):
            self.assertEqual(["100.64.0.2", "fd7a:115c:a1e0::2"], tailscale_addresses())

    def test_local_addresses_never_report_the_tailnet(self) -> None:
        with mock.patch.object(tailnet.socket, "socket", side_effect=OSError), \
                mock.patch.object(tailnet.socket, "getaddrinfo", return_value=[
            (2, 1, 6, "", ("192.168.1.10", 0)),
            (2, 1, 6, "", ("100.64.0.2", 0)),
            (2, 1, 6, "", ("127.0.0.1", 0)),
            (2, 1, 6, "", ("8.8.8.8", 0)),
        ]):
            found = local_addresses()
        self.assertEqual(["192.168.1.10"], found)


if __name__ == "__main__":
    unittest.main()
