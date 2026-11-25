"""Dedicated Codex OAuth login, refresh, and atomic credential persistence."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import secrets
import time
import webbrowser
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urlsplit

import httpx

logger = logging.getLogger(__name__)

# Values pinned from the Codex OAuth definition at OMP commit
# 230e7494405bee0188c9597d3c4647828a0824ba
# (packages/catalog/src/compat/rules/auth/openai-codex.kdl).
CODEX_OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
CODEX_AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
CODEX_TOKEN_URL = "https://auth.openai.com/oauth/token"
CODEX_SCOPE = (
    "openid profile email offline_access api.connectors.read api.connectors.invoke"
)
CODEX_REDIRECT_URI = "http://localhost:1455/auth/callback"
CODEX_CALLBACK_HOST = "localhost"
CODEX_CALLBACK_PORT = 1455
CODEX_CALLBACK_PATH = "/auth/callback"
CODEX_DEVICE_USERCODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
CODEX_DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
CODEX_DEVICE_REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"
CODEX_DEVICE_AUTH_URL = "https://auth.openai.com/codex/device"

CODEX_BASE_URL = "https://chatgpt.com/backend-api"
# Pinned client version from packages/catalog/src/wire/codex.ts.  The Codex
# backend version-gates model availability against this value.
CODEX_CLIENT_VERSION = "0.169.0"
CODEX_ORIGINATOR = "Codex Desktop"

JWT_CLAIM_PATH = "https://api.openai.com/auth"
JWT_PROFILE_CLAIM = "https://api.openai.com/profile"

TOKEN_TIMEOUT_S = 15.0
REFRESH_SKEW_S = 120.0
REFRESH_OPERATION_TIMEOUT_S = 20.0
LOGIN_TIMEOUT_S = 300.0
DEVICE_POLL_INTERVAL_S = 5.0
DEVICE_MAX_POLLS = 120
MAX_ERROR_BODY = 2048


class CodexAuthError(RuntimeError):
    """Base class for credential failures; messages never contain tokens."""


class CodexAuthMissing(CodexAuthError):
    """No dedicated phonxiety OAuth login is available."""


class CodexAuthExpired(CodexAuthError):
    """The stored refresh token is no longer usable; login is required."""


class CodexAuthRefreshError(CodexAuthError):
    """The token endpoint rejected or failed a refresh request."""


class CodexLoginError(CodexAuthError):
    """Interactive login could not be started or completed."""


@dataclass(frozen=True, slots=True)
class CodexCredentials:
    """A usable Codex OAuth credential.

    Token fields are excluded from ``repr`` so an accidental log line cannot
    leak them.
    """

    access_token: str = field(repr=False)
    account_id: str | None = None
    expires_at: float | None = None
    refresh_token: str = field(default="", repr=False)
    email: str | None = None
    plan: str | None = None
    residency: str | None = None
    id_token: str = field(default="", repr=False)

    @property
    def expired(self) -> bool:
        return self.expires_at is not None and self.expires_at <= time.time()

    def usable(self, skew_s: float = REFRESH_SKEW_S) -> bool:
        return self.expires_at is None or self.expires_at > time.time() + skew_s


def default_auth_path() -> Path:
    """Dedicated credential file, never the Codex CLI's own file."""
    configured = os.environ.get("PHONXIETY_CODEX_AUTH_FILE", "").strip()
    if configured:
        return Path(configured).expanduser()
    state_home = os.environ.get("XDG_STATE_HOME", "").strip()
    base = Path(state_home).expanduser() if state_home else Path.home() / ".local" / "state"
    return base / "phonxiety" / "auth.json"


def codex_cli_auth_path() -> Path:
    codex_home = os.environ.get("CODEX_HOME", "").strip()
    base = Path(codex_home).expanduser() if codex_home else Path.home() / ".codex"
    return base / "auth.json"


def _b64url_decode(value: str) -> bytes:
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def decode_jwt_payload(token: str) -> dict[str, Any]:
    """Decode a JWT payload without verifying it (claims only)."""
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return {}
        payload = json.loads(_b64url_decode(parts[1]))
        return payload if isinstance(payload, dict) else {}
    except (ValueError, TypeError, json.JSONDecodeError):
        return {}


def credential_identity(
    access_token: str, id_token: str | None = None
) -> tuple[str | None, str | None, str | None, str | None, float | None]:
    """Return account id, email, plan, residency and JWT expiry."""
    payload = decode_jwt_payload(access_token)
    id_payload = decode_jwt_payload(id_token) if id_token else {}
    auth = payload.get(JWT_CLAIM_PATH) or {}
    id_auth = id_payload.get(JWT_CLAIM_PATH) or {}
    profile = payload.get(JWT_PROFILE_CLAIM) or {}
    id_profile = id_payload.get(JWT_PROFILE_CLAIM) or {}

    account_id = auth.get("chatgpt_account_id") or id_auth.get("chatgpt_account_id")
    email = profile.get("email") or id_profile.get("email")
    plan = auth.get("chatgpt_plan_type") or id_auth.get("chatgpt_plan_type")
    residency = auth.get("chatgpt_data_residency") or auth.get("chatgpt_compute_residency")
    expires = payload.get("exp")

    if isinstance(account_id, str) and not account_id.strip():
        account_id = None
    if isinstance(email, str):
        email = email.strip().lower() or None
    if isinstance(plan, str):
        plan = plan.strip().lower() or None
    if isinstance(residency, str):
        residency = residency.strip()
        if not residency or residency.lower() == "no_constraint":
            residency = None
    if not isinstance(expires, (int, float)):
        expires = None
    elif expires > 1e12:  # tolerate millisecond JWT claims
        expires = expires / 1000.0
    return (
        account_id if isinstance(account_id, str) else None,
        email if isinstance(email, str) else None,
        plan if isinstance(plan, str) else None,
        residency if isinstance(residency, str) else None,
        float(expires) if expires is not None else None,
    )


def _bounded_error_body(text: str, status_text: str = "") -> str:
    normalized = " ".join(text.split())
    if not normalized:
        return status_text or "empty response body"
    if len(normalized) <= MAX_ERROR_BODY:
        return normalized
    return normalized[:MAX_ERROR_BODY] + "…"


def _token_error_detail(body: str, status: int) -> str:
    """Format a token endpoint error without echoing credential material."""
    try:
        parsed = json.loads(body)
    except (ValueError, TypeError):
        return f"{status} {_bounded_error_body(body)}"
    if not isinstance(parsed, dict):
        return f"{status} {_bounded_error_body(body)}"
    error = parsed.get("error")
    description = parsed.get("error_description")
    if isinstance(error, dict):
        error = error.get("code") or error.get("message")
    if isinstance(description, dict):
        description = description.get("message") or description.get("description")
    if isinstance(error, str) and isinstance(description, str) and error != description:
        return f"{status} {error}: {description}"
    if isinstance(error, str):
        return f"{status} {error}"
    if isinstance(description, str):
        return f"{status} {description}"
    return f"{status} {_bounded_error_body(body)}"


def _pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    challenge = (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest())
        .rstrip(b"=")
        .decode("ascii")
    )
    return verifier, challenge


def _authorization_url(state: str, challenge: str, originator: str) -> str:
    query = urlencode(
        {
            "response_type": "code",
            "client_id": CODEX_OAUTH_CLIENT_ID,
            "redirect_uri": CODEX_REDIRECT_URI,
            "scope": CODEX_SCOPE,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            "id_token_add_organizations": "true",
            "codex_cli_simplified_flow": "true",
            "originator": originator,
        }
    )
    return f"{CODEX_AUTHORIZE_URL}?{query}"


def _token_response_credentials(
    data: dict[str, Any], *, previous_refresh: str = ""
) -> CodexCredentials:
    access = data.get("access_token")
    refresh = data.get("refresh_token") or previous_refresh
    expires_in = data.get("expires_in")
    if not isinstance(access, str) or not access:
        raise CodexAuthError("token response did not contain an access token")
    if not isinstance(refresh, str) or not refresh:
        raise CodexAuthError("token response did not contain a refresh token")
    if not isinstance(expires_in, (int, float)):
        raise CodexAuthError("token response did not contain expires_in")
    id_token = data.get("id_token")
    id_token = id_token if isinstance(id_token, str) else ""
    account_id, email, plan, residency, jwt_expires = credential_identity(
        access, id_token or None
    )
    expires_at = time.time() + float(expires_in)
    if jwt_expires is not None:
        expires_at = min(expires_at, jwt_expires)
    return CodexCredentials(
        access_token=access,
        account_id=account_id,
        expires_at=expires_at,
        refresh_token=refresh,
        email=email,
        plan=plan,
        residency=residency,
        id_token=id_token,
    )


def codex_request_headers(
    credentials: CodexCredentials,
    *,
    session_id: str,
    thread_id: str | None = None,
    realtime_session_id: str | None = None,
    attestation: str | None = None,
    routing_model: str | None = None,
    client_version: str = CODEX_CLIENT_VERSION,
    originator: str = CODEX_ORIGINATOR,
    user_agent: str | None = None,
) -> dict[str, str]:
    """Headers shared by Codex signaling, sideband and Responses requests."""
    headers = {
        "Authorization": f"Bearer {credentials.access_token}",
        "originator": originator,
        "version": client_version,
        "User-Agent": user_agent or f"{originator}/{client_version}",
        "session-id": session_id,
        "thread-id": thread_id or session_id,
    }
    if realtime_session_id:
        headers["x-session-id"] = realtime_session_id
    if credentials.account_id:
        headers["chatgpt-account-id"] = credentials.account_id
    if credentials.residency:
        headers["x-openai-internal-codex-residency"] = credentials.residency
    if routing_model:
        headers["x-codex-routing-hint"] = f"model={routing_model}"
    if attestation:
        headers["x-oai-attestation"] = attestation
    return headers


class CodexAuth:
    """Loads, refreshes and persists the daemon's Codex credentials."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = Path(path).expanduser() if path is not None else default_auth_path()
        self._lock = asyncio.Lock()
        self._credentials: CodexCredentials | None = None
        self._file_mtime: float | None = None
        self._last_refresh_at: float | None = None

    # -- state ---------------------------------------------------------

    def _read_file(self, path: Path) -> CodexCredentials:
        try:
            raw = path.read_text(encoding="utf-8")
            parsed = json.loads(raw)
        except FileNotFoundError as exc:
            raise CodexAuthMissing(f"no Codex credentials at {path}") from exc
        except (OSError, ValueError) as exc:
            raise CodexAuthError(f"cannot read Codex credentials at {path}: {exc}") from exc
        return self._parse_auth(parsed, path)

    def _parse_auth(self, parsed: Any, path: Path) -> CodexCredentials:
        if not isinstance(parsed, dict):
            raise CodexAuthError(f"credential file at {path} is not a JSON object")
        tokens = parsed.get("tokens")
        if not isinstance(tokens, dict):
            raise CodexAuthError(f"credential file at {path} has no tokens object")
        access = tokens.get("access_token")
        refresh = tokens.get("refresh_token")
        if not isinstance(access, str) or not access:
            raise CodexAuthError(f"credential file at {path} has no access token")
        if not isinstance(refresh, str) or not refresh:
            raise CodexAuthError(f"credential file at {path} has no refresh token")
        id_token = tokens.get("id_token")
        id_token = id_token if isinstance(id_token, str) else ""
        account_id = tokens.get("account_id")
        account_id = account_id if isinstance(account_id, str) and account_id else None
        jwt_account, email, plan, residency, expires_at = credential_identity(
            access, id_token or None
        )
        return CodexCredentials(
            access_token=access,
            account_id=account_id or jwt_account,
            expires_at=expires_at,
            refresh_token=refresh,
            email=email,
            plan=plan,
            residency=residency,
            id_token=id_token,
        )

    def _check_owned_path(self) -> None:
        if self.path.resolve() == codex_cli_auth_path().resolve():
            raise CodexAuthError(
                "PHONXIETY_CODEX_AUTH_FILE must be a daemon-owned path; run `phonxiety auth` "
                "to create an independent login"
            )

    def _write_file(self, credentials: CodexCredentials) -> None:
        path = self.path
        self._check_owned_path()
        if not path.parent.exists():
            path.parent.mkdir(mode=0o700, parents=True)
            try:
                path.parent.chmod(0o700)
            except OSError:
                pass
        else:
            path.parent.mkdir(parents=True, exist_ok=True)

        existing: dict[str, Any] = {}
        if path.exists():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    existing = loaded
            except (OSError, ValueError):
                existing = {}

        tokens = existing.get("tokens")
        tokens = dict(tokens) if isinstance(tokens, dict) else {}
        tokens.update(
            {
                "access_token": credentials.access_token,
                "refresh_token": credentials.refresh_token,
            }
        )
        if credentials.account_id:
            tokens["account_id"] = credentials.account_id
        if credentials.id_token:
            tokens["id_token"] = credentials.id_token
        elif "id_token" not in tokens:
            tokens["id_token"] = ""

        existing.update(
            {
                "auth_mode": "chatgpt",
                "OPENAI_API_KEY": None,
                "tokens": tokens,
                "last_refresh": time.strftime(
                    "%Y-%m-%dT%H:%M:%SZ", time.gmtime()
                ),
            }
        )

        payload = json.dumps(existing, ensure_ascii=False, separators=(",", ":"))
        tmp = path.with_name(f".{path.name}.{os.getpid()}.{secrets.token_hex(4)}.tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp, path)
            os.chmod(path, 0o600)
        except BaseException:
            try:
                tmp.unlink()
            except OSError:
                pass
            raise
        self._file_mtime = path.stat().st_mtime

    def _remember(self, credentials: CodexCredentials) -> CodexCredentials:
        self._credentials = credentials
        try:
            self._file_mtime = self.path.stat().st_mtime
        except OSError:
            self._file_mtime = None
        return credentials

    # -- public API ----------------------------------------------------


    async def credentials(self) -> CodexCredentials:
        """Return usable credentials from the dedicated login."""
        self._check_owned_path()
        async with self._lock:
            cached = self._credentials
            if cached is not None and cached.usable():
                try:
                    mtime = self.path.stat().st_mtime
                except OSError:
                    return cached
                if self._file_mtime is None or mtime == self._file_mtime:
                    return cached
                # Prefer credentials replaced by a separate phonxiety login.
                cached = None

            loaded: CodexCredentials | None = None
            if self.path.exists():
                loaded = self._read_file(self.path)
            else:
                raise CodexAuthMissing("no phonxiety Codex login; run `phonxiety auth`")

            if loaded.usable():
                return self._remember(loaded)
            return await self._refresh_locked(loaded, reason="proactive_expiry")

    async def force_refresh(
        self, rejected_access_token: str | None = None
    ) -> CodexCredentials:
        """Refresh after a provider 401, avoiding a redundant rotation."""
        self._check_owned_path()
        async with self._lock:
            current = self._credentials
            if current is None:
                if self.path.exists():
                    current = self._read_file(self.path)
                else:
                    return await self._refresh_locked(
                        self._read_file(self.path), reason="unauthorized_recovery"
                    )
            if (
                rejected_access_token is not None
                and current.access_token != rejected_access_token
                and current.usable()
            ):
                return current
            if (
                self._last_refresh_at is not None
                and time.monotonic() - self._last_refresh_at < 5.0
                and current.usable()
            ):
                return current
            return await self._refresh_locked(current, reason="unauthorized_recovery")

    # -- refresh -------------------------------------------------------

    async def _refresh_locked(
        self, current: CodexCredentials, *, reason: str
    ) -> CodexCredentials:
        if not current.refresh_token:
            raise CodexAuthExpired(
                "the stored Codex login has no refresh token; run "
                "`phonxiety auth` (device-code login is also available)"
            )
        form = {
            "grant_type": "refresh_token",
            "client_id": CODEX_OAUTH_CLIENT_ID,
            "refresh_token": current.refresh_token,
            "refresh_reason": reason,
        }
        timeout = httpx.Timeout(TOKEN_TIMEOUT_S)
        try:
            async with asyncio.timeout(REFRESH_OPERATION_TIMEOUT_S):
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(
                        CODEX_TOKEN_URL,
                        data=form,
                        headers={"Content-Type": "application/x-www-form-urlencoded"},
                    )
                    body = response.text
        except TimeoutError as exc:
            raise CodexAuthRefreshError(
                "Codex token refresh timed out"
            ) from exc
        except httpx.HTTPError as exc:
            raise CodexAuthRefreshError(
                f"Codex token refresh failed: {type(exc).__name__}"
            ) from exc

        if response.status_code >= 400:
            detail = _token_error_detail(body, response.status_code)
            if response.status_code in (400, 401, 403):
                raise CodexAuthExpired(
                    f"the Codex login can no longer be refreshed ({detail}); "
                    "run `phonxiety auth` (device-code login is also available)"
                )
            raise CodexAuthRefreshError(
                f"Codex token refresh failed ({detail})"
            )

        try:
            data = response.json()
        except ValueError as exc:
            raise CodexAuthRefreshError(
                "Codex token refresh returned non-JSON content"
            ) from exc
        if not isinstance(data, dict):
            raise CodexAuthRefreshError(
                "Codex token refresh returned an unexpected payload"
            )
        try:
            refreshed = _token_response_credentials(
                data, previous_refresh=current.refresh_token
            )
        except CodexAuthError as exc:
            raise CodexAuthRefreshError(str(exc)) from exc

        # A refresh endpoint may omit identity claims; keep the known account.
        if refreshed.account_id is None and current.account_id:
            refreshed = CodexCredentials(
                access_token=refreshed.access_token,
                account_id=current.account_id,
                expires_at=refreshed.expires_at,
                refresh_token=refreshed.refresh_token,
                email=refreshed.email or current.email,
                plan=refreshed.plan or current.plan,
                residency=refreshed.residency or current.residency,
                id_token=refreshed.id_token or current.id_token,
            )
        self._write_file(refreshed)
        self._last_refresh_at = time.monotonic()
        logger.info("refreshed Codex OAuth access token")
        return self._remember(refreshed)

    # -- login ---------------------------------------------------------

    async def login(
        self,
        *,
        on_url: Callable[[str], None] | None = None,
        timeout_s: float = LOGIN_TIMEOUT_S,
        originator: str = "phonxiety",
    ) -> CodexCredentials:
        """Run the browser PKCE login and persist the resulting credentials.

        ``on_url`` receives the authorization URL (which contains no
        credential) for headless terminals; the browser is opened as well.
        """
        verifier, challenge = _pkce_pair()
        state = secrets.token_urlsafe(24)
        url = _authorization_url(state, challenge, originator)

        loop = asyncio.get_running_loop()
        code_future: asyncio.Future[str] = loop.create_future()

        def _respond(
            writer: asyncio.StreamWriter, status: str, message: str
        ) -> None:
            body = (
                "<!doctype html><html><head><meta charset='utf-8'>"
                "<title>phonxiety Codex login</title></head><body>"
                f"<p>{message}</p></body></html>"
            ).encode("utf-8")
            head = (
                f"HTTP/1.1 {status}\r\n"
                "Content-Type: text/html; charset=utf-8\r\n"
                f"Content-Length: {len(body)}\r\n"
                "Connection: close\r\n\r\n"
            ).encode("ascii")
            writer.write(head + body)
            try:
                writer.close()
            except OSError:
                pass

        async def _handle(
            reader: asyncio.StreamReader, writer: asyncio.StreamWriter
        ) -> None:
            try:
                request = await reader.readuntil(b"\r\n\r\n")
                request_line = request.split(b"\r\n", 1)[0].decode(
                    "latin-1", "replace"
                )
                parts = request_line.split(" ")
                target = parts[1] if len(parts) >= 2 else ""
                split = urlsplit(target)
                if split.path != CODEX_CALLBACK_PATH:
                    _respond(writer, "404 Not Found", "Not found.")
                    return
                query = parse_qs(split.query)
                if query.get("error"):
                    error = query["error"][0]
                    description = (query.get("error_description") or [""])[0]
                    if not code_future.done():
                        code_future.set_exception(
                            CodexLoginError(
                                f"Codex login was not completed: {error}"
                                f"{f' ({description})' if description else ''}"
                            )
                        )
                    _respond(writer, "400 Bad Request", "Login was not completed.")
                    return
                returned_state = (query.get("state") or [""])[0]
                code = (query.get("code") or [""])[0]
                if returned_state != state:
                    if not code_future.done():
                        code_future.set_exception(
                            CodexLoginError("Codex login state did not match")
                        )
                    _respond(writer, "400 Bad Request", "Login state mismatch.")
                    return
                if not code:
                    if not code_future.done():
                        code_future.set_exception(
                            CodexLoginError("Codex login returned no code")
                        )
                    _respond(writer, "400 Bad Request", "Login returned no code.")
                    return
                if not code_future.done():
                    code_future.set_result(code)
                _respond(
                    writer,
                    "200 OK",
                    "Login complete. You can close this window and return to the terminal.",
                )
            except (asyncio.IncompleteReadError, ConnectionError):
                try:
                    writer.close()
                except OSError:
                    pass

        try:
            server = await asyncio.start_server(
                _handle, host=CODEX_CALLBACK_HOST, port=CODEX_CALLBACK_PORT
            )
        except OSError as exc:
            raise CodexLoginError(
                "cannot bind the Codex OAuth callback on localhost:1455 "
                f"({exc}); the OpenAI callback URI is fixed, so free the port and retry"
            ) from exc

        try:
            async with server:
                if on_url is not None:
                    on_url(url)
                try:
                    opened = await asyncio.to_thread(webbrowser.open, url)
                except Exception:
                    opened = False
                if not opened and on_url is None:
                    raise CodexLoginError(
                        "could not open a browser for Codex login; retry with "
                        "on_url=<callback that prints the URL>"
                    )
                try:
                    code = await asyncio.wait_for(code_future, timeout=timeout_s)
                except TimeoutError as exc:
                    raise CodexLoginError(
                        "Codex login timed out before the browser callback"
                    ) from exc
        finally:
            server.close()
            await server.wait_closed()

        credentials = await self._exchange_code(code, verifier, CODEX_REDIRECT_URI)
        async with self._lock:
            self._write_file(credentials)
            return self._remember(credentials)

    async def login_device(
        self,
        *,
        on_code: Callable[[str, str], None] | None = None,
        timeout_s: float = 600.0,
    ) -> CodexCredentials:
        """Headless device-code login; ``on_code(url, user_code)`` shows the code."""
        timeout = httpx.Timeout(TOKEN_TIMEOUT_S)
        try:
            async with asyncio.timeout(TOKEN_TIMEOUT_S):
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(
                        CODEX_DEVICE_USERCODE_URL,
                        json={"client_id": CODEX_OAUTH_CLIENT_ID},
                        headers={"Content-Type": "application/json"},
                    )
                    body = response.text
        except (TimeoutError, httpx.HTTPError) as exc:
            raise CodexLoginError(
                f"could not start Codex device login: {type(exc).__name__}"
            ) from exc
        if response.status_code >= 400:
            raise CodexLoginError(
                "could not start Codex device login "
                f"({response.status_code} {_bounded_error_body(body)})"
            )
        try:
            start = response.json()
        except ValueError as exc:
            raise CodexLoginError(
                "Codex device login returned non-JSON content"
            ) from exc
        if not isinstance(start, dict):
            raise CodexLoginError("Codex device login returned an unexpected payload")
        device_auth_id = start.get("device_auth_id")
        user_code = start.get("user_code")
        if not isinstance(device_auth_id, str) or not isinstance(user_code, str):
            raise CodexLoginError(
                "Codex device login did not return a device code"
            )
        interval = start.get("interval", DEVICE_POLL_INTERVAL_S)
        try:
            interval_s = max(1.0, float(interval))
        except (TypeError, ValueError):
            interval_s = DEVICE_POLL_INTERVAL_S
        if on_code is not None:
            on_code(CODEX_DEVICE_AUTH_URL, user_code)

        deadline = time.monotonic() + timeout_s
        authorization_code: str | None = None
        code_verifier: str | None = None
        async with httpx.AsyncClient(timeout=timeout) as client:
            for _ in range(DEVICE_MAX_POLLS):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise CodexLoginError("Codex device login timed out")
                await asyncio.sleep(min(interval_s, remaining))
                try:
                    poll = await client.post(
                        CODEX_DEVICE_TOKEN_URL,
                        json={
                            "device_auth_id": device_auth_id,
                            "user_code": user_code,
                        },
                        headers={"Content-Type": "application/json"},
                    )
                    poll_body = poll.text
                except httpx.HTTPError as exc:
                    raise CodexLoginError(
                        f"Codex device login polling failed: {type(exc).__name__}"
                    ) from exc
                if poll.status_code in (403, 404):
                    continue
                if poll.status_code >= 400:
                    raise CodexLoginError(
                        "Codex device login was rejected "
                        f"({poll.status_code} {_bounded_error_body(poll_body)})"
                    )
                try:
                    poll_data = poll.json()
                except ValueError as exc:
                    raise CodexLoginError(
                        "Codex device login polling returned non-JSON content"
                    ) from exc
                if not isinstance(poll_data, dict):
                    raise CodexLoginError(
                        "Codex device login polling returned an unexpected payload"
                    )
                candidate_code = poll_data.get("authorization_code")
                candidate_verifier = poll_data.get("code_verifier")
                if isinstance(candidate_code, str) and isinstance(
                    candidate_verifier, str
                ):
                    authorization_code = candidate_code
                    code_verifier = candidate_verifier
                    break
        if authorization_code is None or code_verifier is None:
            raise CodexLoginError("Codex device login did not complete")

        credentials = await self._exchange_code(
            authorization_code, code_verifier, CODEX_DEVICE_REDIRECT_URI
        )
        async with self._lock:
            self._write_file(credentials)
            return self._remember(credentials)

    async def _exchange_code(
        self, code: str, verifier: str, redirect_uri: str
    ) -> CodexCredentials:
        form = {
            "grant_type": "authorization_code",
            "client_id": CODEX_OAUTH_CLIENT_ID,
            "code": code,
            "code_verifier": verifier,
            "redirect_uri": redirect_uri,
        }
        timeout = httpx.Timeout(TOKEN_TIMEOUT_S)
        try:
            async with asyncio.timeout(TOKEN_TIMEOUT_S):
                async with httpx.AsyncClient(timeout=timeout) as client:
                    response = await client.post(
                        CODEX_TOKEN_URL,
                        data=form,
                        headers={"Content-Type": "application/x-www-form-urlencoded"},
                    )
                    body = response.text
        except (TimeoutError, httpx.HTTPError) as exc:
            raise CodexLoginError(
                f"Codex login token exchange failed: {type(exc).__name__}"
            ) from exc
        if response.status_code >= 400:
            raise CodexLoginError(
                "Codex login token exchange failed "
                f"({_token_error_detail(body, response.status_code)})"
            )
        try:
            data = response.json()
        except ValueError as exc:
            raise CodexLoginError(
                "Codex login token exchange returned non-JSON content"
            ) from exc
        if not isinstance(data, dict):
            raise CodexLoginError(
                "Codex login token exchange returned an unexpected payload"
            )
        try:
            return _token_response_credentials(data)
        except CodexAuthError as exc:
            raise CodexLoginError(f"Codex login failed: {exc}") from exc
