package com.phonxiety.bridge.feature.livecall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.daemonCallState
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.CallEvent
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import com.phonxiety.bridge.model.VoiceControls
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * What the daemon has said about one control of this call. Before `/info` answers — and if it never
 * does — the answer is [UNKNOWN], so the screen disables the control with a reason instead of
 * offering one the daemon would refuse.
 */
enum class ControlAvailability {
    UNKNOWN,
    ENABLED,
    DISABLED;

    /** True only when the daemon confirmed the voice backend honours the control. */
    val isEnabled: Boolean get() = this == ENABLED
}

/** Which daemon-side controls the voice backend actually honours for this call. */
data class LiveControls(
    val instruction: ControlAvailability = ControlAvailability.UNKNOWN,
    val pause: ControlAvailability = ControlAvailability.UNKNOWN,
    val summarize: ControlAvailability = ControlAvailability.UNKNOWN,
    val dtmf: ControlAvailability = ControlAvailability.UNKNOWN,
    val marks: ControlAvailability = ControlAvailability.UNKNOWN,
    val hangup: ControlAvailability = ControlAvailability.UNKNOWN
) {
    /** True once `/info` answered and every control has a confirmed answer. */
    val known: Boolean
        get() = listOf(instruction, pause, summarize, dtmf, marks, hangup)
            .none { it == ControlAvailability.UNKNOWN }

    /** `/info`'s booleans as the three-state answer the screen needs. */
    companion object {
        fun reported(voice: VoiceControls): LiveControls = LiveControls(
            instruction = confirmed(voice.instruction),
            pause = confirmed(voice.pause && voice.resume),
            summarize = confirmed(voice.summarize),
            dtmf = confirmed(voice.dtmf),
            marks = confirmed(voice.marks),
            hangup = confirmed(voice.hangup)
        )

        private fun confirmed(supported: Boolean): ControlAvailability = if (supported) ControlAvailability.ENABLED else ControlAvailability.DISABLED
    }
}

/** Everything the live call screen draws. The daemon is the only writer of the transcript. */
data class LiveCallUiState(
    val callId: String = "",
    val loaded: Boolean = false,
    val destination: String? = null,
    val farEnd: String? = null,
    val state: String? = null,
    /** The phone's own phase, used until the daemon reports one of its own. */
    val localPhase: CallPhase? = null,
    val callOutcome: String? = null,
    val resultStatus: String? = null,
    val activity: String? = null,
    val muted: Boolean = false,
    val turns: List<TranscriptTurn> = emptyList(),
    val partials: List<PartialTurn> = emptyList(),
    val marks: List<CallMark> = emptyList(),
    val summary: String? = null,
    val summaryFields: JsonObject? = null,
    val audioCodec: String? = null,
    /** The record's own connect time; null until a snapshot or event carries `connected_at`. */
    val connectedAtMs: Long? = null,
    /**
     * The bridge's own connect time for the call on screen, once it is known. The engine records
     * it once at the answer, so a screen opened mid-call counts the conversation from the connect
     * rather than from the moment it opened, and nothing here has to guess. A lost link can empty
     * the bridge's call before the record's own end arrives; what was known stays, because it
     * belongs to this call and the screen is still showing it.
     */
    val bridgeConnectedAtMs: Long? = null,
    val createdAtMs: Long? = null,
    /** When the call stopped, so the timer stops with it. */
    val endedAtMs: Long? = null,
    val audio: AudioState = AudioState(),
    val controls: LiveControls = LiveControls(),
    val instruction: String = "",
    val loading: Boolean = false,
    val reconnecting: Boolean = false,
    val summarizing: Boolean = false,
    val busy: Boolean = false,
    /** The hang-up reached the daemon; the call is ending until a terminal state arrives. */
    val hangingUp: Boolean = false,
    /**
     * The hang-up is waiting for the user's confirmation. It is screen state, not composition
     * memory, so it survives a configuration change; only the ViewModel clears or confirms it.
     */
    val pendingHangUp: Boolean = false,
    /**
     * A hang-up did not go through: [error] is that failure, and its card keeps the hang-up's own
     * wording whatever happened to the action beside it. A later attempt, or the call's end,
     * clears it.
     */
    val hangUpFailed: Boolean = false,
    /**
     * A hang-up did not go through, so the error offers the phone's own call screen: the daemon
     * could not end the call, but the platform still can. Hidden when the platform refuses, which
     * leaves [hangUpFailed] and its copy in place.
     */
    val callScreenAction: Boolean = false,
    val error: UiText? = null,
    /** The daemon's code for [error], shown behind the details disclosure. */
    val errorCode: String? = null,
    /**
     * The record's own error on a call whose end could not be confirmed: the evidence behind the
     * "Call status unknown" card, not another action failure.
     */
    val statusUnknownError: String? = null,
    val ended: Boolean = false,
    val missing: Boolean = false
) {
    val terminal: Boolean get() = ended

    /** The daemon's word wins, except where the phone is provably ahead of a stale record. */
    val phase: String? get() = reconcilePhase(state, daemonCallState(localPhase))

    /**
     * Where the header's count starts. Before the line is up it counts from placement, ring
     * included; the connection then restarts it at zero, because the count is the conversation,
     * the way the system's call chip begins at the answer. That reset is intentional: the ring the
     * chip never showed is not added back. A running call counts from the bridge's own connect
     * stamp, which the engine records once at the answer: that time is the same however late the
     * screen opens, and the record's `connected_at` from another clock is only the fallback. An
     * ended call's header is the record's connected duration; a bridge stamp it already knew stands
     * in while that duration is still on its way.
     */
    val timerStartMs: Long?
        get() = when {
            ended -> connectedAtMs ?: bridgeConnectedAtMs ?: createdAtMs
            else -> bridgeConnectedAtMs ?: connectedAtMs ?: createdAtMs
        }

    val callSecondsElapsed: Long?
        get() {
            val start = timerStartMs ?: return null
            val end = (if (ended) endedAtMs else null) ?: System.currentTimeMillis()
            return ((end - start).coerceAtLeast(0)) / 1000
        }
}

/**
 * The phase of the one call, from the daemon's state and the phone's own. Telecom dials the moment
 * the phone places the call, so a record still saying "preparing" while the phone reports dialing or
 * a connection is a stale snapshot, not a newer fact: the phone's phase advances it. A terminal
 * daemon state stays the authority, and so does a terminal phase from the phone here: the record's
 * own end, its end time, its timer and its controls travel together, so the screen may only show
 * "Ended" from the daemon's terminal snapshot or event, never from this reconciliation.
 */
internal fun reconcilePhase(daemon: String?, phone: String?): String? = when {
    daemon == null -> phone
    phone == null -> daemon
    daemon == "preparing" && phone in PHONE_PHASES_THAT_ADVANCE_PREPARING -> phone
    else -> daemon
}

/** The non-terminal phases Telecom reports for a call the phone already placed. */
private val PHONE_PHASES_THAT_ADVANCE_PREPARING = setOf("dialing", "active")

/**
 * The live call. The transcript, the state and the agent's activity all arrive through the
 * revisioned `events` long-poll: the cursor is the last applied revision, so a dropped link costs
 * nothing but a delay, and an event is applied once.
 */
@HiltViewModel
class LiveCallViewModel @Inject constructor(
    private val api: DaemonApi,
    private val bridgeState: BridgeStateRepository,
    private val callScreen: CallScreenOpener
) : ViewModel() {
    private val _state = MutableStateFlow(LiveCallUiState())
    val state: StateFlow<LiveCallUiState> = _state.asStateFlow()

    private var boundCallId: String? = null
    private var eventsJob: Job? = null
    private var audioJob: Job? = null
    private var revision = 0L
    private val seenSequences = mutableSetOf<Long>()

    /** Idempotent: a recomposition or a second entry for the same call must not restart the poll. */
    fun bind(callId: String) {
        if (callId.isBlank() || boundCallId == callId) return
        boundCallId = callId
        revision = 0
        seenSequences.clear()
        _state.value = LiveCallUiState(callId = callId, loading = true)
        collectBridge()
        viewModelScope.launch { loadInfo() }
        viewModelScope.launch {
            loadDetail(callId)
            eventsJob = launch { pollEvents(callId) }
        }
    }

    fun onInstructionChange(text: String) {
        _state.update { it.copy(instruction = text) }
    }

    /** Sends the one-line directive to the voice session; never spoken into the call. */
    fun tellAgent() {
        val snapshot = _state.value
        val text = snapshot.instruction.trim()
        if (text.isEmpty() || snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null, errorCode = null, callScreenAction = false, hangUpFailed = false) }
        viewModelScope.launch {
            when (val outcome = api.instruction(snapshot.callId, text)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(busy = false, instruction = "", error = null, errorCode = null, callScreenAction = false, hangUpFailed = false)
                }

                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = UiText.Literal(outcome.message), errorCode = outcome.code, callScreenAction = false, hangUpFailed = false) }
            }
        }
    }

    fun togglePause() {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null, errorCode = null, callScreenAction = false, hangUpFailed = false) }
        viewModelScope.launch {
            val outcome = if (snapshot.muted) api.resume(snapshot.callId) else api.pause(snapshot.callId)
            when (outcome) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(busy = false, muted = outcome.value.muted, error = null, errorCode = null, callScreenAction = false, hangUpFailed = false)
                }

                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = UiText.Literal(outcome.message), errorCode = outcome.code, callScreenAction = false, hangUpFailed = false) }
            }
        }
    }

    fun summarizeNow() {
        val snapshot = _state.value
        if (snapshot.summarizing || snapshot.hangingUp) return
        _state.update { it.copy(summarizing = true, error = null, errorCode = null, callScreenAction = false, hangUpFailed = false) }
        viewModelScope.launch {
            when (val outcome = api.summarize(snapshot.callId)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        summarizing = false,
                        summary = outcome.value.summary ?: it.summary,
                        summaryFields = outcome.value.fields ?: it.summaryFields
                    )
                }

                is ApiOutcome.Failure -> _state.update { it.copy(summarizing = false, error = UiText.Literal(outcome.message), errorCode = outcome.code, callScreenAction = false, hangUpFailed = false) }
            }
        }
    }

    fun sendDtmf(digits: String) {
        val snapshot = _state.value
        if (digits.isBlank() || snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null, errorCode = null, callScreenAction = false, hangUpFailed = false) }
        viewModelScope.launch {
            when (val outcome = api.dtmf(snapshot.callId, digits)) {
                is ApiOutcome.Ok -> _state.update { it.copy(busy = false) }
                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = UiText.Literal(outcome.message), errorCode = outcome.code, callScreenAction = false, hangUpFailed = false) }
            }
        }
    }

    fun mark(kind: String, note: String? = null) {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null, errorCode = null, callScreenAction = false, hangUpFailed = false) }
        viewModelScope.launch {
            when (val outcome = api.addMark(snapshot.callId, kind, note)) {
                is ApiOutcome.Ok -> _state.update { it.copy(busy = false, marks = outcome.value.marks) }
                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = UiText.Literal(outcome.message), errorCode = outcome.code, callScreenAction = false, hangUpFailed = false) }
            }
        }
    }

    /**
     * Asks for the confirmation a hang-up needs. The hang-up is offered for as long as the call is
     * live, whatever `/info` says about the voice backend's own `hangup`: the daemon owns its
     * call's state and cancels a dial that has not reached Telecom yet.
     */
    fun requestHangUp() {
        val snapshot = _state.value
        if (snapshot.missing || snapshot.busy || snapshot.hangingUp || snapshot.ended) return
        _state.update { it.copy(pendingHangUp = true) }
    }

    /** The user kept talking: the pending confirmation is dropped and the call runs on. */
    fun dismissHangUp() {
        _state.update { it.copy(pendingHangUp = false) }
    }

    /**
     * The only path from the confirmation to the daemon. The confirmation is single-use: the
     * pending value is consumed atomically before the operation starts, so a double tap cannot
     * hang the call up twice.
     */
    fun confirmHangUp() {
        val current = _state.value
        if (!current.pendingHangUp) return
        if (!_state.compareAndSet(current, current.copy(pendingHangUp = false))) return
        hangUp()
    }

    /**
     * Ends the call through the daemon endpoint, which owns the call's state and cancels a dial
     * that has not reached Telecom yet. It runs whatever `/info` reported about the voice
     * backend's own `hangup`. A failure releases the ending latch and offers the phone's call
     * screen, so the user can retry here or end the call from the platform.
     */
    fun hangUp() {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.hangingUp || snapshot.ended) return
        _state.update {
            it.copy(
                busy = true,
                hangingUp = true,
                pendingHangUp = false,
                callScreenAction = false,
                hangUpFailed = false,
                error = null,
                errorCode = null
            )
        }
        viewModelScope.launch {
            when (val outcome = api.hangup(snapshot.callId)) {
                is ApiOutcome.Ok -> {
                    // The answer may already be terminal; if it is only "ending", the events loop
                    // keeps the screen in the ending state until the daemon says ended.
                    applySnapshot(outcome.value)
                    _state.update { it.copy(busy = false) }
                }

                is ApiOutcome.Failure -> _state.update { current ->
                    // A late failure on a call that already ended is news about a call that is
                    // over: it releases the latch and installs nothing on the ended screen.
                    if (current.ended) {
                        current.copy(busy = false, hangingUp = false)
                    } else {
                        current.copy(
                            busy = false,
                            hangingUp = false,
                            hangUpFailed = true,
                            error = UiText.Literal(outcome.message),
                            errorCode = outcome.code,
                            callScreenAction = true
                        )
                    }
                }
            }
        }
    }

    /**
     * Brings up the system's own in-call screen, the way out when the daemon cannot end the call.
     * The platform answers a missing permission with a [SecurityException]; the opener reports
     * that as "not shown" and the action disappears rather than failing the same way twice.
     */
    fun openCallScreen() {
        if (!_state.value.callScreenAction) return
        if (!callScreen.show()) {
            _state.update { it.copy(callScreenAction = false) }
        }
    }

    /**
     * Re-reads the record after a lost link, so the transcript gap is filled from the daemon, and
     * asks `/info` again: an answer that never arrived is exactly why the controls are still
     * unknown, and a reload is when the user expects them to settle.
     */
    fun reload() {
        val callId = boundCallId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            loadInfo()
            loadDetail(callId)
        }
    }

    /**
     * The phone is the only writer of its own phase and audio: the engine has them the moment
     * Telecom dials, while the daemon's snapshot arrives over the link. Audio levels and the
     * codec/bitrate therefore always come from the bridge; the phase is a fallback for the daemon's
     * state, which stays authoritative once it arrives. The connect time comes from the bridge too,
     * recorded by the engine at the answer for the matching call.
     */
    private fun collectBridge() {
        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            bridgeState.state.collect { bridge ->
                val bound = boundCallId
                val call = bridge.call?.takeIf { bound != null && it.callId == bound }
                _state.update {
                    it.copy(
                        audio = bridge.audio,
                        localPhase = call?.phase,
                        // The engine's answer stamp belongs to the call on screen: a link loss that
                        // empties the bridge call, or an ended event that beats the final record,
                        // must not hand the count back to placement. Binding another call builds a
                        // fresh state, which is the only reset this needs.
                        bridgeConnectedAtMs = call?.connectedAtEpochMs ?: it.bridgeConnectedAtMs
                    )
                }
            }
        }
    }

    private suspend fun loadInfo() {
        val info = when (val outcome = api.info()) {
            is ApiOutcome.Ok -> outcome.value
            is ApiOutcome.Failure -> return
        }
        val voice: VoiceControls = info.voiceControls
        _state.update {
            it.copy(controls = LiveControls.reported(voice))
        }
    }

    private suspend fun loadDetail(callId: String) {
        when (val outcome = api.call(callId)) {
            is ApiOutcome.Ok -> {
                applySnapshot(outcome.value)
                _state.update { it.copy(loading = false, missing = false) }
            }

            is ApiOutcome.Failure -> _state.update {
                if (outcome.status == 404) {
                    // The empty state explains this; an extra invented sentence would not.
                    it.copy(loading = false, missing = true)
                } else {
                    it.copy(
                        loading = false,
                        reconnecting = true,
                        error = UiText.Literal(outcome.message),
                        errorCode = outcome.code
                    )
                }
            }
        }
    }

    private fun applySnapshot(snapshot: CallSnapshot) {
        val merged = mergeTurns(snapshot.transcript)
        _state.update {
            // A terminal state is the call's recorded end: a record read that answers late and
            // still says "preparing" may not reopen a call the transcript already closed.
            val state = if (terminalState(it.state) && !terminalState(snapshot.state)) {
                it.state
            } else {
                snapshot.state ?: it.state
            }
            val ended = it.ended || terminalState(state)
            it.copy(
                loaded = true,
                destination = snapshot.destination ?: it.destination,
                farEnd = snapshot.farEnd ?: it.farEnd,
                state = state,
                callOutcome = snapshot.callOutcome ?: it.callOutcome,
                resultStatus = snapshot.resultStatus ?: it.resultStatus,
                summary = snapshot.summary ?: it.summary,
                summaryFields = snapshot.fields.takeIf { fields -> fields.isNotEmpty() } ?: it.summaryFields,
                marks = snapshot.marks.ifEmpty { it.marks },
                turns = merged,
                partials = snapshot.currentPartials.ifEmpty { it.partials },
                audioCodec = snapshot.audioCodec ?: it.audioCodec,
                connectedAtMs = snapshot.connectedAt?.let { seconds -> (seconds * 1000).toLong() } ?: it.connectedAtMs,
                createdAtMs = snapshot.createdAt?.let { seconds -> (seconds * 1000).toLong() } ?: it.createdAtMs,
                endedAtMs = snapshot.endedAt?.let { seconds -> (seconds * 1000).toLong() }
                    ?: it.endedAtMs ?: if (ended) System.currentTimeMillis() else null,
                // The record keeps the reason its end could not be confirmed; a resolved call
                // drops it rather than leaving a stale card on screen.
                statusUnknownError = when {
                    state == "unknown" -> snapshot.error ?: it.statusUnknownError
                    else -> null
                },
                ended = ended,
                hangingUp = it.hangingUp && !ended,
                pendingHangUp = it.pendingHangUp && !ended
            ).withCallEnded(ended)
        }
    }

    private suspend fun pollEvents(callId: String) {
        var failures = 0
        while (currentCoroutineContext().isActive && !_state.value.terminal) {
            when (val outcome = api.events(callId, since = revision, waitSeconds = EVENTS_WAIT_SECONDS)) {
                is ApiOutcome.Ok -> {
                    failures = 0
                    val page = outcome.value
                    if (page.truncated) {
                        // The feed dropped events we never saw: the record is the only complete copy.
                        loadDetail(callId)
                    } else {
                        // Exactly the daemon's own cursor rule: one event is applied once.
                        applyEvents(freshEvents(page.events, revision))
                    }
                    revision = maxOf(revision, page.revision)
                    _state.update {
                        val ended = terminalState(page.state) || it.ended
                        it.copy(
                            reconnecting = false,
                            state = page.state ?: it.state,
                            callOutcome = page.callOutcome ?: it.callOutcome,
                            resultStatus = page.resultStatus ?: it.resultStatus,
                            endedAtMs = it.endedAtMs ?: if (ended) System.currentTimeMillis() else null,
                            ended = ended,
                            hangingUp = it.hangingUp && !ended,
                            pendingHangUp = it.pendingHangUp && !ended
                        ).withCallEnded(ended)
                    }
                }

                is ApiOutcome.Failure -> {
                    failures++
                    _state.update { it.copy(reconnecting = true) }
                    delay(backoffMs(failures))
                }
            }
        }
        // The call is over: pick up the final summary, fields and marks once.
        if (_state.value.terminal) loadDetail(callId)
    }

    private fun applyEvents(events: List<CallEvent>) {
        if (events.isEmpty()) return
        _state.value = reduceCallEvents(_state.value, events, seenSequences)
    }

    private fun mergeTurns(incoming: List<TranscriptTurn>): List<TranscriptTurn> {
        val merged = _state.value.turns.toMutableList()
        for (turn in incoming) {
            if (!seenSequences.add(turn.sequence)) continue
            merged += turn
        }
        return merged.sortedBy { it.sequence }
    }

    private fun terminalState(state: String?): Boolean = isTerminalState(state)

    private fun backoffMs(failures: Int): Long = (FAILURE_BACKOFF_MS * failures.coerceIn(1, 5)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val EVENTS_WAIT_SECONDS = 25.0
        const val FAILURE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
    }
}

/**
 * The daemon's cursor rule: only events past [since] are new, and an event without a
 * revision (decoded as 0) is never applied.  The contract test drives the shared
 * `call_events_*` pages through this function before reducing them, so an event that
 * loses its revision is caught here instead of silently never reaching the screen.
 */
internal fun freshEvents(events: List<CallEvent>, since: Long): List<CallEvent> = events.filter { it.revision > since }

/**
 * One batch of polled events applied to [state], in the order the cursor delivered
 * them. [applied] holds the transcript sequences already drawn, so a repeated page
 * writes nothing twice; the caller keeps it for the call's whole life.
 *
 * Every event kind the daemon sends has a branch here: the contract test drives the
 * shared `call_events_*` pages through this function and requires each one to change
 * the state, so a renamed type or payload key cannot fall through unnoticed.
 */
internal fun reduceCallEvents(
    state: LiveCallUiState,
    events: List<CallEvent>,
    applied: MutableSet<Long>
): LiveCallUiState {
    var next = state
    var turns: List<TranscriptTurn>? = null
    var partials: List<PartialTurn>? = null
    for (event in events) {
        when (event.type) {
            "transcript" -> {
                val sequence = event.sequence ?: continue
                if (!applied.add(sequence)) continue
                val turn = TranscriptTurn(
                    sequence = sequence,
                    role = event.role,
                    text = event.text,
                    at = event.at?.let(::parseIsoSeconds),
                    finalTurn = event.finalTurn ?: true
                )
                turns = (turns ?: state.turns) + turn
                partials = (partials ?: state.partials).filterNot { it.role == event.role }
            }

            "partial" -> {
                val others = (partials ?: state.partials).filterNot { it.role == event.role }
                partials = if (event.text.isNullOrBlank()) {
                    others
                } else {
                    others + PartialTurn(role = event.role, text = event.text, at = null)
                }
            }

            "marks" -> next = next.copy(marks = event.marks ?: next.marks)

            "muted" -> next = next.copy(muted = event.muted ?: next.muted)

            "agent_activity" -> next = next.copy(activity = event.activity)

            "result" -> next = next.copy(
                summary = event.summary ?: next.summary,
                summaryFields = event.fields ?: next.summaryFields,
                resultStatus = event.resultStatus ?: next.resultStatus,
                callOutcome = event.callOutcome ?: next.callOutcome
            )

            "state" -> {
                val ended = isTerminalState(event.state) || next.ended
                next = next.copy(
                    state = event.state ?: next.state,
                    callOutcome = event.callOutcome ?: next.callOutcome,
                    resultStatus = event.resultStatus ?: next.resultStatus,
                    endedAtMs = next.endedAtMs ?: if (ended) event.secondsEpochMs() else null,
                    ended = ended,
                    hangingUp = next.hangingUp && !ended,
                    pendingHangUp = next.pendingHangUp && !ended
                ).withCallEnded(ended)
            }

            "ended" -> next = next.copy(
                state = event.state ?: next.state,
                callOutcome = event.callOutcome ?: next.callOutcome,
                resultStatus = event.resultStatus ?: next.resultStatus,
                endedAtMs = next.endedAtMs ?: event.secondsEpochMs(),
                ended = true,
                hangingUp = false,
                pendingHangUp = false
            ).withCallEnded(true)
        }
    }
    turns?.let { list -> next = next.copy(turns = list) }
    partials?.let { list -> next = next.copy(partials = list) }
    return next
}

/** The event's own timestamp in epoch milliseconds; "now" when the daemon sent none. */
private fun CallEvent.secondsEpochMs(): Long = at?.let(::parseIsoSeconds)?.let { seconds -> (seconds * 1000).toLong() } ?: System.currentTimeMillis()

/** A daemon call state that ends the call. */
private val TERMINAL_STATES = setOf("ended", "cancelled", "failed", "unknown")

private fun isTerminalState(state: String?): Boolean = state in TERMINAL_STATES

/**
 * The call's own end retires the hang-up failure: its card and its action both describe a call
 * that is still up, so neither outlives that call. A failure of any other action is left alone.
 */
internal fun LiveCallUiState.withCallEnded(ended: Boolean): LiveCallUiState {
    if (!ended || !hangUpFailed) return this
    return copy(hangUpFailed = false, callScreenAction = false, error = null, errorCode = null)
}

/** `2026-11-26T10:00:00+00:00` → epoch seconds; the transcript only needs the relative order. */
internal fun parseIsoSeconds(value: String): Double? {
    val seconds = runCatching { java.time.OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()
    return seconds?.toDouble()
}
