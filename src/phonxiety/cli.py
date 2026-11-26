"""Command line entry point for phonxiety.

Exposes ``daemon``, ``mcp``, ``status``, ``auth``, ``pair`` and the three call
operations.  ``status`` uses the daemon's real health endpoint; call
operations go through the daemon's MCP tools, so no private REST surface is
invented here.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import uuid
from typing import Any

MCP_PATH = "/mcp"


class CliError(Exception):
    """A user-facing CLI failure with no traceback."""


def _short(value: Any) -> str:
    text = " ".join(str(value).split())
    if not text:
        text = type(value).__name__
    return text[:500]


def _settings() -> Any:
    from .settings import Settings

    return Settings.from_env()


def _print_json(payload: Any) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))


async def _invoke(
    settings: Any,
    tool: str,
    arguments: dict[str, Any],
    *,
    timeout: float,
) -> Any:
    from fastmcp import Client
    from fastmcp.client.auth import BearerAuth

    endpoint = settings.base_url.rstrip("/") + MCP_PATH
    client = Client(
        endpoint,
        auth=BearerAuth(settings.host_token),
        timeout=timeout,
        init_timeout=10.0,
    )
    async with client:
        result = await client.call_tool(tool, arguments, timeout=timeout)
    for value in (result.data, result.structured_content):
        if isinstance(value, (dict, list)):
            return value
    raise CliError(f"{tool} returned no structured result")


def _call_tool(
    settings: Any,
    tool: str,
    arguments: dict[str, Any],
    *,
    timeout: float,
) -> Any:
    try:
        return asyncio.run(_invoke(settings, tool, arguments, timeout=timeout))
    except CliError:
        raise
    except Exception as exc:
        raise CliError(f"{tool} failed: {_short(exc)}") from None


def _cmd_daemon(_args: argparse.Namespace) -> None:
    from .server import main as server_main

    server_main()


def _cmd_mcp(_args: argparse.Namespace) -> None:
    from .mcp import main as mcp_main

    mcp_main()


def _ensure_daemon(settings: Any) -> None:
    from .mcp import ensure_daemon

    try:
        ensure_daemon(settings)
    except RuntimeError as exc:
        raise CliError(_short(exc)) from None


def _cmd_start(_args: argparse.Namespace) -> None:
    settings = _settings()
    _ensure_daemon(settings)
    print("\n".join(_status_lines(_daemon_get(settings, "/health"), color=sys.stdout.isatty())))


def _cmd_stop(_args: argparse.Namespace) -> None:
    import fcntl
    import os
    import signal
    import time

    path = _settings().state_dir / "daemon.lock"

    def running() -> bool:
        # The daemon holds an exclusive lock on this file for its whole life.
        try:
            with open(path, "a+") as lock:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        except OSError:
            return False
        return False

    if not running():
        print("The daemon is not running.")
        return
    try:
        os.kill(int(path.read_text().strip()), signal.SIGTERM)
    except (OSError, ValueError) as exc:
        raise CliError(f"cannot signal the daemon: {_short(exc)}") from None
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if not running():
            print("The daemon stopped.")
            return
        time.sleep(0.2)
    raise CliError("the daemon did not stop within 25 s")


def _daemon_get(settings: Any, path: str, *, timeout: float = 5.0) -> Any:
    """GET a host-token protected daemon endpoint and return its JSON body."""
    import httpx

    try:
        response = httpx.get(
            settings.base_url.rstrip("/") + path,
            headers={"Authorization": f"Bearer {settings.host_token}"},
            timeout=timeout,
            follow_redirects=False,
            trust_env=False,
        )
    except httpx.HTTPError as exc:
        raise CliError(
            f"daemon unreachable at {settings.host}:{settings.port}: {_short(exc)}"
        ) from None
    if response.status_code == 401:
        raise CliError(
            "daemon rejected the host token; PHONXIETY_HOST_TOKEN and the "
            "installation token file must match"
        )
    try:
        body = response.json()
    except ValueError:
        raise CliError(f"daemon {path} returned invalid JSON") from None
    if response.status_code != 200:
        detail = ""
        if isinstance(body, dict) and isinstance(body.get("error"), str):
            detail = ": " + _short(body["error"])
        raise CliError(f"daemon {path} returned HTTP {response.status_code}{detail}")
    return body


def _status_lines(payload: dict[str, Any], *, color: bool) -> list[str]:
    """Health as aligned rows; the state word is coloured on a terminal."""
    from .net.tailnet import path_kind

    def tone(text: str, code: str) -> str:
        return f"\x1b[{code}m{text}\x1b[0m" if color else text

    good, warn, bad = "32", "33", "31"
    device = payload.get("device") if isinstance(payload.get("device"), dict) else {}
    link = payload.get("link") if isinstance(payload.get("link"), dict) else {}
    capabilities = device.get("capabilities") if isinstance(device.get("capabilities"), dict) else {}
    daemon = str(payload.get("daemon", "unknown"))
    rows = [("daemon", tone(daemon, good if daemon == "ready" else warn))]
    if device.get("connected"):
        route = f" ({device['route']})" if device.get("route") else ""
        rows.append(("phone", f"{tone('connected', good)}  {device.get('device')} via {device.get('peer')}{route}"))
        missing = [
            label
            for key, label in (("call_control", "call control"), ("framework_audio", "call audio"))
            if not capabilities.get(key)
        ]
        if missing:
            rows.append(("calling", f"{tone('unavailable', bad)}  the phone reports no {' and no '.join(missing)}"))
        elif capabilities.get("busy"):
            rows.append(("calling", f"{tone('busy', warn)}  another call is on the phone"))
        else:
            rows.append(("calling", f"{tone('ready', good)}  {device.get('audio_codec')} audio"))
    else:
        rows.append(("phone", f"{tone('not connected', bad)}  pair it with: phonxiety pair"))
    if device.get("connected"):
        peer = device.get("peer")
        kind = path_kind(str(peer)) if peer else "none"
        route = f"  {device['route']}" if device.get("route") else ""
        rows.append(("path", f"{tone(kind, good if kind in ('iroh', 'lan', 'tailscale') else warn)}{route}"))
        if kind == "iroh":
            iroh_path = link.get("iroh_path")
            label = str(iroh_path) if iroh_path else "unknown"
            rows.append(("iroh", tone(label, good if iroh_path == "direct" else warn)))
    else:
        rows.append(("path", tone("offline", warn)))
    call = device.get("active_call")
    if isinstance(call, dict):
        rows.append(("call", f"{call.get('state')}  {call.get('call_id')}"))
    else:
        rows.append(("call", "none"))
    endpoint = link.get("endpoint_id")
    rows.append(("endpoint", str(endpoint) if endpoint else tone("unavailable", warn)))
    rows.append(("address", f"port {link.get('address_port')}  certificate {str(link.get('certificate'))[:16]}"))
    return [f"{name:<9} {value}" for name, value in rows]


def _cmd_status(args: argparse.Namespace) -> None:
    payload = _daemon_get(_settings(), "/health")
    if args.json or not isinstance(payload, dict):
        _print_json(payload)
        return
    print("\n".join(_status_lines(payload, color=sys.stdout.isatty())))


def _link_test_lines(result: dict[str, Any], *, color: bool) -> list[str]:
    """A call-load test of the phone link as aligned rows with a verdict."""

    def tone(text: str, code: str) -> str:
        return f"\x1b[{code}m{text}\x1b[0m" if color else text

    def spread(values: Any) -> str:
        if not isinstance(values, dict):
            return "no answer"
        return f"median {values.get('median')} ms, p95 {values.get('p95')} ms, max {values.get('max')} ms"

    sent = int(result.get("frames_sent") or 0)
    echoed = int(result.get("frames_echoed") or 0)
    route = f" ({result['route']})" if result.get("route") else ""
    if result.get("link_lost"):
        verdict = tone("failed", "31") + "  the link went down during the test"
    elif not echoed:
        verdict = tone("unknown", "33") + "  the phone returned no test frames; update the phone app"
    elif not result.get("healthy"):
        verdict = tone("poor", "33") + "  speech will arrive late or with gaps"
    else:
        verdict = tone("good", "32") + "  the idle link carries call-rate audio"
    rows = [
        ("link", f"{result.get('peer')}{route}"),
        ("audio", f"{echoed} of {sent} {result.get('codec')} frames ({result.get('frame_bytes')} B) returned in {result.get('seconds')} s"),
        ("delay", spread(result.get("frame_ms"))),
        ("control", spread(result.get("ping_ms"))),
        ("verdict", verdict),
    ]
    return [f"{name:<9} {value}" for name, value in rows]


def _cmd_link_test(args: argparse.Namespace) -> None:
    settings = _settings()
    _ensure_daemon(settings)
    query = f"seconds={args.seconds}" + (f"&codec={args.codec}" if args.codec else "")
    if args.datagrams:
        query += "&transport=datagram"
    result = _daemon_get(settings, f"/link-test?{query}", timeout=args.seconds + 30.0)
    if args.json or not isinstance(result, dict):
        _print_json(result)
        return
    print("\n".join(_link_test_lines(result, color=sys.stdout.isatty())))


def _cmd_call(args: argparse.Namespace) -> None:
    settings = _settings()
    _ensure_daemon(settings)
    request_id = args.request_id or uuid.uuid4().hex
    arguments: dict[str, Any] = {
        "request_id": request_id,
        "destination": args.destination,
        "goal": args.goal,
        "context": args.context,
        "constraints": args.constraints,
        "max_duration_s": args.max_duration_s,
        "ring_timeout_s": args.ring_timeout_s,
    }
    if args.result_field:
        arguments["result_fields"] = args.result_field
    if args.phone_account:
        arguments["phone_account"] = args.phone_account

    try:
        payload = _call_tool(settings, "place_call", arguments, timeout=70.0)
    except CliError as exc:
        raise CliError(f"{exc} (request_id={request_id})") from None
    if isinstance(payload, dict) and "request_id" not in payload:
        payload = {"request_id": request_id, **payload}
    _print_json(payload)


def _auth_summary(auth: Any, credentials: Any) -> dict[str, Any]:
    """Describe a credential without exposing any token material."""
    from .auth import CodexCredentials

    if not isinstance(credentials, CodexCredentials):
        raise CliError("auth returned an unexpected credential type")
    return {
        "authenticated": True,
        "path": str(auth.path),
        "account_id": credentials.account_id,
        "plan": credentials.plan,
        "residency": credentials.residency,
        "expires_at": credentials.expires_at,
        "usable": credentials.usable(),
    }


def _cmd_auth(args: argparse.Namespace) -> None:
    from .auth import CodexAuth

    auth = CodexAuth()

    def show_url(url: str) -> None:
        print(f"phonxiety: open this URL to sign in to Codex:\n{url}", file=sys.stderr)

    def show_code(url: str, code: str) -> None:
        print(
            f"phonxiety: open {url} and enter the code {code}",
            file=sys.stderr,
        )

    async def run() -> Any:
        if args.status:
            return await auth.credentials()
        if args.device:
            return await auth.login_device(on_code=show_code)
        return await auth.login(on_url=show_url)

    try:
        credentials = asyncio.run(run())
    except Exception as exc:
        raise CliError(f"Codex authentication failed: {_short(exc)}") from None
    _print_json(_auth_summary(auth, credentials))


def _cmd_result(args: argparse.Namespace) -> None:
    settings = _settings()
    payload = _call_tool(
        settings,
        "wait_call_result",
        {
            "call_id": args.call_id,
            "wait_s": args.wait_s,
            "cursor": args.cursor,
            "limit": args.limit,
        },
        timeout=max(70.0, args.wait_s + 15.0),
    )
    _print_json(payload)


def _cmd_hangup(args: argparse.Namespace) -> None:
    settings = _settings()
    payload = _call_tool(
        settings,
        "hangup_call",
        {"call_id": args.call_id, "reason": args.reason},
        timeout=70.0,
    )
    _print_json(payload)


def _primary_address() -> str:
    """Address of the interface that carries the default route; no packet is sent."""
    import socket

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            return probe.getsockname()[0]
    except OSError:
        raise CliError("cannot detect this computer's network address; pass --host") from None


def _tailscale_address() -> str | None:
    """IPv4 address of this computer in its tailnet while Tailscale is connected."""
    import ipaddress
    import subprocess

    from .net.tailnet import tailscale_binary

    binary = tailscale_binary()
    if binary is None:
        return None
    try:
        result = subprocess.run([binary, "ip", "-4"], capture_output=True, text=True, timeout=5)
        return str(ipaddress.IPv4Address(result.stdout.split()[0])) if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired, IndexError, ValueError):
        return None


# Past this version the QR has too many modules to scan comfortably from a terminal.
PAIRING_QR_MAX_VERSION = 20


def _qr_version(payload: str) -> int:
    import segno

    try:
        return segno.make_qr(payload, error="l").version
    except segno.DataOverflowError:
        # Too long for any QR; the caller trims addresses until it fits.
        return PAIRING_QR_MAX_VERSION + 1


def _pairing_payload(
    base: dict[str, str],
    certificate: str | None,
    addresses: list[tuple[str, str]],
) -> str:
    """The QR link: the pairing itself, its certificate pin and the extra addresses."""
    from urllib.parse import urlencode

    pairs: list[tuple[str, str]] = list(base.items())
    if certificate:
        pairs.append(("cert", certificate))
    pairs.extend(addresses)
    return "phonxiety://pair?" + urlencode(pairs)


def _fitted_pairing_payload(
    base: dict[str, str],
    certificate: str | None,
    lan: list[str],
    tailscale: list[str],
) -> tuple[str, tuple[int, int], tuple[int, int]]:
    """The payload whose QR stays scannable, with the least useful addresses dropped first.

    Returns the payload, its untrimmed ``(bytes, version)`` and the final one.
    LAN addresses score above tailnet ones, so the tailnet tail goes first.
    """
    lan_urls = list(lan)
    tailscale_urls = list(tailscale)

    def build() -> str:
        return _pairing_payload(
            base,
            certificate,
            [("lan", url) for url in lan_urls] + [("tailscale", url) for url in tailscale_urls],
        )

    payload = build()
    version = _qr_version(payload)
    before = (len(payload), version)
    while version > PAIRING_QR_MAX_VERSION and (lan_urls or tailscale_urls):
        if tailscale_urls:
            tailscale_urls.pop()
        else:
            lan_urls.pop()
        payload = build()
        version = _qr_version(payload)
    return payload, before, (len(payload), version)


def _render_pairing_qr(payload: str) -> None:
    import segno

    segno.make_qr(payload, error="l").terminal(compact=True)


def _address_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str) and item.startswith("wss://")]


def _cmd_pair(args: argparse.Namespace) -> None:
    settings = _settings()
    qr_report: tuple[tuple[int, int], tuple[int, int]] | None = None
    if args.host or args.lan or args.tailscale:
        from .net.tls import device_identity

        _, _, fingerprint = device_identity(settings.state_dir)
        if args.tailscale:
            host = _tailscale_address()
            if host is None:
                raise CliError("Tailscale is not connected on this computer")
        else:
            host = args.host or _primary_address()
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"
        url = f"wss://{host}:{settings.device_port}/device"
        config = {
            "url": url,
            "token": settings.device_token,
            "app_token": settings.app_token,
            "cert_sha256": fingerprint,
        }
        query = {
            "url": url,
            "token": settings.device_token,
            "app_token": settings.app_token,
            "cert": fingerprint,
        }
        payload = _pairing_payload(query, None, [])
        summary = [f"Daemon address:      {url}", f"Certificate SHA-256: {fingerprint}"]
    else:
        _ensure_daemon(settings)
        try:
            pairing = _daemon_get(settings, "/pairing", timeout=15.0)
        except CliError as exc:
            raise CliError(
                f"{exc}; pair by address instead with --lan, --tailscale or --host"
            ) from None
        ticket = pairing.get("iroh") if isinstance(pairing, dict) else None
        if not isinstance(ticket, str) or not ticket:
            raise CliError("daemon returned no pairing ticket")
        app_token = pairing.get("app_token") if isinstance(pairing, dict) else None
        if not isinstance(app_token, str) or not app_token:
            app_token = settings.app_token
        certificate = pairing.get("certificate") if isinstance(pairing, dict) else None
        if not isinstance(certificate, str) or not certificate:
            certificate = None
        lan = _address_list(pairing.get("lan") if isinstance(pairing, dict) else None)
        tailscale = _address_list(pairing.get("tailscale") if isinstance(pairing, dict) else None)
        base = {"iroh": ticket, "token": settings.device_token, "app_token": app_token}
        config = dict(base)
        if certificate:
            config["cert_sha256"] = certificate
        if lan:
            config["lan"] = lan
        if tailscale:
            config["tailscale"] = tailscale
        payload, before, after = _fitted_pairing_payload(base, certificate, lan, tailscale)
        qr_report = (before, after)
        summary = [
            f"Daemon endpoint: {pairing.get('endpoint_id')}",
            "The phone dials this id over iroh: no address, no VPN app.",
        ]
    if args.json:
        print(json.dumps(config, separators=(",", ":")))
        return

    _render_pairing_qr(payload)
    print("\n".join(summary))
    if qr_report is not None:
        before, after = qr_report
        print(f"QR payload: {before[0]} bytes, version {before[1]}")
        if after != before:
            print(f"QR payload after trimming: {after[0]} bytes, version {after[1]}")
    print("Scan the code in the Phonxiety app. It carries the phone credential: do not share it.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="phonxiety",
        description="Place and observe real cellular calls through phonxietyd.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    daemon = subparsers.add_parser(
        "daemon", help="run phonxietyd in the foreground"
    )
    daemon.set_defaults(func=_cmd_daemon)

    start = subparsers.add_parser(
        "start", help="start phonxietyd in the background unless it already runs"
    )
    start.set_defaults(func=_cmd_start)

    stop = subparsers.add_parser("stop", help="stop the running phonxietyd")
    stop.set_defaults(func=_cmd_stop)

    mcp = subparsers.add_parser(
        "mcp", help="run the stdio MCP adapter; starts the daemon when none runs"
    )
    mcp.set_defaults(func=_cmd_mcp)

    status = subparsers.add_parser("status", help="show the daemon, phone and link state")
    status.add_argument("--json", action="store_true", help="print the raw health document")
    status.set_defaults(func=_cmd_status)

    link_test = subparsers.add_parser(
        "link-test", help="load the phone link like a call, without dialing, and time it"
    )
    link_test.add_argument("--seconds", type=float, default=5.0, help="test length, 1 to 30 (default 5)")
    link_test.add_argument(
        "--codec", choices=("pcm", "opus"),
        help="size the test frames for this format instead of the one a call would use",
    )
    link_test.add_argument(
        "--datagrams", action="store_true",
        help="send the probe frames as QUIC datagrams instead of on the audio stream (iroh only)",
    )
    link_test.add_argument("--json", action="store_true", help="print the raw measurements")
    link_test.set_defaults(func=_cmd_link_test)

    auth = subparsers.add_parser(
        "auth",
        help="sign in to Codex or show credential status",
    )
    auth_modes = auth.add_mutually_exclusive_group()
    auth_modes.add_argument(
        "--status",
        action="store_true",
        help="load and describe the current credentials without signing in",
    )
    auth_modes.add_argument(
        "--device",
        action="store_true",
        help="use the headless device-code login instead of a browser",
    )
    auth.set_defaults(func=_cmd_auth)

    call = subparsers.add_parser("call", help="place an outbound call")
    call.add_argument(
        "--request-id",
        help="stable idempotency key; repeat it to retry without redialing",
    )
    call.add_argument(
        "--destination", required=True,
        help="E.164 number (+14155550123) or a 3-6 digit service short number; emergency numbers are refused",
    )
    call.add_argument("--goal", required=True, help="what the voice agent must accomplish")
    call.add_argument("--context", default="", help="minimal call brief")
    call.add_argument(
        "--constraints", default="", help="allowed dates, budget, alternatives, disclosure"
    )
    call.add_argument("--max-duration-s", type=int, default=900)
    call.add_argument("--ring-timeout-s", type=int, default=60)
    call.add_argument(
        "--result-field",
        action="append",
        default=[],
        help="field name the agent should confirm; repeat for multiple fields",
    )
    call.add_argument("--phone-account", default=None, help="Android PhoneAccountHandle id")
    call.set_defaults(func=_cmd_call)

    result = subparsers.add_parser(
        "result", help="read a call snapshot and transcript page"
    )
    result.add_argument("call_id")
    result.add_argument("--wait-s", type=float, default=30.0)
    result.add_argument("--cursor", type=int, default=0)
    result.add_argument("--limit", type=int, default=100)
    result.set_defaults(func=_cmd_result)

    hangup = subparsers.add_parser("hangup", help="request termination of a call")
    hangup.add_argument("call_id")
    hangup.add_argument("--reason", default="requested")
    hangup.set_defaults(func=_cmd_hangup)

    pair = subparsers.add_parser(
        "pair", help="show the QR code that pairs the phone app with this daemon"
    )
    pair_host = pair.add_mutually_exclusive_group()
    pair_host.add_argument(
        "--lan",
        action="store_true",
        help="pair by this computer's local network address instead of its iroh endpoint id",
    )
    pair_host.add_argument(
        "--tailscale",
        action="store_true",
        help="pair by this computer's Tailscale address",
    )
    pair_host.add_argument(
        "--host",
        help="pair by this address or name (for another tunnel or a port forward)",
    )
    pair.add_argument(
        "--json",
        action="store_true",
        help="print the phone configuration as JSON instead of a QR code",
    )
    pair.set_defaults(func=_cmd_pair)

    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except CliError as exc:
        print(f"phonxiety: {exc}", file=sys.stderr)
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        print("phonxiety: interrupted", file=sys.stderr)
        raise SystemExit(130) from None
    except Exception as exc:
        print(f"phonxiety: {type(exc).__name__}: {_short(exc)}", file=sys.stderr)
        raise SystemExit(1) from None


__all__ = ["build_parser", "main"]
