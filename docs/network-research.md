# Network research

What carries the device protocol between the Mac daemon and the Android bridge, which parts were hand-rolled, and what replaces them. The wire protocol stays as it is (see [Protocol changes](#protocol-changes) for what changing it would give); this pass moves the code into one place per side and swaps in platform or library facilities where they do the same job.

Layout after the move:

- Mac: `src/phonxiety/net/` — `link.py` (what a link provides, limits, errors), `framing.py`, `iroh.py`, `websocket.py`, `tls.py`. `device.py` keeps the protocol itself (commands, events, audio stream, heartbeat, link test).
- Android: `com.phonxiety.bridge.net` — `DeviceLink.kt`, `Framing.kt`, `IrohLink.kt`, `WebSocketLink.kt`, `LinkSupervisor.kt` (connect, reconnect, host watchdog). `BridgeEngine.kt` keeps the protocol and call control.

## Components

| # | Component | Where it was | Candidates | Decision |
|---|---|---|---|---|
| 1 | iroh endpoint, accept loop, first-frame auth (Mac) | `p2p.py` `IrohListener` | [iroh-ffi](https://github.com/n0-computer/iroh-ffi) (already used) | **Keep, move to `net/iroh.py`.** It is already the library; what remains is our auth handshake. |
| 2 | Message framing on the iroh stream, both sides | `p2p.py` `frame`/`parse_header`; inline in `IrohLink.kt` `readLoop`/`buildFrame` | WebSocket framing ([websockets](https://websockets.readthedocs.io/en/stable/), [OkHttp](https://github.com/square/okhttp)); QUIC streams per message; [QUIC datagrams, RFC 9221](https://www.rfc-editor.org/rfc/rfc9221) | **Keep own, one codec per side** (`net/framing.py`, `net/Framing.kt`) with unit tests on both. Any other framing changes the wire protocol. |
| 3 | TLS WebSocket transport (Mac server, phone client) | `device.py` `WebSocketListener`, `_WebSocketLink`; `WebSocketLink.kt` | [websockets](https://websockets.readthedocs.io/en/stable/), [OkHttp](https://github.com/square/okhttp) (already used) | **Keep, move.** Both ends are already libraries; only the adapters are ours. |
| 4 | Self-signed TLS identity and pin | `tls.py`; `PinnedTrustManager` in `WebSocketLink.kt` | [cryptography x509](https://cryptography.io/en/latest/x509/) (already used); Android `network_security_config` pins | **Keep, move to `net/tls.py`.** A config pin needs the hash at build time; ours comes from pairing. |
| 5 | Heartbeat: daemon `ping` every 2 s with a 5 s deadline, phone host watchdog 10 s | `device.py` `_heartbeat`; `BridgeEngine.watchdog` | [websockets keepalive](https://websockets.readthedocs.io/en/stable/topics/keepalive.html); QUIC keep-alive/idle timeout ([iroh `QuicTransportConfigBuilder`](https://docs.rs/iroh/latest/iroh/endpoint/struct.QuicTransportConfigBuilder.html)) | **Keep own.** The phone watches *host activity* on the protocol, which a transport ping does not produce; iroh-ffi 1.2 does not expose the QUIC transport config (`EndpointOptions` has only preset, bind address, key, ALPNs, relay mode, protocols). The watchdog moves into `LinkSupervisor.kt`. |
| 6 | Reconnect with backoff 1 s ×2 up to 8 s (phone) | `BridgeEngine.scheduleReconnect` | [kotlinx `retryWhen`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/retry-when.html); [tenacity](https://github.com/jd/tenacity) on the Mac side | **Keep own, move to `LinkSupervisor.kt`**, now unit-tested. The link is callback-driven, not a flow; a retry operator would need the whole link rewritten as a flow for ten lines of backoff. |
| 7 | Waiting for an internet-capable network before binding iroh (phone) | `IrohSupport.awaitNetwork`: polls `activeNetwork` every 500 ms for up to 30 s | [`ConnectivityManager.registerDefaultNetworkCallback`](https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)) | **Adopt.** The platform tells us when the default network gains `INTERNET`; same 30 s give-up. |
| 8 | Shutting endpoints and connections down off the caller's thread (phone) | `IrohSupport.stopAsync`, `IrohLink.cancel`: a raw `Thread` per call running `runBlocking` | [kotlinx.coroutines `CoroutineScope`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-scope/) on `Dispatchers.IO` (already a dependency) | **Adopt.** One process-wide scope instead of a thread per shutdown. |
| 9 | Route sampling and relay-only recycle (phone) | `IrohLink.sampleRoute`: polls `paths()` every 1 s; recycles the endpoint after 20 s on the relay, at most once per 5 min, never during a call | iroh-ffi `Connection.watchPaths` / `watchPathEvents`; `Endpoint.watchNetworkChange` ([API](https://n0-computer.github.io/iroh-ffi)) | **Keep own for now.** The relay-only rule needs a clock anyway (20 s continuous, re-checked while a call blocks it), so a callback saves only the 1 s poll; the recycle itself is a workaround discussed below and changing its trigger changes behaviour. |
| 10 | Pre-dial link check: 1 s `PXLT` echo probe, retried for up to 90 s (Mac) | `controller._await_link`, `DeviceHub.link_test` | [tenacity](https://github.com/jd/tenacity) | **Keep own.** The loop re-waits for a reconnecting phone and logs each probe; tenacity would wrap the same three lines and add a dependency. |
| 11 | Accept-loop task bookkeeping (Mac) | `IrohListener._accept` with a task set | [`asyncio.TaskGroup`](https://docs.python.org/3/library/asyncio-task.html#task-groups) | **Keep own.** A TaskGroup cancels every sibling when one task raises; one bad handshake must not drop the connected phone. |
| 12 | TCP_NODELAY on the phone's WebSocket | `EventListener.connectionAcquired` in `WebSocketLink.kt` | OkHttp client options; a custom `SocketFactory` | **Keep own.** OkHttp has no option for it; the listener is the smallest hook that sets it before the upgrade request. |

## Workarounds found

| Workaround | Where | Why it exists | Evidence / status |
|---|---|---|---|
| Recycle the iroh endpoint after 20 s of relay-only path (at most once per 5 min, never during a call) | `IrohLink.kt` `sampleRoute`, `IrohSupport.reserveRecycle` | "An endpoint bound before the network settles can stay on the relay forever; a fresh endpoint finds the direct path again." | Matches upstream reports: endpoints that keep an old address or stay wedged after network changes ([iroh#4588](https://github.com/n0-computer/iroh/issues/4588), [iroh#4657](https://github.com/n0-computer/iroh/issues/4657), [iroh#4476](https://github.com/n0-computer/iroh/issues/4476)); production apps replace the endpoint on network change ([smelt#340](https://github.com/smelt-ai/smelt/pull/340)) or forward Android network callbacks to iroh ([hand#1356](https://github.com/atqamz/hand/pull/1356)); relay recovery itself can take 16–22 s of backoff ([UniClipboard#134](https://github.com/UniClipboard/Engine/issues/134)). Kept; see next stage. |
| Wait up to 30 s for an internet-capable network before binding | `IrohSupport.awaitNetwork` | An endpoint bound with no network never recovers a direct path | Polling replaced by the default-network callback (row 7). |
| Pre-dial echo probe, up to 90 s | `controller._await_link` | In the first minute after the phone joins Wi-Fi the link could not carry call audio (relay-only, then one-way loss) and calls died on the 5 s heartbeat | Root cause not found; the probe keeps calls from being dialled into a dead link. |
| Drop the oldest queued audio frame when 10 are waiting (Mac) / drop capture frames above 48 KB backlog (phone) | `DeviceHub._enqueue`, `BridgeEngine.sendFrame` | A stalled link must add no latency to speech | Intended behaviour, not a bug; kept. |
| Confirm playback by an empty phone queue after a ping round trip | `DeviceHub.wait_played` | Counters cannot prove playback (pre-roll counted, overflow drops not) | Fixed in the previous stage; relies on the single ordered stream. |
| TCP_NODELAY via an OkHttp event listener | `WebSocketLink.kt` | OkHttp does not disable Nagle on WebSockets | Kept (row 12). |
| `uniffi_set_event_loop` before binding iroh (Mac) | `net/iroh.py` `IrohListener.start` | iroh completes futures from its own threads | Required by the bindings; kept. |

## Protocol changes

Not done in this stage: each needs the daemon and the phone app updated together, and the phone could not be updated during this work.

| Change | What it would give | Evidence | Cost |
|---|---|---|---|
| Audio as QUIC datagrams ([RFC 9221](https://www.rfc-editor.org/rfc/rfc9221); iroh-ffi has `Connection.send_datagram` / `read_datagram`), control on the stream | A lost packet delays only itself; today one lost packet holds back every later audio frame *and* the pings behind it on the single ordered stream, which is how a stall turns into a 5 s heartbeat failure. Late audio is useless anyway. | `link_test` exists to measure exactly this head-of-line delay (control p95/max behind call-sized frames); the early-minute failures died on the heartbeat. Not measured with datagrams. | Both sides: datagram send/receive paths, a jitter buffer on the phone (the ring buffer drops on overflow but assumes order), loss accounting. `wait_played` must stop relying on audio and pings sharing one ordered stream. Datagrams are capped by path MTU (Opus fits, PCM 1920 B frames may not). |
| Control and audio on separate QUIC streams | Pings and commands no longer wait behind audio retransmissions; still reliable and ordered per stream. | Same as above. | Moderate: a second `open_bi` and routing by stream on both sides, plus a new playback barrier. `DeviceHub.wait_played` relies on a ping not overtaking audio; on separate streams it can, so an empty playback report could hang up before the goodbye played. Needs an end marker or watermark on the audio stream that the phone acknowledges, with tests. Audio still head-of-line blocks itself. |
| RTP over QUIC ([draft-ietf-avtcore-rtp-over-quic](https://datatracker.ietf.org/doc/draft-ietf-avtcore-rtp-over-quic/)) or WebRTC for the phone audio leg | Standard media framing, jitter buffers and loss concealment from existing stacks | Industry practice for live audio | Large: a media stack on the phone, new pairing/auth path; not justified before datagrams are measured. |
| Recreate the iroh endpoint on Android network changes instead of after 20 s on the relay | Faster recovery after Wi-Fi/cellular switches; no timer heuristic | [smelt#340](https://github.com/smelt-ai/smelt/pull/340), [hand#1356](https://github.com/atqamz/hand/pull/1356) | Phone-only, not a wire change, but changes link behaviour: needs testing on the phone. |

Recommended next stage, with the phone on the cable: separate control and audio streams first, together with an audio-stream end marker for the playback barrier, then measure datagrams for audio with `link_test` before deciding.

## Stage 2: link v2 (measured on the phone)

**Direct path.** Mac and phone were on the same Wi-Fi yet always talked through the relay. Cause, from tcpdump on the phone and iroh traces on the Mac: Android 17 blocks LAN traffic for apps targeting API 37 unless they hold [`ACCESS_LOCAL_NETWORK`](https://developer.android.com/privacy-and-security/local-network-permission). The phone received the Mac's hole-punch probes on `wlan0` but never answered them, and probed only non-local addresses. With the permission declared and granted the selected path is `direct`, 6–13 ms round trip (relay: 180–600 ms).

**Network changes.** The relay-only recycle timer is gone. `IrohSupport.watchNetwork` listens to Android's default network; when it is lost or replaced (and no call is owned) the link is dropped and the endpoint recreated, so the reconnect binds on the new network. Wi-Fi off/on ×3: link back in 4.0–4.5 s on cellular and 9.3–10.1 s on Wi-Fi, direct again each time. Airplane mode on/off ×2: back in 17.7–17.9 s after airplane mode is switched off (Wi-Fi association plus reconnect backoff, which reaches 8 s while there is no network).

**Reconnect takeover.** A new link from the same phone and boot replaces a stale one instead of being refused with `1013`; after `am force-stop` + start the daemon had the phone back in 2.5 s.

**Protocol v2.** ALPN `phonxiety/device/2`: control on the phone's bidirectional stream (higher send priority), audio on one unidirectional stream each way, probe frames optionally as QUIC datagrams. The playback barrier is a `PXMK` mark frame after the last audio on the audio stream; the phone reports `drained_mark` in its playback events once everything before the mark has played.

`phonxiety link-test --codec opus`, 15 s runs, ping = control round trip measured while call-rate audio flows, ms (median / p95 / max):

| Condition | v1 one stream | v2 separate streams | v2 audio as datagrams |
|---|---|---|---|
| LAN, no added impairment | 13–15 / 17–145 / 163–194 | 13–17 / 32–159 / 93–206 | 13–14 / 21–54 / 104–110 |
| Direct, netem 80±20 ms delay + 5 % loss on the phone | 130–145 / 221–300 / 245–524 | 109–113 / 274–276 / 283–402 | 106–110 / 191–262 / 360–374 |
| Relay, netem 5 % loss | 205–252 / 292–352 / 318–517 | 203–225 / 327–353 / 403–628 | not measured |

Audio frames in the same runs: direct with delay + loss, frame median 112–123 ms on v1 and 121–134 ms on v2 streams, 103–104 ms as datagrams with 5–6 % of frames lost (stream frames are never lost, they wait).

Reading: separate streams lower the typical control delay behind audio on a lossy direct path (median −15 to −20 %) but not its tail, and change nothing on the relay, whose leg to the relay server is TCP and blocks every stream on a loss. Datagrams lower audio delay and its tail at the price of losing late frames; the phone side would need loss concealment (Opus PLC) before carrying call audio that way.
