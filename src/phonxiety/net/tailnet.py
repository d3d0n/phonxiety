"""Where the daemon can be reached, and what a connected phone's address means.

The daemon's address link is one TLS WebSocket listener; which network it is
reached over decides the path the phone is using:

* ``iroh`` — the QUIC transport; its peer is an endpoint id, not an address.
* ``lan`` — a private address on the same network as the daemon.
* ``tailscale`` — an address in the tailnet: the Tailscale app on the phone
  provides the interface, and the daemon only needs to report where it is.
* ``address`` — anything else, e.g. a forwarded public address.
"""

from __future__ import annotations

import ipaddress
import json
import shutil
import socket
import subprocess
import time

# Tailscale's fixed ranges: the CGNAT block it hands out and its IPv6 prefix.
TAILNET_V4 = ipaddress.ip_network("100.64.0.0/10")
TAILNET_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")
MAGIC_DNS_SUFFIX = ".ts.net"

_PRIVATE = tuple(
    ipaddress.ip_network(network)
    for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8")
)


def parse_address(text: str | None) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    """The address in ``text``, with IPv6 brackets and a ``%zone`` removed."""
    if not text:
        return None
    candidate = text.strip().removeprefix("[").split("]", 1)[0]
    candidate = candidate.split("%", 1)[0]
    try:
        return ipaddress.ip_address(candidate)
    except ValueError:
        return None


def is_tailnet_address(text: str | None) -> bool:
    """True for an address in the tailnet ranges, or a MagicDNS name."""
    if not text:
        return False
    name = text.strip().lower()
    if name.endswith(MAGIC_DNS_SUFFIX) or name.endswith(f"{MAGIC_DNS_SUFFIX}."):
        return True
    address = parse_address(name)
    if address is None:
        return False
    if isinstance(address, ipaddress.IPv4Address):
        return address in TAILNET_V4
    return address in TAILNET_V6


def _is_private(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    if isinstance(address, ipaddress.IPv4Address):
        return any(address in network for network in _PRIVATE)
    return address.is_private or address.is_link_local or address.is_loopback


def path_kind(peer: str | None) -> str:
    """Classify a connected phone's peer string.

    ``iroh:<id>`` is the iroh transport; an address is the TLS listener, and
    the ranges decide whether it is ``lan``, ``tailscale`` or ``address``.
    """
    if not peer:
        return "none"
    if peer.startswith("iroh:"):
        return "iroh"
    if is_tailnet_address(peer):
        return "tailscale"
    address = parse_address(peer)
    if address is None:
        return "address"
    return "lan" if _is_private(address) else "address"


def _tailscale_binary() -> str | None:
    return shutil.which("tailscale")


def tailscale_addresses() -> list[str]:
    """This computer's tailnet addresses while Tailscale is connected; empty otherwise.

    Reads ``tailscale ip`` (one address per line, IPv4 and IPv6) and falls back
    to ``tailscale status --json`` when the short form is unavailable.  Never
    raises: a missing or stopped Tailscale simply reports nothing.
    """
    binary = _tailscale_binary()
    if binary is None:
        return []
    found: list[str] = []
    for arguments in (["ip", "-4"], ["ip", "-6"]):
        try:
            result = subprocess.run([binary, *arguments], capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            continue
        if result.returncode != 0:
            continue
        for line in result.stdout.split():
            address = parse_address(line)
            if address is not None and is_tailnet_address(str(address)):
                found.append(str(address))
    if found:
        return _dedupe(found)
    try:
        result = subprocess.run([binary, "status", "--json"], capture_output=True, text=True, timeout=5)
        status = json.loads(result.stdout) if result.returncode == 0 else {}
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return []
    self_node = status.get("Self") if isinstance(status, dict) else None
    addresses = self_node.get("TailscaleIPs") if isinstance(self_node, dict) else None
    if isinstance(addresses, list):
        for entry in addresses:
            address = parse_address(str(entry))
            if address is not None and is_tailnet_address(str(address)):
                found.append(str(address))
    return _dedupe(found)


def local_addresses() -> list[str]:
    """Addresses this computer has on its LAN interfaces, loopback excluded."""
    found: list[str] = []
    # The address the default route would use, which the host name does not always resolve to.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            found.append(probe.getsockname()[0])
    except OSError:
        pass
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None)
    except OSError:
        infos = []
    for info in infos:
        address = parse_address(info[4][0])
        if address is None or address.is_loopback or address.is_link_local:
            continue
        if isinstance(address, ipaddress.IPv4Address) and not _is_private(address):
            continue
        if is_tailnet_address(str(address)):
            # A tailnet address is reported separately: it needs the Tailscale app on the phone.
            continue
        found.append(str(address))
    return _dedupe(found)


_ADDRESS_CACHE: dict[int, tuple[float, dict[str, list[str]]]] = {}
ADDRESS_CACHE_S = 60.0


def addresses_for(port: int, *, ttl_s: float = ADDRESS_CACHE_S) -> dict[str, list[str]]:
    """The ``wss://`` addresses the daemon offers, grouped by path.

    Cached for a minute: the app API asks on every ``/info``, and the tailnet
    addresses need a ``tailscale`` call.
    """

    def url(host: str) -> str:
        authority = f"[{host}]" if ":" in host else host
        return f"wss://{authority}:{port}/device"

    cached = _ADDRESS_CACHE.get(port)
    if cached is not None and time.monotonic() - cached[0] < ttl_s:
        return {key: list(value) for key, value in cached[1].items()}
    addresses = {
        "lan": _dedupe([url(host) for host in local_addresses()]),
        "tailscale": _dedupe([url(host) for host in tailscale_addresses()]),
    }
    _ADDRESS_CACHE[port] = (time.monotonic(), addresses)
    return {key: list(value) for key, value in addresses.items()}


def _dedupe(values: list[str]) -> list[str]:
    seen: dict[str, None] = {}
    for value in values:
        seen.setdefault(value, None)
    return list(seen)
