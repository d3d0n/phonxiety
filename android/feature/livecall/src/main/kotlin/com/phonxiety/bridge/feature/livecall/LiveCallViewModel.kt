package com.phonxiety.bridge.feature.livecall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.daemonCallState
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

/** Which daemon-side controls the voice backend actually honours for this call. */
data class LiveControls(
    val instruction: Boolean = true,
    val pause: Boolean = true,
    val summarize: Boolean = true,
    val dtmf: Boolean = true,
    val marks: Boolean = true,
    val hangup: Boolean = true
)

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
    val connectedAtMs: Long? = null,
    val createdAtMs: Long? = null,
    /** When the call stopped, so the timer stops with it. */
    val endedAtMs: Long? = null,
    val audio: AudioState = AudioState(),
    val controls: LiveControls = LiveControls(),
    val controlsKnown: Boolean = false,
    val instruction: String = "",
    val loading: Boolean = false,
    val reconnecting: Boolean = false,
    val summarizing: Boolean = false,
    val busy: Boolean = false,
    /** The hang-up reached the daemon; the call is ending until a terminal state arrives. */
    val hangingUp: Boolean = false,
    val error: String? = null,
    val ended: Boolean = false,
    val missing: Boolean = false
) {
    val terminal: Boolean get() = ended

    /** The daemon's word wins; the phone's own phase fills in until the daemon reports one. */
    val phase: String? get() = state ?: daemonCallState(localPhase)

    val callSecondsElapsed: Long?
        get() {
            val start = connectedAtMs ?: createdAtMs ?: return null
            val end = (if (ended) endedAtMs else null) ?: System.currentTimeMillis()
            return ((end - start).coerceAtLeast(0)) / 1000
        }
}

/**
 * The live call. The transcript, the state and the agent's activity all arrive through the
 * revisioned `events` long-poll: the cursor is the last applied revision, so a dropped link costs
 * nothing but a delay, and an event is applied once.
 */
@HiltViewModel
class LiveCallViewModel @Inject constructor(
    private val api: DaemonApi,
    private val bridgeState: BridgeStateRepository
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
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val outcome = api.instruction(snapshot.callId, text)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(busy = false, instruction = "", error = null)
                }

                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = outcome.message) }
            }
        }
    }

    fun togglePause() {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = if (snapshot.muted) api.resume(snapshot.callId) else api.pause(snapshot.callId)
            when (outcome) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(busy = false, muted = outcome.value.muted, error = null)
                }

                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = outcome.message) }
            }
        }
    }

    fun summarizeNow() {
        val snapshot = _state.value
        if (snapshot.summarizing || snapshot.hangingUp) return
        _state.update { it.copy(summarizing = true, error = null) }
        viewModelScope.launch {
            when (val outcome = api.summarize(snapshot.callId)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        summarizing = false,
                        summary = outcome.value.summary ?: it.summary,
                        summaryFields = outcome.value.fields ?: it.summaryFields
                    )
                }

                is ApiOutcome.Failure -> _state.update { it.copy(summarizing = false, error = outcome.message) }
            }
        }
    }

    fun sendDtmf(digits: String) {
        val snapshot = _state.value
        if (digits.isBlank() || snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val outcome = api.dtmf(snapshot.callId, digits)) {
                is ApiOutcome.Ok -> _state.update { it.copy(busy = false) }
                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = outcome.message) }
            }
        }
    }

    fun mark(kind: String, note: String? = null) {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.hangingUp) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val outcome = api.addMark(snapshot.callId, kind, note)) {
                is ApiOutcome.Ok -> _state.update { it.copy(busy = false, marks = outcome.value.marks) }
                is ApiOutcome.Failure -> _state.update { it.copy(busy = false, error = outcome.message) }
            }
        }
    }

    fun hangUp() {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.hangingUp || snapshot.ended) return
        _state.update { it.copy(busy = true, hangingUp = true, error = null) }
        viewModelScope.launch {
            when (val outcome = api.hangup(snapshot.callId)) {
                is ApiOutcome.Ok -> {
                    // The answer may already be terminal; if it is only "ending", the events loop
                    // keeps the screen in the ending state until the daemon says ended.
                    applySnapshot(outcome.value)
                    _state.update { it.copy(busy = false) }
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(busy = false, hangingUp = false, error = "${outcome.message} (${outcome.code})")
                }
            }
        }
    }

    /** Re-reads the record after a lost link, so the transcript gap is filled from the daemon. */
    fun reload() {
        val callId = boundCallId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            loadDetail(callId)
        }
    }

    /**
     * The phone is the only writer of its own phase and audio: the engine has them the moment
     * Telecom dials, while the daemon's snapshot arrives over the link. Audio levels and the
     * codec/bitrate therefore always come from the bridge; the phase is a fallback for the daemon's
     * state, which stays authoritative once it arrives.
     */
    private fun collectBridge() {
        audioJob?.cancel()
        audioJob = viewModelScope.launch {
            bridgeState.state.collect { bridge ->
                val bound = boundCallId
                val localPhase = bridge.call?.takeIf { bound != null && it.callId == bound }?.phase
                _state.update { it.copy(audio = bridge.audio, localPhase = localPhase) }
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
            it.copy(
                controls = LiveControls(
                    instruction = voice.instruction,
                    pause = voice.pause && voice.resume,
                    summarize = voice.summarize,
                    dtmf = voice.dtmf,
                    marks = voice.marks,
                    hangup = voice.hangup
                ),
                controlsKnown = true
            )
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
                    it.copy(loading = false, missing = true, error = "the daemon does not know this call")
                } else {
                    it.copy(loading = false, reconnecting = true, error = outcome.message)
                }
            }
        }
    }

    private fun applySnapshot(snapshot: CallSnapshot) {
        val merged = mergeTurns(snapshot.transcript)
        _state.update {
            val ended = it.ended || terminalState(it.state ?: snapshot.state)
            it.copy(
                loaded = true,
                destination = snapshot.destination ?: it.destination,
                farEnd = snapshot.farEnd ?: it.farEnd,
                state = snapshot.state ?: it.state,
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
                ended = ended,
                hangingUp = it.hangingUp && !ended
            )
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
                        applyEvents(page.events.filter { it.revision > revision })
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
                            hangingUp = it.hangingUp && !ended
                        )
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
        var turns: List<TranscriptTurn>? = null
        var partials: List<PartialTurn>? = null
        for (event in events) {
            when (event.type) {
                "transcript" -> {
                    val sequence = event.sequence ?: continue
                    if (!seenSequences.add(sequence)) continue
                    val turn = TranscriptTurn(
                        sequence = sequence,
                        role = event.role,
                        text = event.text,
                        at = event.at?.let(::parseIsoSeconds),
                        finalTurn = event.finalTurn ?: true
                    )
                    turns = (turns ?: _state.value.turns) + turn
                    partials = (partials ?: _state.value.partials).filterNot { it.role == event.role }
                }

                "partial" -> {
                    val others = (partials ?: _state.value.partials).filterNot { it.role == event.role }
                    partials = if (event.text.isNullOrBlank()) {
                        others
                    } else {
                        others + PartialTurn(role = event.role, text = event.text, at = null)
                    }
                }

                "marks" -> _state.update { it.copy(marks = event.marks ?: it.marks) }

                "muted" -> _state.update { it.copy(muted = event.muted ?: it.muted) }

                "agent_activity" -> _state.update { it.copy(activity = event.activity) }

                "result" -> _state.update {
                    it.copy(
                        summary = event.summary ?: it.summary,
                        summaryFields = event.fields ?: it.summaryFields,
                        resultStatus = event.resultStatus ?: it.resultStatus,
                        callOutcome = event.callOutcome ?: it.callOutcome
                    )
                }

                "state" -> _state.update {
                    val ended = terminalState(event.state) || it.ended
                    it.copy(
                        state = event.state ?: it.state,
                        callOutcome = event.callOutcome ?: it.callOutcome,
                        resultStatus = event.resultStatus ?: it.resultStatus,
                        endedAtMs = it.endedAtMs ?: if (ended) event.secondsEpochMs() else null,
                        ended = ended,
                        hangingUp = it.hangingUp && !ended
                    )
                }

                "ended" -> _state.update {
                    it.copy(
                        state = event.state ?: it.state,
                        callOutcome = event.callOutcome ?: it.callOutcome,
                        resultStatus = event.resultStatus ?: it.resultStatus,
                        endedAtMs = it.endedAtMs ?: event.secondsEpochMs(),
                        ended = true,
                        hangingUp = false
                    )
                }
            }
        }
        turns?.let { list -> _state.update { it.copy(turns = list) } }
        partials?.let { list -> _state.update { it.copy(partials = list) } }
    }

    /** The event's own timestamp in epoch milliseconds; "now" when the daemon sent none. */
    private fun CallEvent.secondsEpochMs(): Long = at?.let(::parseIsoSeconds)?.let { seconds -> (seconds * 1000).toLong() } ?: System.currentTimeMillis()

    private fun mergeTurns(incoming: List<TranscriptTurn>): List<TranscriptTurn> {
        val merged = _state.value.turns.toMutableList()
        for (turn in incoming) {
            if (!seenSequences.add(turn.sequence)) continue
            merged += turn
        }
        return merged.sortedBy { it.sequence }
    }

    private fun terminalState(state: String?): Boolean = state in TERMINAL_STATES

    private fun backoffMs(failures: Int): Long = (FAILURE_BACKOFF_MS * failures.coerceIn(1, 5)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val EVENTS_WAIT_SECONDS = 25.0
        const val FAILURE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
        val TERMINAL_STATES = setOf("ended", "cancelled", "failed", "unknown")
    }
}

/** `2026-11-26T10:00:00+00:00` → epoch seconds; the transcript only needs the relative order. */
internal fun parseIsoSeconds(value: String): Double? {
    val seconds = runCatching { java.time.OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()
    return seconds?.toDouble()
}
