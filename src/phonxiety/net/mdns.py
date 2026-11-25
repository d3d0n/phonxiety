"""mDNS advertisement of the daemon's address link.

The phone learns where the daemon's TLS listener is without the user typing an
address: the daemon publishes ``_phonxiety._tcp`` with the listener port in the
SRV record and, in TXT, the certificate pin it must be validated against and
the device protocol version.  mDNS is unauthenticated by nature, so the pin is
the only part a phone may trust, and only when it matches the pairing.

The advertisement is best-effort: no ``zeroconf`` installed, no multicast on the
network or a refused registration all mean the phone falls back to the paired
address, and nothing else changes.
"""

from __future__ import annotations

import logging
import socket
from typing import Any, Protocol

LOGGER = logging.getLogger(__name__)

SERVICE_TYPE = "_phonxiety._tcp.local."
TXT_CERT = "cert"
TXT_PROTOCOL = "proto"


class Backend(Protocol):
    """The two calls the advertiser makes; tests substitute a fake."""

    def register(self, info: Any) -> None: ...

    def unregister_all(self) -> None: ...

    def close(self) -> None: ...


class ZeroconfBackend:
    """A real multicast responder, imported only when an advertisement is started."""

    def __init__(self) -> None:
        from zeroconf import Zeroconf  # noqa: PLC0415 - optional dependency

        self._zeroconf = Zeroconf()

    def register(self, info: Any) -> None:
        self._zeroconf.register_service(info)

    def unregister_all(self) -> None:
        self._zeroconf.unregister_all_services()

    def close(self) -> None:
        self._zeroconf.close()


def service_info(port: int, certificate: str, protocol: int, host: str | None = None) -> Any:
    """The service record: one PTR/SRV for the instance, TXT with the pin and protocol.

    An address is published too when one is known, so a phone that has only mDNS
    can still connect; MDNS skips link-local IPv6.
    """
    from zeroconf import ServiceInfo  # noqa: PLC0415 - optional dependency

    name = f"phonxiety-{socket.gethostname().split('.')[0] or 'daemon'}.{SERVICE_TYPE}"
    addresses: list[bytes] = []
    if host:
        try:
            addresses.append(socket.inet_aton(host))
        except OSError:
            addresses = []
    properties = {TXT_CERT: certificate, TXT_PROTOCOL: str(protocol)}
    return ServiceInfo(
        SERVICE_TYPE,
        name,
        addresses=addresses or None,
        port=port,
        properties=properties,
        server=f"{socket.gethostname().split('.')[0] or 'phonxiety'}.local.",
    )


class MdnsAdvertiser:
    """Publishes (and withdraws) the address-link advertisement."""

    def __init__(
        self,
        port: int,
        certificate: str,
        protocol: int,
        *,
        host: str | None = None,
        backend: Backend | None = None,
    ) -> None:
        self._port = port
        self._certificate = certificate
        self._protocol = protocol
        self._host = host
        self._backend = backend
        self._info: Any = None
        self._started = False

    @property
    def started(self) -> bool:
        return self._started

    def start(self) -> bool:
        """Advertise the listener; false when mDNS is unavailable here."""
        if self._started:
            return True
        try:
            backend = self._backend or ZeroconfBackend()
            host = self._host or _first_local_address()
            info = service_info(self._port, self._certificate, self._protocol, host)
        except ImportError:
            LOGGER.info("mDNS advertisement unavailable: the zeroconf package is not installed")
            return False
        except (OSError, ValueError) as exc:
            LOGGER.warning("mDNS advertisement could not be prepared: %s", exc)
            return False
        try:
            backend.register(info)
        except Exception as exc:  # noqa: BLE001 - any responder failure is only a lost hint
            LOGGER.warning("mDNS advertisement failed: %s", exc)
            _close(backend)
            return False
        self._backend = backend
        self._info = info
        self._started = True
        LOGGER.info("advertising %s for the phone on port %s", SERVICE_TYPE, self._port)
        return True

    def stop(self) -> None:
        """Withdraw the advertisement; safe to call twice."""
        backend = self._backend
        self._started = False
        self._backend = None
        self._info = None
        if backend is None:
            return
        try:
            backend.unregister_all()
        except Exception as exc:  # noqa: BLE001 - shutdown must not raise
            LOGGER.debug("mDNS withdrawal failed: %s", exc)
        _close(backend)


def _first_local_address() -> str | None:
    """The address multicast leaves by.

    Only the routing table is asked: a host-name lookup can take minutes when DNS is
    unreachable, and `start()` runs on the daemon's event loop. A phone on the same LAN
    reaches the daemon by the same interface that carries the advertisement.
    """
    from .tailnet import route_address  # noqa: PLC0415 - keeps the import graph flat

    return route_address()


def _close(backend: Backend) -> None:
    try:
        backend.close()
    except Exception:  # noqa: BLE001 - shutdown must not raise
        pass
