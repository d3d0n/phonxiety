"""phonxietyd: call controller, Android device link and local MCP endpoint.

The daemon owns all call state.  MCP and device requests only observe or
request actions through :class:`phonxiety.controller.CallController`; no call
work is scoped to a request.
"""

from __future__ import annotations

import asyncio
import logging
import secrets
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING, Annotated, Any

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from pydantic import Field, ValidationError

if TYPE_CHECKING:
    from starlette.applications import Starlette

    from .settings import Settings

LOGGER = logging.getLogger("phonxiety.server")

HEALTH_PATH = "/health"
PAIRING_PATH = "/pairing"
LINK_TEST_PATH = "/link-test"
MCP_PATH = "/mcp"


def _bearer_token(header: str | None) -> str | None:
    """Return the token from a single ``Authorization: Bearer`` header."""
    if not header:
        return None
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer":
        return None
    value = value.strip()
    return value or None


def _matches_token(header: str | None, expected: str) -> bool:
    """Compare a bearer token without leaking length or prefix information."""
    presented = _bearer_token(header)
    if presented is None or not expected:
        return False
    return secrets.compare_digest(presented.encode("utf-8"), expected.encode("utf-8"))


def _unauthorized() -> JSONResponse:
    return JSONResponse(
        {"error": "unauthorized"},
        status_code=401,
        headers={"WWW-Authenticate": "Bearer"},
    )


def _request_path(scope: Scope) -> str:
    """Return the application-relative request path."""
    path = scope.get("path", "")
    root_path = scope.get("root_path", "")
    if root_path and root_path != "/" and (
        path == root_path or path.startswith(root_path + "/")
    ):
        path = path[len(root_path) :] or "/"
    return path


class _HostBearerMiddleware:
    """Require the host bearer token on requests to the MCP sub-app."""

    def __init__(self, app: ASGIApp, token: str, prefix: str = MCP_PATH) -> None:
        self.app = app
        self.token = token
        self.prefix = prefix.rstrip("/")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = _request_path(scope)
        if path != self.prefix and not path.startswith(self.prefix + "/"):
            await self.app(scope, receive, send)
            return
        if not _matches_token(Headers(scope=scope).get("authorization"), self.token):
            await _unauthorized()(scope, receive, send)
            return
        await self.app(scope, receive, send)


def _validation_error_message(exc: Exception) -> str:
    """Condense a Pydantic validation error into a bounded, non-echoing line."""
    errors = getattr(exc, "errors", None)
    if not callable(errors):
        return "invalid call request"
    parts: list[str] = []
    try:
        raw_errors = errors()
    except Exception:
        return "invalid call request"
    for error in raw_errors[:5]:
        location = ".".join(str(item) for item in error.get("loc", ())) or "request"
        message = str(error.get("msg", "invalid value")).replace("\n", " ")
        parts.append(f"{location}: {message}")
    text = "invalid call request: " + "; ".join(parts)
    return text[:600]


def create_app(settings: Settings) -> Starlette:
    """Build the combined Starlette application for the daemon.

    The controller is created here but started and closed by the application
    lifespan, together with FastMCP's session manager.
    """
    from fastmcp import FastMCP
    from fastmcp.exceptions import ToolError
    from fastmcp.utilities.lifespan import combine_lifespans
    from starlette.applications import Starlette
    from starlette.middleware import Middleware
    from starlette.requests import Request
    from starlette.routing import Mount, Route

    from iroh import IrohError

    from .audio.opus import CODECS
    from .app_api import APP_API_VERSION, AppApi
    from .device import PROTOCOL_VERSION, DeviceError
    from .models import CallLimits, CallRequest
    from .net.iroh import IrohListener
    from .net.link import LinkUnavailable
    from .net.tls import device_identity, server_context
    from .net.tailnet import addresses_for
    from .net.tunnel import TunnelListener
    from .net.websocket import WebSocketListener
    from .runtime import create_controller

    controller = create_controller(settings)

    async def controller_result(name: str, awaiting: Any) -> Any:
        """Await a controller operation and keep MCP tool errors short."""
        try:
            return await awaiting
        except ToolError:
            raise
        except Exception as exc:
            detail = " ".join(str(exc).split()) or type(exc).__name__
            raise ToolError(f"{name} failed: {detail[:400]}") from exc

    mcp = FastMCP(
        name="phonxiety",
        instructions=(
            "Place and observe a real cellular call through a paired Android "
            "phone. Calls and their results belong to the phonxiety "
            "daemon: they continue after this MCP request or client ends. "
            "place_call is idempotent for a given request_id; wait_call_result "
            "is repeatable and does not consume the result; hangup_call only "
            "requests termination."
        ),
    )

    @mcp.tool
    async def place_call(
        request_id: str,
        destination: str,
        goal: str,
        context: str = "",
        constraints: str = "",
        max_duration_s: int = 900,
        ring_timeout_s: int = 60,
        result_fields: list[str] | None = None,
        phone_account: str | None = None,
    ) -> dict[str, Any]:
        """Ask the daemon to place one outbound call.

        Returns as soon as the intent is durably accepted, normally with
        ``call_id`` and ``state: "preparing"``.  It does not wait for the call
        to be answered or to finish.

        Args:
            request_id: Stable idempotency key. Repeating the same request_id
                with the same brief returns the existing call; a different
                brief is a conflict.
            destination: Already resolved E.164 number, for example +14155550123, or a 3-6 digit
                service short number such as a provider's call centre. Emergency numbers are refused.
            goal: What the voice agent should accomplish on the call.
            context: Minimal call brief, not the whole terminal history.
            constraints: Allowed dates, budget, alternatives and information
                the agent may disclose.
            max_duration_s: Maximum call duration in seconds (10..3600).
            ring_timeout_s: Maximum ringing time in seconds (5..180).
            result_fields: Field names the voice agent should confirm.
            phone_account: Optional Android PhoneAccountHandle id (SIM choice).
        """
        try:
            request = CallRequest(
                request_id=request_id,
                destination=destination,
                goal=goal,
                context=context,
                constraints=constraints,
                limits=CallLimits(
                    max_duration_s=max_duration_s,
                    ring_timeout_s=ring_timeout_s,
                ),
                result_fields=result_fields or [],
                phone_account=phone_account,
            )
        except ValidationError as exc:
            raise ToolError(_validation_error_message(exc)) from None
        return await controller_result("place_call", controller.place_call(request))

    @mcp.tool
    async def wait_call_result(
        call_id: str,
        wait_s: Annotated[float, Field(ge=0, le=45)] = 30.0,
        cursor: Annotated[int, Field(ge=0)] = 0,
        limit: Annotated[int, Field(ge=1, le=100)] = 100,
    ) -> dict[str, Any]:
        """Wait briefly for call state and read a transcript page.

        This is both the long-poll and the snapshot primitive.  A terminal
        result stays readable on later calls; reading never consumes it.
        ``wait_s=0`` returns immediately.  At most 45 seconds may be requested
        so that MCP client timeouts remain in control.  When the wait expires
        the current state is returned; that is not an error and does not
        terminate the call.

        Transcript pages contain immutable final segments only.  The separate
        snapshot field carries current partial speech and never advances the
        cursor.  ``next_cursor`` is the last final segment sequence in this
        page; pass it to read the next page.  ``transcript_complete`` means
        voice input has finished, not that this page exhausted the transcript.
        ``limit`` is bounded to 1..100 final segments per page.
        """
        if not call_id.strip():
            raise ToolError("call_id is required")
        return await controller_result(
            "wait_call_result",
            controller.wait_call_result(
                call_id,
                wait_s=wait_s,
                cursor=cursor,
                limit=limit,
            ),
        )

    @mcp.tool
    async def hangup_call(call_id: str, reason: str = "requested") -> dict[str, Any]:
        """Request termination of a call owned by this daemon.

        Idempotent: repeating it after termination returns the terminal state.
        ``ending`` acknowledges the request, not the phone's Telecom disconnect
        confirmation.  MCP request cancellation never triggers this action; it
        must be called explicitly.
        """
        if not call_id.strip():
            raise ToolError("call_id is required")
        return await controller_result(
            "hangup_call", controller.hangup_call(call_id, reason=reason)
        )

    mcp_app = mcp.http_app(path=MCP_PATH)

    async def health(request: Request) -> JSONResponse:
        if not _matches_token(request.headers.get("authorization"), settings.host_token):
            return _unauthorized()
        try:
            payload = controller.health()
        except Exception:
            LOGGER.exception("controller health check failed")
            return JSONResponse(
                {"status": "error", "error": "controller health check failed"},
                status_code=503,
            )
        if not isinstance(payload, dict):
            LOGGER.error("controller health returned %s, expected dict", type(payload).__name__)
            return JSONResponse(
                {"status": "error", "error": "invalid controller health payload"},
                status_code=503,
            )
        payload["link"] = {
            "endpoint_id": iroh_link.endpoint_id,
            "address_port": settings.device_port,
            "certificate": fingerprint,
            # `direct`/`relay` for the attached iroh connection; null when
            # disconnected, on another transport, or not yet known.
            "iroh_path": controller.device.iroh_path,
        }
        return JSONResponse(payload)

    async def pairing(request: Request) -> JSONResponse:
        if not _matches_token(request.headers.get("authorization"), settings.host_token):
            return _unauthorized()
        try:
            addresses = await asyncio.to_thread(addresses_for, settings.device_port)
            return JSONResponse({
                "iroh": await iroh_link.ticket(),
                "endpoint_id": iroh_link.endpoint_id,
                # The app's own credential: the QR carries it so the phone can call /app/v1.
                "app_token": app_api.app_token,
                "app_api": APP_API_VERSION,
                "certificate": fingerprint,
                "device_port": settings.device_port,
                # The other places this daemon can be reached; the phone stores them as candidates.
                "lan": addresses["lan"],
                "tailscale": addresses["tailscale"],
            })
        except LinkUnavailable:
            return JSONResponse({"error": "iroh_link_unavailable"}, status_code=503)

    async def link_test(request: Request) -> JSONResponse:
        if not _matches_token(request.headers.get("authorization"), settings.host_token):
            return _unauthorized()
        try:
            seconds = min(30.0, max(1.0, float(request.query_params.get("seconds", "5"))))
        except ValueError:
            return JSONResponse({"error": "invalid_seconds"}, status_code=400)
        codec = request.query_params.get("codec") or None
        if codec not in (None, *CODECS):
            return JSONResponse({"error": "invalid_codec"}, status_code=400)
        try:
            # Like a call, give a phone that is reconnecting time to arrive.
            await controller.device.wait_connected(15.0)
            datagrams = request.query_params.get("transport") == "datagram"
            return JSONResponse(await controller.device.link_test(seconds, codec=codec, datagrams=datagrams))
        except DeviceError as exc:
            return JSONResponse({"error": str(exc)}, status_code=409)

    cert_path, key_path, fingerprint = device_identity(settings.state_dir)
    device_tls = server_context(cert_path, key_path)
    address_link = WebSocketListener(
        controller.device,
        settings.device_token,
        certificate=fingerprint,
        protocol=PROTOCOL_VERSION,
    )
    iroh_link = IrohListener(controller.device, settings.device_token, settings.state_dir, settings.iroh_relays)
    adb_tunnel = TunnelListener(controller.device, "adb")

    def link_info() -> dict[str, Any]:
        return {
            "endpoint_id": iroh_link.endpoint_id,
            "address_port": settings.device_port,
            "certificate": fingerprint,
            "iroh_available": iroh_link.endpoint_id is not None,
        }

    app_api = AppApi(settings, controller, link_info=link_info)
    # The phone's app API and bulk streams arrive on the same authenticated link.
    controller.device.on_api = app_api.handle
    controller.device.on_attachment = app_api.serve_attachment
    controller.device.app_token_source = lambda: app_api.app_token

    @asynccontextmanager
    async def controller_lifespan(_app: Any) -> AsyncIterator[None]:
        await controller.start()
        try:
            await address_link.start(settings.device_host, settings.device_port, device_tls)
            LOGGER.info(
                "phone link listening on %s:%s (certificate %s)",
                settings.device_host, settings.device_port, fingerprint[:16],
            )
            try:
                await iroh_link.start()
                LOGGER.info("phone link endpoint %s", iroh_link.endpoint_id)
            except (IrohError, OSError) as exc:
                # The address link keeps working; pairing by endpoint id reports the failure.
                LOGGER.error("iroh phone link unavailable: %s", exc)
            try:
                await adb_tunnel.start("127.0.0.1", settings.adb_port)
                LOGGER.info("phone adb reachable at 127.0.0.1:%s (adb connect 127.0.0.1:%s)", settings.adb_port, settings.adb_port)
            except OSError as exc:
                LOGGER.error("adb tunnel port unavailable: %s", exc)
            yield
        finally:
            await controller.close()
            await adb_tunnel.close()
            await iroh_link.close()
            await address_link.close()
            # Last: a daemon started right after this one must find the ports free.
            controller.release()

    app = Starlette(
        routes=[
            Route(HEALTH_PATH, health, methods=["GET"]),
            Route(PAIRING_PATH, pairing, methods=["GET"]),
            Route(LINK_TEST_PATH, link_test, methods=["GET"]),
            Mount("/", app=mcp_app),
        ],
        middleware=[Middleware(_HostBearerMiddleware, token=settings.host_token)],
        lifespan=combine_lifespans(controller_lifespan, mcp_app.lifespan),
    )
    app.state.controller = controller
    app.state.settings = settings
    app.state.app_api = app_api
    return app


def _configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        stream=sys.stderr,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        force=True,
    )
    # Per-candidate ICE checks and per-connection handshakes bury the call events.
    for noisy in ("aioice", "websockets.server", "mcp.server.streamable_http_manager"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def main() -> None:
    """Run the daemon: uvicorn on loopback for MCP, iroh and TLS listeners for the phone."""
    _configure_logging()

    import uvicorn

    from .settings import Settings

    settings = Settings.from_env()
    app = create_app(settings)
    LOGGER.info("phonxietyd listening on %s:%s", settings.host, settings.port)
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_config=None,
        access_log=False,
    )


__all__ = ["create_app", "main"]
