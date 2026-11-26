"""Own the call independently of MCP requests and asynchronous provider work."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from . import far_end
from .archive import write_call_record
from .audio.recording import CallRecorder
from .audio.share import make_shareable
from .device import CommandSuppressed, DeviceError, DeviceHub
from .interfaces import ToolHandler, VoiceCallbacks, VoiceSession, line_action
from .models import CallRequest, CallState, TERMINAL_STATES, TestAudioOptions
from .prompts import render
from .store import CallStore

log = logging.getLogger(__name__)
KEYPRESS_COOLDOWN_S = 3.0  # LiveKit's IVR navigator uses the same per-step cooldown
# Shutdown budget: background work gets CLOSE_TASK_BUDGET_S to finish on its own, then
# CLOSE_CANCEL_BUDGET_S to unwind after cancellation. Device, agent and store close after
# that, so a caller bounding close() must allow more than 20 s (tests use 30 s).
CLOSE_TASK_BUDGET_S = 15.0
CLOSE_CANCEL_BUDGET_S = 5.0
# A phone that refuses `dial` proved it placed no call (its link is being rebuilt, or the
# command never left the daemon), so the user's call is retried once after waiting up to
# DIAL_RETRY_WAIT_S for the phone's link.  An ambiguous end of the link is never retried.
DIAL_REFUSALS = frozenset({"link_down", "device_not_connected", "device_replaced"})
DIAL_RETRY_WAIT_S = 20.0
# A Lab loopback probe needs long enough to hear both directions before the call may end.
LOOPBACK_PROBE_S = 10.0


class _DialRefused(DeviceError):
    """The phone refused the dial before placing anything and the one retry did not get through."""


@dataclass(slots=True)
class _DialRetry:
    """One retry dial: reserved under the controller lock, sent after the lock is released.

    The phone may take its full command timeout to answer the dial, so a hangup marks this
    reservation instead of waiting for the command.  `resolved` is set once the command has
    an outcome; until then `cancelled` is the pending cancellation.
    """

    resolved: asyncio.Event = field(default_factory=asyncio.Event)
    cancelled: bool = False
    accepted: bool = False
    error: DeviceError | None = None


@dataclass(slots=True)
class _Call:
    call_id: str
    request: CallRequest
    voice: VoiceSession | None = None
    prepare_task: asyncio.Task[Any] | None = None
    work_task: asyncio.Task[Any] | None = None
    ring_task: asyncio.Task[Any] | None = None
    duration_task: asyncio.Task[Any] | None = None
    ending_task: asyncio.Task[Any] | None = None
    finalize_task: asyncio.Task[Any] | None = None
    watch_task: asyncio.Task[Any] | None = None
    generation: int = 0
    work_is_review: bool = False
    actions: list[dict[str, Any]] = field(default_factory=list)
    delegation_ids: set[str] = field(default_factory=set)
    audio_ready: bool = False
    output_open: bool = False
    finalizing: bool = False
    ended: asyncio.Event = field(default_factory=asyncio.Event)
    media_changed: asyncio.Event = field(default_factory=asyncio.Event)
    assistant_turns: int = 0
    # Set at an assistant turn end when the voice cannot hang up itself: review the line now.
    review_now: bool = False
    review_wake: asyncio.Event = field(default_factory=asyncio.Event)
    last_voice_activity: float = 0
    last_activity: float = 0
    finish_requested_at: float | None = None
    finish_turn_base: int = 0
    finish_speech_seen: bool = False
    audio_codec: str | None = None
    recorder: CallRecorder | None = None
    recording: dict[str, Any] | None = None
    last_keypress: tuple[str, float] | None = None  # digits and when they finished
    keypress_in_flight: str | None = None
    far_end: str | None = None  # "ivr" or "voicemail" once the wording gives it away
    farewell_pending: bool = False
    proposed_result: dict[str, Any] | None = None
    end_reason: str | None = None
    error: str | None = None
    release_awake: Callable[[], None] | None = None
    # Not dialed yet and waiting for a usable link: a link drop is part of the wait, not a failure.
    awaiting_link: bool = False
    # The retry dial currently reserved or in flight, so a hangup can cancel it.
    redial: _DialRetry | None = None
    # Paused from the phone: the agent keeps talking into the session but nothing reaches the line.
    muted: bool = False
    marks: list[dict[str, Any]] = field(default_factory=list)
    # Optional Lab test-call probes, started once the audio is up (see place_call).
    test_audio: TestAudioOptions | None = None
    probe_hold_until: float | None = None
    # One finish is being started or held for the probes: repeats must not stack goodbyes.
    finish_armed: bool = False
    # The task that ends the call once the probe deadline passes; cancelled when the
    # hold is withdrawn, on hang-up and in finalization.
    finish_hold_task: asyncio.Task[Any] | None = None
    # A held finish that will end without a farewell: like a started one, it silences
    # any further spoken answer (see _run_delegation).
    held_without_farewell: bool = False
    loopback_on: bool = False
    tone_on: bool = False
    tone_stop_task: asyncio.Task[Any] | None = None


class CallController:
    def __init__(
        self,
        store: CallStore,
        device: DeviceHub,
        voice_factory: Callable[[str, str, VoiceCallbacks], VoiceSession],
        task_agent: Any,
        *,
        singleton_file: Any = None,
        keep_awake: Callable[[], Callable[[], None] | None] | None = None,
        archive_dir: Path | None = None,
        device_wait: float = 15,
        link_wait: float = 90,
        dial_retry_wait: float = DIAL_RETRY_WAIT_S,
        farewell_timeout: float = 8,
        farewell_quiet: float = 0.8,
        review_quiet: float = 2.0,
        review_max_interval: float = 60,
        language_hint: str = "",
    ):
        self.store = store
        self.device = device
        self.voice_factory = voice_factory
        self.task_agent = task_agent
        self._singleton_file = singleton_file
        self._keep_awake = keep_awake
        self._archive_dir = archive_dir
        self._device_wait = device_wait
        self._link_wait = link_wait
        self._dial_retry_wait = dial_retry_wait
        self._farewell_timeout = farewell_timeout
        self._farewell_quiet = farewell_quiet
        self._review_quiet = review_quiet
        self._review_max_interval = review_max_interval
        self._language_hint = language_hint.strip()
        self._calls: dict[str, _Call] = {}
        self._tasks: set[asyncio.Task[Any]] = set()
        self._lock = asyncio.Lock()
        self._changed = asyncio.Condition()
        self._revision = 0  # bumped by every state or transcript change the app API may report
        self._closing = False
        self._epoch = 0
        device.on_event = self.on_device_event
        device.on_audio = self.on_device_audio

    def _spawn(self, coroutine: Any, name: str) -> asyncio.Task[Any]:
        task = asyncio.create_task(coroutine, name=name)
        self._tasks.add(task)
        task.add_done_callback(self._task_done)
        return task

    def _task_done(self, task: asyncio.Task[Any]) -> None:
        self._tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.error("Background operation %s failed: %s", task.get_name(), task.exception())

    async def _notify(self) -> None:
        self._revision += 1
        async with self._changed:
            self._changed.notify_all()

    @property
    def revision(self) -> int:
        return self._revision

    async def wait_change(self, since: int, timeout: float) -> int:
        """Block until the call state moves past `since`, or the timeout; return the revision.

        The app API's long-poll uses this instead of polling the store.
        """
        try:
            async with asyncio.timeout(timeout):
                async with self._changed:
                    await self._changed.wait_for(lambda: self._revision > since)
        except TimeoutError:
            pass
        return self._revision

    def health(self) -> dict[str, Any]:
        return {
            "daemon": "stopping" if self._closing else "ready",
            "device": self.device.status(),
            "active_calls": [call.call_id for call in self._calls.values() if not call.finalizing],
        }

    async def start(self) -> None:
        for row in self.store.unfinished():
            call_id = row["call_id"]
            request = CallRequest.model_validate_json(row["request_json"])
            call = _Call(call_id, request, error="daemon_restarted")
            self._calls[call_id] = call
            if CallState(row["state"]) not in TERMINAL_STATES:
                self.store.terminate(
                    call_id,
                    CallState.UNKNOWN if row["dial_submitted"] else CallState.CANCELLED,
                    "unknown" if row["dial_submitted"] else "not_started",
                    error="daemon_restarted",
                )
            self._begin_finalize(call)

    async def place_call(self, request: CallRequest, *, test_audio: TestAudioOptions | None = None) -> dict[str, Any]:
        if self._closing:
            raise RuntimeError("daemon_stopping")
        async with self._lock:
            call_id, created = self.store.reserve(request)
            if created:
                call = _Call(call_id, request, test_audio=test_audio)
                self._calls[call_id] = call
                call.prepare_task = self._spawn(self._prepare(call), f"prepare:{call_id}")
            return self.store.snapshot(call_id)

    async def wait_call_result(self, call_id: str, wait_s: float = 30, cursor: int = 0, limit: int = 100) -> dict[str, Any]:
        if not 0 <= wait_s <= 45:
            raise ValueError("wait_s must be between 0 and 45")
        snapshot = self.store.snapshot(call_id, cursor, limit)
        if wait_s and snapshot["result_status"] != "ready":
            try:
                async with asyncio.timeout(wait_s):
                    async with self._changed:
                        await self._changed.wait_for(lambda: self.store.row(call_id)["result_status"] == "ready")
            except TimeoutError:
                pass
        return self.store.snapshot(call_id, cursor, limit)

    async def hangup_call(self, call_id: str, reason: str = "requested") -> dict[str, Any]:
        async with self._lock:
            state = self.store.state(call_id)
            if state == CallState.UNKNOWN and self.device.connected:
                await self.device.command("hangup", call_id)
                return self.store.snapshot(call_id)
            if state in TERMINAL_STATES:
                return self.store.snapshot(call_id)
            call = self._calls[call_id]
            call.end_reason = reason
            log.info("call %s: hangup requested (%s)", call_id, reason)
            # The retry dial is sent without the lock, so a hangup only marks its reservation
            # here; the dial that is already out is dropped by `_disconnect` once it resolves.
            retry = call.redial
            if retry is not None:
                retry.cancelled = True
            call.generation += 1
            call.output_open = False
            self._cancel(call.work_task)
            self._cancel(call.ending_task)
            self._abort_finish_hold(call)
            if self.store.cancel_preparing(call_id):
                self._cancel(call.prepare_task)
                self._begin_finalize(call)
            else:
                self.store.begin_ending(call_id)
                call.ending_task = self._spawn(self._disconnect(call, flush=True, retry=retry), f"hangup:{call_id}")
        await self._notify()
        return self.store.snapshot(call_id)

    # -- supervision from the phone app ---------------------------------

    def live_call(self, call_id: str) -> dict[str, Any] | None:
        """The in-memory view of a call the daemon still owns, or None."""
        call = self._calls.get(call_id)
        if call is None:
            return None
        work = call.work_task
        return {
            "call_id": call_id,
            "finalizing": call.finalizing,
            "muted": call.muted,
            "audio_ready": call.audio_ready,
            "awaiting_link": call.awaiting_link,
            "far_end": call.far_end,
            "audio_codec": call.audio_codec,
            "assistant_turns": call.assistant_turns,
            "marks": list(call.marks),
            "agent_activity": "working" if work is not None and not work.done() else "idle",
            "recording": call.recording,
            "error": call.error,
        }

    def _live_call_or_error(self, call_id: str) -> _Call:
        call = self._calls.get(call_id)
        if call is None or call.finalizing:
            raise RuntimeError("call_not_active")
        return call

    async def set_output_muted(self, call_id: str, muted: bool) -> dict[str, Any]:
        """Stop or resume sending the agent's speech to the phone.

        Backend-agnostic: the voice session keeps running and the line keeps the far end,
        only the daemon's outgoing audio is withheld, so no backend has to support pausing.
        """
        call = self._live_call_or_error(call_id)
        if self.store.state(call_id) not in (CallState.ACTIVE, CallState.ENDING):
            raise RuntimeError("call_not_active")
        call.muted = bool(muted)
        log.info("call %s: agent speech %s from the app", call_id, "paused" if muted else "resumed")
        await self._notify()
        return {"call_id": call_id, "muted": call.muted, "state": self.store.state(call_id).value}

    async def inject_instruction(self, call_id: str, text: str) -> dict[str, Any]:
        """Hand a supervisor directive to the voice session without speaking it.

        The text goes in as commentary so the model treats it as guidance, never as
        something to read out; the task agent also sees it in the history.
        """
        call = self._live_call_or_error(call_id)
        if call.voice is None or self.store.state(call_id) not in (CallState.ACTIVE, CallState.ENDING):
            raise RuntimeError("call_not_active")
        await call.voice.append_context(f"Supervisor instruction: {text}", commentary=True)
        call.actions.append({"role": "supervisor", "text": text, "at": time.time()})
        await self._notify()
        return {"call_id": call_id, "delivered": True}

    async def summarize_now(self, call_id: str, timeout: float = 90) -> dict[str, Any]:
        """Ask the task agent for summary and fields now; the call keeps running."""
        call = self._live_call_or_error(call_id)
        async with asyncio.timeout(timeout):
            result = await self.task_agent.summarize(call.request.model_dump(mode="json"), self._history(call))
        if result.get("task_outcome") not in {"succeeded", "declined", "incomplete", "needs_user", "unknown"}:
            raise ValueError("invalid_summary: unknown task outcome")
        if not isinstance(result.get("summary"), str) or not isinstance(result.get("fields", {}), dict):
            raise ValueError("invalid_summary: summary and fields are required")
        call.proposed_result = {
            "task_outcome": result["task_outcome"],
            "summary": result["summary"],
            "fields": result.get("fields", {}),
        }
        await self._notify()
        return {"call_id": call_id, **call.proposed_result}

    async def send_dtmf(self, call_id: str, digits: str) -> dict[str, Any]:
        """Press keys from the phone; the same command the agent's keypress uses."""
        call = self._live_call_or_error(call_id)
        if self.store.state(call_id) != CallState.ACTIVE:
            raise RuntimeError("call_not_active")
        result = await self.device.command("dtmf", call_id, action_generation=call.generation, digits=digits, timeout=15)
        call.actions.append({"role": "phone", "text": f"keypad digits sent: {digits}", "at": time.time()})
        await self._notify()
        return {"call_id": call_id, "digits": digits, "result": result}

    async def add_mark(self, call_id: str, mark: str, note: str | None = None) -> list[dict[str, Any]] | None:
        """Store a mark in a live call's record; None when the call is not in memory."""
        call = self._calls.get(call_id)
        if call is None:
            return None
        entry = {"mark": mark, "at": time.time()}
        if note:
            entry["note"] = note
        call.marks.append(entry)
        await self._notify()
        return list(call.marks)

    @staticmethod
    def _instructions() -> str:
        return render("voice/instructions")

    def _brief(self, request: CallRequest) -> str:
        brief = render("voice/brief", request=request)
        if self._language_hint:
            # The phone's language hint: say it once, then let the other party's own
            # language win if they clearly prefer another one.
            brief += (
                f"\nSpeak to the other party in {self._language_hint} unless they clearly prefer "
                "another language.\n"
            )
        return brief

    async def _prepare(self, call: _Call) -> None:
        try:
            # A phone that lost its link, or a daemon that just started, reconnects within seconds.
            if not self.device.connected and not await self.device.wait_connected(self._device_wait):
                raise DeviceError("device_not_connected")
            if self.device.info.get("active_call"):
                raise DeviceError("phone_busy")
            if not self.device.info.get("capabilities", {}).get("call_control"):
                raise DeviceError("phone_call_control_unavailable")
            await self._await_link(call)
            if self._keep_awake is not None:
                call.release_awake = self._keep_awake()
            callbacks = VoiceCallbacks(
                audio=lambda pcm: self._voice_audio(call, pcm),
                transcript=lambda role, text, final: self._transcript(call, role, text, final),
                delegation=lambda item_id, text: self._delegation(call, item_id, text),
                turn_done=lambda role: self._turn_done(call, role),
                error=lambda message: self._voice_error(call, message),
            )
            call.voice = self.voice_factory(self._instructions(), self._brief(call.request), callbacks)
            await call.voice.start()
            async with self._lock:
                if self._closing or not self.device.connected:
                    raise DeviceError("device_not_connected")
                if not self.store.submit_dial(call.call_id):
                    return
                call.ring_task = self._spawn(self._ring_timeout(call), f"ring:{call.call_id}")
            await self._notify()
            payload: dict[str, Any] = {"destination": call.request.destination}
            if call.request.phone_account:
                payload["phone_account"] = call.request.phone_account
            await self._dial(call, payload)
        except asyncio.CancelledError:
            raise
        except _DialRefused as exc:
            log.warning("call %s: preparation failed: %s", call.call_id, exc)
            if not call.finalizing:
                await self._fail(call, str(exc), unknown=False)
        except Exception as exc:
            log.warning("call %s: preparation failed: %s", call.call_id, exc)
            if not call.finalizing:
                row = self.store.row(call.call_id)
                submitted = bool(row["dial_submitted"])
                # A proven refusal means the phone placed nothing, so this is an
                # explicit failure, not an unknown outcome to reconcile.
                refused = isinstance(exc, DeviceError) and str(exc) in DIAL_REFUSALS
                await self._fail(call, str(exc), unknown=submitted and not refused)

    async def _dial(self, call: _Call, payload: dict[str, Any]) -> None:
        """Dial the number, waiting out one link refusal instead of losing the call.

        The phone refuses a dial outright (``link_down``) while it rebuilds its
        link; the user's call is not lost to that.  Only a refusal that proves
        nothing was placed is retried, and only once.  An ambiguous end of the
        link — a command timeout, or the link dropping after the command went
        out — is never retried: the phone may already be ringing someone, and a
        second dial would call twice.  The ring deadline of the refused dial is
        held through the wait and restarted with the retry, and a call cancelled
        during the wait is never dialed again.
        """
        # A link drop before the phone accepts this dial is part of the dial, not
        # the call's end; the device event must not finalize the call under it.
        call.awaiting_link = True
        try:
            try:
                await self.device.command("dial", call.call_id, **payload)
            except DeviceError as exc:
                if str(exc) not in DIAL_REFUSALS or call.finalizing or self._closing:
                    raise
                log.info("call %s: phone refused the dial (%s); waiting for its link", call.call_id, exc)
                # A refused dial placed nothing, so its ring deadline is held here; the
                # retry starts a fresh one when it goes out.
                self._cancel(call.ring_task)
                call.ring_task = None
                try:
                    await self._await_dial_link(call)
                except DeviceError as wait_exc:
                    # The wait sent no second command, so the refusal still proves nothing was placed.
                    raise _DialRefused(str(wait_exc)) from wait_exc
                if not await self._redial(call, payload):
                    return
            log.info("call %s: dial accepted by the phone", call.call_id)
        finally:
            call.awaiting_link = False

    async def _redial(self, call: _Call, payload: dict[str, Any]) -> bool:
        """Send the one retry a refusal earned, unless the call stopped being dialed meanwhile.

        Eligibility is decided under the controller lock and the retry is reserved there, but
        the dial itself goes out after the lock is released: the phone can take its full
        command timeout to answer, and a hangup, the ring deadline, a phone event or shutdown
        must never wait for that.  A hangup that reaches the reservation before the command
        goes out suppresses it.  One that reaches it afterwards leaves the ending to
        `_disconnect`, which drops a line the phone accepts after refusing its earlier hangup.
        False leaves the call to the cancellation that owns it.
        """
        async with self._lock:
            state = self.store.state(call.call_id)
            if (
                self._closing
                or not self._owned(call)
                or call.finalizing
                or state not in (CallState.DIALING, CallState.PREPARING)
            ):
                log.info("call %s: retry skipped, the call is %s", call.call_id, state.value)
                return False
            retry = _DialRetry()
            call.redial = retry
            call.ring_task = self._spawn(self._ring_timeout(call), f"ring:{call.call_id}")
        try:
            if retry.cancelled:
                log.info("call %s: retry suppressed, the call was hung up", call.call_id)
                return False
            await self.device.command(
                "dial", call.call_id, should_send=lambda: not retry.cancelled, **payload
            )
            retry.accepted = True
            return True
        except CommandSuppressed:
            # The hangup reached the reservation before the command left the daemon: the
            # phone saw nothing, and the cancellation that owns the call finishes it.
            log.info("call %s: retry suppressed at the send boundary, the call was hung up", call.call_id)
            return False
        except DeviceError as exc:
            retry.error = exc
            if retry.cancelled:
                # The hangup owns this ending; `_disconnect` decides from the outcome whether
                # a line was placed, so it is never reported twice.
                return False
            # This command went out. A refusal code proves the phone placed nothing;
            # anything else may have rung the far end, so the outcome stays unknown.
            if str(exc) in DIAL_REFUSALS:
                raise _DialRefused(str(exc)) from exc
            raise
        finally:
            # A cancelled reservation stays on the call: a later hangup, shutdown or
            # audio_error replaces the ending task and must hand it the same resolved
            # outcome, or a provably unplaced call would be reported unknown.
            if call.redial is retry and not retry.cancelled:
                call.redial = None
            retry.resolved.set()

    async def _await_dial_link(self, call: _Call) -> None:
        """Wait, bounded, for the link a refused dial needs, then let the caller retry once."""
        deadline = time.monotonic() + self._dial_retry_wait
        if not self.device.connected and not await self.device.wait_connected(self._dial_retry_wait):
            raise DeviceError("device_not_connected")
        await self._await_link(call, budget=max(0.0, deadline - time.monotonic()))

    async def _await_link(self, call: _Call, budget: float | None = None) -> None:
        """Dial only over a link that carries call audio right now.

        A phone that has just joined a network can spend its first minute on a
        path that delays or drops audio. Ringing someone into silence is worse
        than dialing a little later, so wait for a one-second echo probe to pass.
        [budget] shortens the wait, e.g. for the one retry after a refusal.
        """
        if not self.device.info.get("capabilities", {}).get("link_test"):
            return
        deadline = time.monotonic() + (self._link_wait if budget is None else budget)
        call.awaiting_link = True
        try:
            while True:
                try:
                    probe = await self.device.link_test(1.0)
                except DeviceError as exc:
                    probe = {"healthy": False, "route": str(exc)}
                if probe.get("healthy"):
                    return
                log.info(
                    "call %s: phone link is not ready for audio (%s, %s of %s frames back)",
                    call.call_id, probe.get("route"), probe.get("frames_echoed"), probe.get("frames_sent"),
                )
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise DeviceError("phone_link_not_ready")
                await asyncio.sleep(min(2.0, remaining))
                if not self.device.connected and not await self.device.wait_connected(max(0.0, deadline - time.monotonic())):
                    raise DeviceError("device_not_connected")
        finally:
            call.awaiting_link = False

    async def _ring_timeout(self, call: _Call) -> None:
        await asyncio.sleep(call.request.limits.ring_timeout_s)
        if self._owned(call) and self.store.state(call.call_id) == CallState.DIALING:
            await self.hangup_call(call.call_id, "ring_timeout")

    async def _duration_timeout(self, call: _Call) -> None:
        await asyncio.sleep(call.request.limits.max_duration_s)
        if self._owned(call) and not call.finalizing:
            await self.hangup_call(call.call_id, "duration_limit")

    async def _start_test_probes(self, call: _Call) -> None:
        """Start the Lab test call's automatic probes; the audio is up when this runs.

        A probe is best-effort: a phone that refuses one command must not lose the call.
        The call then holds its finish until the probes have run (tone seconds, and a
        minimum window for loopback) so the agent cannot cut them short.
        """
        options = call.test_audio
        if options is None:
            return
        began = time.monotonic()
        hold_s = 0.0
        if options.loopback:
            try:
                await self.device.command("loopback_start", call.call_id, timeout=5)
            except Exception as exc:
                log.warning("call %s: loopback probe could not start: %s", call.call_id, exc)
            else:
                call.loopback_on = True
                hold_s = max(hold_s, LOOPBACK_PROBE_S)
        if options.tone is not None:
            tone = options.tone
            try:
                await self.device.command("tone_start", call.call_id, hz=tone.hz, dbfs=tone.dbfs, timeout=5)
            except Exception as exc:
                log.warning("call %s: tone probe could not start: %s", call.call_id, exc)
            else:
                call.tone_on = True
                hold_s = max(hold_s, tone.seconds)
                call.tone_stop_task = self._spawn(
                    self._stop_tone_probe(call, tone.seconds), f"tone:{call.call_id}"
                )
        if hold_s > 0:
            call.probe_hold_until = began + hold_s
            log.info("call %s: test probes hold the finish for %.1f s", call.call_id, hold_s)

    async def _stop_tone_probe(self, call: _Call, seconds: float) -> None:
        await asyncio.sleep(seconds)
        if not self._owned(call) or not call.tone_on:
            return
        with contextlib.suppress(Exception):
            await self.device.command("tone_stop", call.call_id, timeout=5)
        call.tone_on = False

    async def _stop_test_probes(self, call: _Call) -> None:
        """Stop whatever probe is still running; `_finalize` calls this once per call."""
        if call.loopback_on:
            call.loopback_on = False
            with contextlib.suppress(Exception):
                await self.device.command("loopback_stop", call.call_id, timeout=5)
        if call.tone_on:
            call.tone_on = False
            with contextlib.suppress(Exception):
                await self.device.command("tone_stop", call.call_id, timeout=5)

    async def _hold_finish(self, call: _Call, farewell_pending: bool, deadline: float) -> None:
        """Wait out the Lab probes, then end the call; runs as its own task.

        The voice readers await delegation callbacks inline, so the wait must not
        live there: speech, hang-ups and failure events are read only between
        callbacks, and a sleep here would stall all of them.
        """
        remaining = deadline - time.monotonic()
        log.info("call %s: holding the finish %.1f s for the audio probes", call.call_id, remaining)
        await asyncio.sleep(max(0.0, remaining))
        if not self._owned(call) or call.finalizing or self.store.state(call.call_id) != CallState.ACTIVE:
            return
        await self._begin_finish(call, farewell_pending)

    def _abort_finish_hold(self, call: _Call) -> None:
        """Withdraw a finish that was waiting for the probes: the line moved on.

        The probe deadline is kept as it is, so a later finish still waits for the
        probes to run their full window instead of starting a new one.  The task
        reference stays for `_finalize` to join.
        """
        task = call.finish_hold_task
        if task is None or task.done():
            return
        self._cancel(task)
        call.held_without_farewell = False
        if not call.finalizing and self.store.state(call.call_id) != CallState.ENDING:
            call.finish_armed = False

    async def on_device_event(self, event: dict[str, Any]) -> None:
        kind = event.get("type")
        if kind == "hello":
            owned = event.get("active_call")
            if isinstance(owned, dict) and isinstance(owned.get("call_id"), str):
                call_id = owned["call_id"]
                call = self._calls.get(call_id)
                if call is None or call.finalizing:
                    self._spawn(self._reconcile_phone(call_id), f"reconcile:{call_id}")
            return
        if kind == "device_disconnected":
            for call in tuple(self._calls.values()):
                if not call.finalizing and not call.awaiting_link:
                    await self._fail(call, "device_disconnected", unknown=bool(self.store.row(call.call_id)["dial_submitted"]))
            return
        call_id = event.get("call_id")
        if not isinstance(call_id, str):
            return
        call = self._calls.get(call_id)
        if call is None:
            if kind == "call_state" and event.get("state") == "ended":
                with contextlib.suppress(LookupError):
                    self.store.reconcile_ended(call_id, event.get("disconnect_cause"))
                    await self._notify()
            return
        if kind == "audio_error" and not call.finalizing:
            call.error = str(event.get("error", "phone_audio_error"))[:1000]
            log.warning("call %s: phone audio error: %s", call_id, call.error)
            await self.hangup_call(call_id, "audio_error")
            return
        if kind != "call_state":
            return
        state = event.get("state")
        log.info("call %s: phone reports %s (cause %s)", call_id, state, event.get("disconnect_cause"))
        if state == "active":
            if call.finalizing or self.store.state(call_id) == CallState.ENDING:
                self._spawn(self._reconcile_phone(call_id), f"late-cancel:{call_id}")
                return
            if self.store.set_active(call_id):
                self._cancel(call.ring_task)
                call.duration_task = self._spawn(self._duration_timeout(call), f"duration:{call_id}")
                self._epoch = (self._epoch + 1) & 0xFFFFFFFF or 1
                try:
                    audio_format = self.device.begin_stream(call_id, self._epoch)
                    call.audio_codec = audio_format["codec"]
                    if self._archive_dir is not None and call.recorder is None and call.recording is None:
                        try:
                            call.recorder = CallRecorder(self._archive_dir, call_id)
                        except OSError as exc:
                            # No recording is no reason to drop an answered call.
                            log.warning("call %s: recording unavailable: %s", call_id, exc)
                            call.recording = {"file": None, "error": f"recording_unavailable: {exc}"}
                    started = await self.device.command("start_audio", call_id, epoch=self._epoch, **audio_format)
                    if started.get("codec", "pcm") != audio_format["codec"]:
                        raise DeviceError(f"audio_codec_mismatch: phone started {started.get('codec', 'pcm')}")
                    if call.finalizing or self.store.state(call_id) != CallState.ACTIVE:
                        return
                    call.audio_ready = True
                    call.output_open = True
                    if call.test_audio is not None:
                        await self._start_test_probes(call)
                    if call.voice:
                        await call.voice.append_context(render("voice/call_connected"))
                    call.last_activity = time.monotonic()
                    call.watch_task = self._spawn(self._supervise(call), f"supervise:{call_id}")
                except Exception as exc:
                    log.warning("call %s: audio start failed: %s", call_id, exc)
                    call.error = str(exc)[:1000]
                    await self.hangup_call(call_id, "audio_start_failed")
                await self._notify()
        elif state == "ended":
            call.ended.set()
            row = self.store.row(call_id)
            cause = event.get("disconnect_cause")
            outcome = self._phone_outcome(row["connected_at"] is not None, cause, call.end_reason)
            if self.store.terminate(call_id, CallState.ENDED, outcome, error=call.error, cause=cause):
                self._begin_finalize(call)
            elif self.store.state(call_id) == CallState.UNKNOWN:
                self.store.reconcile_ended(call_id, cause)
            await self._notify()

    @staticmethod
    def _phone_outcome(answered: bool, cause: Any, reason: str | None) -> str:
        if answered:
            return "answered"
        if reason == "ring_timeout":
            return "no_answer"
        code = cause.get("code") if isinstance(cause, dict) else None
        # android.telecom.DisconnectCause: BUSY=7, REJECTED=6, MISSED=5, CANCELED=4.
        return {7: "busy", 6: "declined", 5: "no_answer", 4: "cancelled"}.get(code, "disconnected")

    async def _reconcile_phone(self, call_id: str) -> None:
        try:
            await self.device.command("hangup", call_id)
        except DeviceError:
            log.warning("Phone call reconciliation did not acknowledge %s", call_id)

    async def on_device_audio(self, call_id: str, pcm: bytes) -> None:
        call = self._calls.get(call_id)
        if call and not call.finalizing and self.store.state(call_id) in (CallState.ACTIVE, CallState.ENDING):
            if call.recorder is not None:
                call.recorder.remote(pcm)
            if call.voice:
                call.voice.push_audio(pcm)

    async def _voice_audio(self, call: _Call, pcm: bytes) -> None:
        if not self._owned(call) or call.finalizing:
            return
        now = time.monotonic()
        if any(abs(sample) >= 96 for sample in memoryview(pcm).cast("h")):
            call.last_voice_activity = now
            self._activity(call, now)
            if call.finish_requested_at is not None:
                call.finish_speech_seen = True
        call.media_changed.set()
        if call.muted or not call.audio_ready or not call.output_open:
            return
        try:
            self.device.send_audio(call.call_id, pcm)
            if call.recorder is not None:
                call.recorder.agent(pcm)
        except DeviceError as exc:
            if call.error is None:
                call.error = str(exc)
                self._spawn(self.hangup_call(call.call_id, "audio_transport_error"), f"audio-failure:{call.call_id}")

    async def _transcript(self, call: _Call, role: str, text: str, final: bool) -> None:
        if not self._owned(call):
            return
        row = self.store.row(call.call_id)
        if row["connected_at"] is None:
            return
        self.store.append_transcript(call.call_id, role, text, final)
        self._activity(call, time.monotonic())
        await self._notify()
        if role == "user" and final and call.far_end is None:
            label = far_end.classify(text)
            if label is not None:
                call.far_end = label
                log.info("call %s: far end is %s", call.call_id, label)
                if call.voice:
                    await call.voice.append_context(render(f"voice/far_end_{label}"), commentary=True)

    async def _turn_done(self, call: _Call, role: str) -> None:
        if not self._owned(call):
            return
        self._activity(call, time.monotonic())
        if role == "assistant":
            call.assistant_turns += 1
            call.media_changed.set()
            if call.voice is not None and call.voice.review_at_turn_end:
                call.review_now = True
                call.review_wake.set()

    async def _voice_error(self, call: _Call, message: str) -> None:
        if self._owned(call) and not call.finalizing and not self._closing:
            call.error = message[:1000]
            if self.store.state(call.call_id) == CallState.PREPARING:
                self._spawn(self._fail(call, call.error, unknown=False), f"voice-error:{call.call_id}")
            else:
                self._spawn(self.hangup_call(call.call_id, "voice_error"), f"voice-error:{call.call_id}")

    async def _delegation(self, call: _Call, delegation_id: str, request: str) -> None:
        if not self._owned(call) or call.finalizing or delegation_id in call.delegation_ids:
            log.info("call %s: delegation %s ignored (finalizing or repeated)", call.call_id, delegation_id)
            return
        state = self.store.state(call.call_id)
        action = line_action(request)
        if action is not None and action[0] == "end_call":
            # The voice agent has just said goodbye and asks to hang up: no model decides this,
            # the line drops once the goodbye has played and the summary comes later. A repeat
            # while the call is already ending changes nothing.
            call.delegation_ids.add(delegation_id)
            if state == CallState.ACTIVE and call.finish_requested_at is None:
                log.info("call %s: delegation %s asked to end the call", call.call_id, delegation_id)
                await self._start_finish(call, farewell_pending=False)
            return
        if state == CallState.ENDING and call.finish_requested_at is not None and call.end_reason is None:
            self._cancel(call.ending_task)
            if not self.store.resume_active(call.call_id):
                return
            call.finish_requested_at = None
            call.finish_armed = False
            call.finish_speech_seen = False
            call.proposed_result = None
            call.output_open = True
            log.info("call %s: delegation %s reopened the call during the farewell", call.call_id, delegation_id)
        elif state != CallState.ACTIVE:
            log.info("call %s: delegation %s ignored in state %s", call.call_id, delegation_id, state.value)
            return
        call.delegation_ids.add(delegation_id)
        if action is not None and action[0] == "dtmf" and self._repeated_keypress(call, action[1]):
            # The same keys again while they are being pressed, or just after: one request heard
            # twice. Answer it without cancelling the keypress in flight.
            log.info("call %s: keypress %s repeated within the cooldown, not sent again", call.call_id, action[1])
            if call.voice:
                await call.voice.append_context(
                    f"Already pressed {action[1]}; wait for the menu.", delegation_id=delegation_id, commentary=True,
                )
            return
        call.generation += 1
        self._cancel(call.work_task)
        self._abort_finish_hold(call)
        generation = call.generation
        log.info(
            "call %s: delegation %s accepted as action generation %d: %r",
            call.call_id, delegation_id, generation, request[:200],
        )
        call.work_is_review = False
        call.last_activity = time.monotonic()
        if action is not None and action[0] == "dtmf":
            # A plain keypress: the daemon presses it, no model is asked.
            work = self._run_keypress(call, generation, delegation_id, action[1])
        else:
            work = self._run_delegation(call, generation, delegation_id, request)
        call.work_task = self._spawn(work, f"delegation:{call.call_id}:{generation}")

    def _activity(self, call: _Call, now: float) -> None:
        """Record speech or transcript progress; a review of the quiet line is stale after it."""
        call.last_activity = now
        # A turn-end review not started yet is stale too: _turn_done sets it after this call.
        call.review_now = False
        if call.work_is_review and call.work_task is not None and not call.work_task.done():
            call.generation += 1
            self._cancel(call.work_task)
        # The line is still alive: a finish waiting for the probes is withdrawn, and a
        # later review asks again while the original probe deadline still stands.
        self._abort_finish_hold(call)

    async def _supervise(self, call: _Call) -> None:
        """Review the line whenever it goes quiet, and after every assistant turn if asked to.

        The voice model does not reliably ask the backend to press keys or to
        disconnect, so call control cannot depend on its delegations alone. A voice that
        cannot hang up itself just says goodbye; the review at the end of its turn hangs up.
        """
        seen: float | None = None
        interval = self._review_quiet
        due = 0.0
        while self._owned(call) and not call.finalizing:
            if self.store.state(call.call_id) != CallState.ACTIVE:
                await asyncio.sleep(min(self._review_quiet, 0.2))
                continue
            if call.work_task is not None and not call.work_task.done():
                await asyncio.wait({call.work_task})
                continue
            if call.last_activity != seen:
                seen = call.last_activity
                interval = self._review_quiet
                due = seen + interval
            now = time.monotonic()
            if call.review_now:
                call.review_now = False
                due = now
            if now < due:
                call.review_wake.clear()
                try:
                    await asyncio.wait_for(call.review_wake.wait(), min(due - now, self._review_quiet))
                except TimeoutError:
                    pass
                continue
            transcript = self.store.transcript(call.call_id)
            if not transcript:
                due = now + self._review_quiet
                continue
            call.generation += 1
            call.work_is_review = True
            call.work_task = self._spawn(
                self._run_review(call, call.generation, now - call.last_activity),
                f"review:{call.call_id}:{call.generation}",
            )
            await asyncio.wait({call.work_task})
            interval = min(interval * 3, self._review_max_interval)
            due = time.monotonic() + interval

    def _check_work(self, call: _Call, generation: int) -> None:
        if not self._owned(call) or call.finalizing or generation != call.generation or self.store.state(call.call_id) not in (CallState.ACTIVE, CallState.ENDING):
            raise asyncio.CancelledError("Stale call work")

    def _history(self, call: _Call) -> list[dict[str, Any]]:
        """Final transcript segments and the phone actions already performed, oldest first.

        Without the actions a later review of an unchanged IVR prompt would press the same keys again.
        """
        return sorted([*self.store.transcript(call.call_id), *call.actions], key=lambda entry: entry["at"])

    def _phone_tools(self, call: _Call, generation: int, *, review: bool) -> dict[str, ToolHandler]:
        async def send_dtmf(arguments: dict[str, Any]) -> dict[str, Any]:
            self._check_work(call, generation)
            digits = arguments.get("digits")
            if not isinstance(digits, str) or not digits or len(digits) > 32 or any(char not in "0123456789*#ABCD" for char in digits):
                raise ValueError("DTMF requires 1..32 keypad characters")
            # Keys are being pressed from here on: speech on the line must not abort half a sequence.
            call.work_is_review = False
            result = await self.device.command(
                "dtmf", call.call_id, action_generation=generation, digits=digits, timeout=15
            )
            self._check_work(call, generation)
            call.last_activity = time.monotonic()
            call.actions.append({"role": "phone", "text": f"keypad digits sent: {digits}", "at": time.time()})
            log.info("call %s: sent %d DTMF digits", call.call_id, len(digits))
            return result

        async def finish_call(arguments: dict[str, Any]) -> dict[str, Any]:
            self._check_work(call, generation)
            if call.finish_requested_at is not None:
                return {"status": "ending"}
            outcome = arguments.get("outcome", "unknown")
            if outcome not in {"succeeded", "declined", "incomplete", "needs_user", "unknown"}:
                raise ValueError("Invalid task outcome")
            summary = arguments.get("summary")
            fields = arguments.get("fields", {})
            farewell_pending = arguments.get("farewell_pending")
            if not isinstance(summary, str) or not isinstance(fields, dict) or not isinstance(farewell_pending, bool):
                raise ValueError("finish_call requires summary text, a fields object and farewell_pending")
            call.proposed_result = {"task_outcome": outcome, "summary": summary, "fields": fields}
            log.info(
                "call %s: finish requested with outcome %s (farewell pending: %s)",
                call.call_id, outcome, farewell_pending,
            )
            await self._start_finish(call, farewell_pending)
            if not farewell_pending:
                return {"status": "ending"}
            if review and call.voice:
                await call.voice.append_context(render("voice/call_complete"))
            return {"status": "ending", "instruction": render("task/finish_instruction")}

        return {"send_dtmf": send_dtmf, "finish_call": finish_call}

    async def _start_finish(self, call: _Call, farewell_pending: bool) -> None:
        """Arm one finish; with Lab probes pending the hold is a task of its own.

        The caller is a voice event reader (a review's tool handler or a delegation
        callback), so it returns at once even while the probes hold the ending.
        """
        if call.finish_armed:
            return
        call.finish_armed = True
        # A Lab test call with probes stays up until they have run, however early the
        # agent decides it is done; a hangup from the app is not held and still ends it.
        deadline = call.probe_hold_until
        if deadline is None or deadline <= time.monotonic():
            if not self._owned(call) or call.finalizing or self.store.state(call.call_id) != CallState.ACTIVE:
                return
            await self._begin_finish(call, farewell_pending)
            return
        call.held_without_farewell = not farewell_pending
        call.finish_hold_task = self._spawn(
            self._hold_finish(call, farewell_pending, deadline), f"finish-hold:{call.call_id}",
        )

    async def _begin_finish(self, call: _Call, farewell_pending: bool) -> None:
        self.store.begin_ending(call.call_id)
        call.finish_requested_at = time.monotonic()
        call.farewell_pending = farewell_pending
        # From here finish_requested_at and farewell_pending decide whether answers are spoken;
        # a hold's own flag would outlive a reopened call and silence its next answer.
        call.held_without_farewell = False
        # Without a pending farewell the last completed assistant turn already was the goodbye.
        call.finish_turn_base = call.assistant_turns if farewell_pending else call.assistant_turns - 1
        call.finish_speech_seen = not farewell_pending
        call.ending_task = self._spawn(self._graceful_finish(call), f"farewell:{call.call_id}")
        await self._notify()

    @staticmethod
    def _repeated_keypress(call: _Call, digits: str) -> bool:
        if call.keypress_in_flight == digits:
            return True
        last = call.last_keypress
        return last is not None and last[0] == digits and time.monotonic() - last[1] < KEYPRESS_COOLDOWN_S

    async def _run_keypress(self, call: _Call, generation: int, delegation_id: str, digits: str) -> None:
        """Press keys the voice agent asked for; repeats are answered in _delegation."""
        # Marked before sending, so a repeat that arrives mid-sequence does not cancel it.
        call.keypress_in_flight = digits
        try:
            await self.device.command("cancel_actions", call.call_id, action_generation=generation)
            self._check_work(call, generation)
            await self._phone_tools(call, generation, review=False)["send_dtmf"]({"digits": digits})
            call.last_keypress = (digits, time.monotonic())
            result = f"Pressed {digits}."
            # Commentary: the agent learns it is done but does not announce it to a menu.
            if call.voice:
                await call.voice.append_context(result, delegation_id=delegation_id, commentary=True)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.warning("call %s: keypress %s failed: %s", call.call_id, digits, exc)
            if self._owned(call) and not call.finalizing and generation == call.generation and call.voice:
                await call.voice.append_context(f"The keypress failed: {str(exc)[:300]}", delegation_id=delegation_id)
        finally:
            if call.keypress_in_flight == digits:
                call.keypress_in_flight = None

    async def _run_delegation(self, call: _Call, generation: int, delegation_id: str, request: str) -> None:
        try:
            await self.device.command("cancel_actions", call.call_id, action_generation=generation)
            self._check_work(call, generation)
            text = await self.task_agent.run(
                call.request.model_dump(mode="json"),
                self._history(call),
                request,
                self._phone_tools(call, generation, review=False),
            )
            self._check_work(call, generation)
            log.info("call %s: delegation %s answered (%d chars)", call.call_id, delegation_id, len(text))
            call.last_activity = time.monotonic()
            # A finish without a pending farewell disconnects now (or once the Lab probes have run);
            # a spoken answer would be cut off, and speech would withdraw a held finish.
            silent_finish = (call.finish_requested_at is not None and not call.farewell_pending) or call.held_without_farewell
            if call.voice and not silent_finish:
                await call.voice.append_context(f"FINAL_RESULT: {text}", delegation_id=delegation_id)
        except asyncio.CancelledError:
            log.info("call %s: delegation %s cancelled", call.call_id, delegation_id)
            raise
        except Exception as exc:
            log.warning("call %s: delegation %s failed: %s", call.call_id, delegation_id, exc)
            if self._owned(call) and not call.finalizing and generation == call.generation and call.voice:
                await call.voice.append_context(f"The requested action failed: {str(exc)[:500]}", delegation_id=delegation_id)

    async def _run_review(self, call: _Call, generation: int, quiet: float) -> None:
        log.info("call %s: reviewing the line after %.1f s of quiet", call.call_id, quiet)
        try:
            await self.device.command("cancel_actions", call.call_id, action_generation=generation)
            self._check_work(call, generation)
            await self.task_agent.review(
                call.request.model_dump(mode="json"),
                self._history(call),
                quiet,
                self._phone_tools(call, generation, review=True),
            )
            log.info("call %s: review finished (finish requested: %s)", call.call_id, call.finish_requested_at is not None)
        except asyncio.CancelledError:
            log.info("call %s: review superseded", call.call_id)
            raise
        except Exception as exc:
            log.warning("call %s: review failed: %s", call.call_id, exc)

    async def _graceful_finish(self, call: _Call) -> None:
        deadline = time.monotonic() + self._farewell_timeout
        while self._owned(call) and not call.finalizing:
            now = time.monotonic()
            quiet_for = now - call.last_voice_activity
            if call.finish_speech_seen and call.assistant_turns > call.finish_turn_base and quiet_for >= self._farewell_quiet:
                break
            if now >= deadline:
                call.error = call.error or "farewell_timeout"
                break
            call.media_changed.clear()
            quiet_wait = max(0.02, self._farewell_quiet - quiet_for) if call.finish_speech_seen else self._farewell_quiet
            try:
                await asyncio.wait_for(call.media_changed.wait(), min(deadline - now, quiet_wait))
            except TimeoutError:
                pass
        if not self._owned(call) or call.finalizing:
            return
        log.info(
            "call %s: farewell wait ended after %.1f s (speech %s, error %s)",
            call.call_id, time.monotonic() - (deadline - self._farewell_timeout), call.finish_speech_seen, call.error,
        )
        call.output_open = False
        call.end_reason = "completed"
        target = self.device.submitted_samples
        if not await self.device.wait_played(call.call_id, target):
            call.error = call.error or "playback_drain_timeout"
        await self._disconnect(call, flush=False)

    async def _disconnect(self, call: _Call, *, flush: bool, retry: _DialRetry | None = None) -> None:
        call.output_open = False
        try:
            if flush:
                self.device.stop_stream()
            await self.device.command("hangup", call.call_id, timeout=5)
            log.info("call %s: hangup accepted by the phone", call.call_id)
            async with asyncio.timeout(8):
                await call.ended.wait()
        except (DeviceError, TimeoutError) as exc:
            if retry is not None and not call.finalizing:
                # The hangup can be refused before the phone has processed the retry dial.
                # The retry decides whether a line exists: drop it if the phone took the
                # dial, keep the unknown outcome if it may have, and finish the cancelled
                # call locally if nothing was placed.
                await retry.resolved.wait()
                if retry.accepted:
                    try:
                        await self.device.command("hangup", call.call_id, timeout=5)
                        async with asyncio.timeout(8):
                            await call.ended.wait()
                        return
                    except (DeviceError, TimeoutError) as again:
                        if call.finalizing:
                            return
                        await self._fail(call, f"hangup_unconfirmed: {again}", unknown=True)
                        return
                if retry.error is not None and str(retry.error) not in DIAL_REFUSALS:
                    await self._fail(call, f"hangup_unconfirmed: {retry.error}", unknown=True)
                    return
                await self._end_locally(call)
                return
            if not call.finalizing:
                await self._fail(call, f"hangup_unconfirmed: {exc}", unknown=True)

    async def _end_locally(self, call: _Call) -> None:
        """Finish a call the phone provably never placed: its dials were refused and nothing rang."""
        outcome = self._phone_outcome(False, None, call.end_reason)
        if self.store.terminate(call.call_id, CallState.ENDED, outcome, error=call.error):
            self._begin_finalize(call)
            await self._notify()

    async def _fail(self, call: _Call, error: str, *, unknown: bool) -> None:
        call.error = error[:1000]
        if self.store.terminate(
            call.call_id,
            CallState.UNKNOWN if unknown else CallState.FAILED,
            "unknown" if unknown else "not_started",
            error=call.error,
        ):
            if unknown and self.device.connected:
                self._spawn(self._reconcile_phone(call.call_id), f"uncertain-cancel:{call.call_id}")
            self._begin_finalize(call)
            await self._notify()

    def _begin_finalize(self, call: _Call) -> None:
        if call.finalizing:
            return
        call.finalizing = True
        call.redial = None
        call.audio_ready = False
        call.output_open = False
        call.generation += 1
        self._cancel(call.finish_hold_task)
        self.device.stop_stream()
        call.finalize_task = self._spawn(self._finalize(call), f"finalize:{call.call_id}")

    async def _finalize(self, call: _Call) -> None:
        current = asyncio.current_task()
        jobs = [
            call.prepare_task, call.work_task, call.ring_task, call.duration_task, call.ending_task,
            call.watch_task, call.tone_stop_task, call.finish_hold_task,
        ]
        for job in jobs:
            if job is not current:
                self._cancel(job)
        await asyncio.gather(*(job for job in jobs if job is not None and job is not current), return_exceptions=True)
        await self._stop_test_probes(call)
        if call.voice is not None:
            try:
                async with asyncio.timeout(5):
                    await call.voice.close()
            except Exception:
                call.error = call.error or "voice_close_failed"
        if call.recorder is not None:
            try:
                call.recording = await asyncio.to_thread(call.recorder.close)
                if call.recording and call.recording.get("file"):
                    wav = call.recorder.path
                    call.recording["compressed"] = await asyncio.to_thread(make_shareable, wav)
            except Exception as exc:
                log.exception("call %s: recording could not be written", call.call_id)
                call.recording = {"file": None, "error": f"recording_export_failed: {exc}"}
        transcript = self.store.transcript(call.call_id)
        result = call.proposed_result
        if result is None and transcript and not self._closing:
            try:
                async with asyncio.timeout(30):
                    result = await self.task_agent.summarize(call.request.model_dump(mode="json"), self._history(call))
                if result.get("task_outcome") not in {"succeeded", "declined", "incomplete", "needs_user", "unknown"}:
                    raise ValueError("Invalid summarized task outcome")
                if not isinstance(result.get("summary"), str) or not isinstance(result.get("fields", {}), dict):
                    raise ValueError("Invalid summarized result")
            except Exception:
                call.error = call.error or "result_finalization_failed"
                result = None
        if result is None:
            row = self.store.row(call.call_id)
            result = {
                "task_outcome": "incomplete" if row["call_outcome"] == "not_started" else "unknown",
                "summary": None,
                "fields": {},
            }
        self.store.finish_result(
            call.call_id,
            task_outcome=result["task_outcome"],
            summary=result.get("summary"),
            fields=result.get("fields", {}),
            transcript_complete=call.error is None and not self.store.partials(call.call_id),
            error=call.error,
        )
        if self._archive_dir is not None:
            try:
                await asyncio.to_thread(write_call_record, self._archive_dir, self._call_record(call))
            except Exception:
                log.exception("call %s: call record could not be written", call.call_id)
        if call.release_awake is not None:
            call.release_awake()
            call.release_awake = None
        if self._calls.get(call.call_id) is call:
            del self._calls[call.call_id]
        await self._notify()

    def _call_record(self, call: _Call) -> dict[str, Any]:
        """Everything known about a finished call, for its archive file."""
        record = self.store.snapshot(call.call_id)
        for paging in ("next_cursor", "has_more"):
            record.pop(paging)
        # Unfinished last utterances (current_partials) stay: they are not in the final transcript.
        record["transcript"] = self.store.transcript(call.call_id)
        record["request"] = call.request.model_dump(mode="json")
        record["audio_codec"] = call.audio_codec
        record["far_end"] = call.far_end or "person"
        record["recording"] = call.recording
        record["marks"] = list(call.marks)
        return record

    def _owned(self, call: _Call) -> bool:
        return self._calls.get(call.call_id) is call

    @staticmethod
    def _cancel(task: asyncio.Task[Any] | None) -> None:
        if task is not None and task is not asyncio.current_task() and not task.done():
            task.cancel()

    async def close(self) -> None:
        """Hang up, stop every task, then close the device, the task agent and the store.

        Both task waits are bounded with `asyncio.wait`, which does not wait for the tasks it
        was watching: `CLOSE_TASK_BUDGET_S` for the work to finish by itself, then
        `CLOSE_CANCEL_BUDGET_S` for it to unwind once cancelled. Nothing after this point is
        conditional, so cleanup runs even when a task refuses to stop; such tasks are named
        in the log and left cancelled.
        """
        if self._closing:
            return
        self._closing = True
        for call in tuple(self._calls.values()):
            if not call.finalizing:
                await self.hangup_call(call.call_id, "daemon_stopping")
        deadline = time.monotonic() + CLOSE_TASK_BUDGET_S
        while self._tasks and time.monotonic() < deadline:
            await asyncio.wait(set(self._tasks), timeout=deadline - time.monotonic())
        if self._tasks:
            pending = tuple(self._tasks)
            for task in pending:
                task.cancel()
            _, stuck = await asyncio.wait(pending, timeout=CLOSE_CANCEL_BUDGET_S)
            if stuck:
                names = sorted(task.get_name() for task in stuck)
                log.warning(
                    "Shutdown: %d task(s) still running %.0f s after cancellation: %s",
                    len(names), CLOSE_CANCEL_BUDGET_S, ", ".join(names),
                )
        await self.device.close()
        close_agent = getattr(self.task_agent, "close", None)
        if close_agent is not None:
            await close_agent()
        self.store.close()

    def release(self) -> None:
        """Give up the state directory; call only after every listener has closed its sockets."""
        if self._singleton_file is not None:
            self._singleton_file.close()
