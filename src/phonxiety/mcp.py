"""Short-lived stdio MCP adapter for phonxietyd.

The daemon owns calls and outlives this adapter. When no daemon answers, the
adapter starts one detached from its own process group, so a harness only needs
this command in its MCP configuration and closing the harness never ends a call.
"""

from __future__ import annotations

import asyncio
import logging
import subprocess
import sys
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .settings import Settings

EXPECTED_TOOLS = frozenset({"place_call", "wait_call_result", "hangup_call"})
START_TIMEOUT_S = 30.0
LOG_NAME = "daemon.log"


def _endpoint(settings: Settings) -> str:
    return settings.base_url.rstrip("/") + "/mcp"


def _short_error(exc: BaseException) -> str:
    text = " ".join(str(exc).split())
    if not text:
        text = type(exc).__name__
    return text[:500]


def daemon_answers(settings: Settings) -> bool | None:
    """True when our daemon answers, None when nothing listens, False when something else does."""
    import httpx

    try:
        response = httpx.get(
            settings.base_url.rstrip("/") + "/health",
            headers={"Authorization": f"Bearer {settings.host_token}"},
            timeout=2.0,
            follow_redirects=False,
            trust_env=False,
        )
    except httpx.ConnectError:
        return None
    except httpx.HTTPError:
        return False
    return response.status_code == 200


def ensure_daemon(settings: Settings) -> None:
    """Start a detached daemon unless one already answers; raise when it does not come up."""
    state = daemon_answers(settings)
    if state:
        return
    if state is False:
        raise RuntimeError(
            f"{settings.host}:{settings.port} answers, but not as this installation's daemon "
            "(another program on the port, or a different host token)"
        )
    log_path = settings.state_dir / LOG_NAME
    with open(log_path, "ab") as log:
        process = subprocess.Popen(
            [sys.executable, "-c", "from phonxiety.server import main; main()"],
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=log,
            start_new_session=True,
        )
    deadline = time.monotonic() + START_TIMEOUT_S
    exited = False
    while time.monotonic() < deadline:
        if daemon_answers(settings):
            return
        if not exited and process.poll() is not None:
            # Ours gave up; a daemon started by someone else at the same moment may still answer.
            exited = True
            deadline = min(deadline, time.monotonic() + 5)
        time.sleep(0.25)
    raise RuntimeError(f"the daemon did not come up; see {log_path}")


async def _preflight(url: str, token: str) -> None:
    """Verify the daemon's MCP endpoint is live and advertises our tools."""
    from fastmcp import Client
    from fastmcp.client.auth import BearerAuth

    client = Client(
        url,
        auth=BearerAuth(token),
        timeout=10.0,
        init_timeout=10.0,
    )
    async with client:
        tools = await client.list_tools()
    names = {tool.name for tool in tools}
    missing = sorted(EXPECTED_TOOLS - names)
    if missing:
        raise RuntimeError(
            "daemon MCP endpoint is missing required tools: " + ", ".join(missing)
        )


def main() -> None:
    """Serve the daemon's three tools over this process's stdio."""
    logging.basicConfig(
        level=logging.WARNING,
        stream=sys.stderr,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        force=True,
    )

    from .settings import Settings

    settings = Settings.from_env()
    url = _endpoint(settings)

    try:
        ensure_daemon(settings)
        asyncio.run(_preflight(url, settings.host_token))
    except Exception as exc:
        print(
            "phonxiety-mcp: cannot use the phonxiety daemon at "
            f"{settings.host}:{settings.port}: {_short_error(exc)}",
            file=sys.stderr,
        )
        raise SystemExit(2) from None

    from fastmcp import Client
    from fastmcp.client.auth import BearerAuth
    from fastmcp.server import create_proxy

    client = Client(
        url,
        auth=BearerAuth(settings.host_token),
        timeout=70.0,
        init_timeout=10.0,
    )
    proxy = create_proxy(
        client,
        name="phonxiety",
        provider_error_strategy="raise",
    )
    # stdio uses stdout for MCP traffic; no banner or logging may be written there.
    proxy.run(transport="stdio", show_banner=False)


__all__ = ["daemon_answers", "ensure_daemon", "main"]
