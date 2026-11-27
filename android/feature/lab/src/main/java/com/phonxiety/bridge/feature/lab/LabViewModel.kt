package com.phonxiety.bridge.feature.lab

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.PhonePreferences
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.PairingGate
import com.phonxiety.bridge.model.ProbeCandidate
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.model.TestCallTone
import com.phonxiety.bridge.net.CandidateMerge
import com.phonxiety.bridge.net.LanPathProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The test lab: every tool calls the daemon's app API and keeps the numbers locally, so the
 * screen says what was measured rather than what the link looked like.
 */
@HiltViewModel
class LabViewModel @Inject constructor(
    private val api: DaemonApi,
    bridgeState: BridgeStateRepository,
    private val phonePreferences: PhonePreferences,
    private val lanPathCheck: LanPathCheck,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val history = LabHistoryStore(File(context.filesDir, "lab/link-tests.json"))
    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    /**
     * The one read of the accepted call's record; a new placement, and leaving the Lab, stop it.
     * A stopped read leaves what it already published in place, and [onLabShown] resumes it.
     */
    private var resultJob: Job? = null

    /** The call [resultJob] reads, so a second hint for the same call does not start another read. */
    private var resultCallId: String? = null

    /** Which placement the running read belongs to; an older read may not publish over a newer call. */
    private var placement = 0

    /**
     * Whether the Lab is on screen. The view model outlives a push to the call record, and its
     * bridge collector keeps running there: a hidden Lab starts no read and publishes nothing.
     */
    private var visible = true

    /** How long the record of a finished test call waits between refetches; tests shorten it. */
    @VisibleForTesting
    internal var resultRetryDelayMs: Long = RESULT_RETRY_MS

    /** The newest number still to be stored; the store keeps one pending value, so a burst is one write. */
    private val pendingNumberWrite = MutableStateFlow<String?>(null)

    /** A keystroke landed: a seed read that answers afterwards may not replace what was typed. */
    private var numberEdited = false

    init {
        viewModelScope.launch {
            _state.update { it.copy(link = it.link.copy(history = history.load())) }
        }
        viewModelScope.launch {
            // Storage seeds the field once. It is a copy of what was typed, never a second writer:
            // replaying a persisted value would undo every keystroke that landed before the answer.
            val stored = phonePreferences.settings.first()
            if (!numberEdited) {
                _state.update { it.copy(testCall = it.testCall.copy(number = stored.testNumber)) }
            }
        }
        viewModelScope.launch {
            pendingNumberWrite.filterNotNull().collect { number ->
                phonePreferences.update { it.copy(testNumber = number) }
            }
        }
        viewModelScope.launch {
            bridgeState.state.collect { bridge -> applyBridgeState(bridge) }
        }
        viewModelScope.launch {
            BridgeLog.revision.collect { refreshLogs() }
        }
        viewModelScope.launch {
            when (val outcome = api.info()) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        loopbackSupported = outcome.value.voiceControls.loopback,
                        toneSupported = outcome.value.voiceControls.tone,
                        testCall = it.testCall.copy(probesSupported = outcome.value.voiceControls.testCallProbes)
                    )
                }

                is ApiOutcome.Failure -> Unit // the tools stay enabled and report the daemon's own answer
            }
        }
    }

    private fun applyBridgeState(bridge: BridgeState) {
        val phase = bridge.call?.phase?.name?.lowercase(Locale.US)
        val callId = bridge.call?.callId
        _state.update { it.copy(callPhase = phase, callId = callId) }
        // The accepted call's record is watched from placement, because a test call can fail
        // before Telecom ever reports it here. This phone's ENDED only asks for the read the
        // record may already answer.
        if (phase == PHASE_ENDED && callId != null && callId == _state.value.testCall.callId) {
            readTestCallResult(callId)
        }
    }

    /**
     * The Lab is on screen again: resume a read the screen took with it when it left. A record
     * that was already read stays as it was fetched rather than being read again.
     */
    fun onLabShown() {
        visible = true
        val callId = _state.value.testCall.callId ?: return
        readTestCallResult(callId)
    }

    /** The Lab left the screen — a push to the call record included: nothing of its read may land. */
    fun onLabHidden() {
        visible = false
        resultJob?.cancel()
        resultJob = null
        resultCallId = null
    }

    // -- link test ------------------------------------------------------

    fun setLinkSeconds(seconds: Float) = _state.update {
        it.copy(link = it.link.copy(seconds = seconds.coerceIn(1f, 30f)))
    }

    fun setLinkCodec(codec: String?) = _state.update { it.copy(link = it.link.copy(codec = codec)) }

    fun setLinkTransport(transport: String) = _state.update {
        it.copy(link = it.link.copy(transport = if (transport == "datagram") "datagram" else "stream"))
    }

    fun runLinkTest() {
        val options = _state.value.link
        if (options.running) return
        _state.update { it.copy(link = it.link.copy(running = true, error = null)) }
        viewModelScope.launch {
            when (val outcome = api.linkTest(options.seconds.toDouble(), options.codec, options.transport)) {
                is ApiOutcome.Ok -> {
                    val stored = history.append(outcome.value)
                    _state.update {
                        it.copy(link = it.link.copy(running = false, last = outcome.value, history = stored, error = null))
                    }
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(link = it.link.copy(running = false, error = outcome.failure()))
                }
            }
        }
    }

    // -- probe ----------------------------------------------------------

    fun toggleProbeCandidate(name: String) = _state.update {
        val selected = it.probe.selected.toMutableSet()
        if (!selected.add(name)) selected.remove(name)
        it.copy(probe = it.probe.copy(selected = selected))
    }

    fun runProbe() {
        val selected = _state.value.probe.selected
        if (selected.isEmpty() || _state.value.probe.running) return
        _state.update { it.copy(probe = it.probe.copy(running = true, error = null)) }
        viewModelScope.launch {
            when (val outcome = api.probe(selected.sorted())) {
                is ApiOutcome.Ok -> {
                    val candidates = outcome.value.candidates
                    val rows = if ("lan" in selected) withPhoneCheck(candidates) else candidates.map(::daemonRow)
                    _state.update {
                        it.copy(
                            probe = it.probe.copy(
                                running = false,
                                rows = rows,
                                checkedAt = outcome.value.checkedAt,
                                error = null
                            )
                        )
                    }
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(probe = it.probe.copy(running = false, error = outcome.failure()))
                }
            }
        }
    }

    /** One of the bridge's own candidate rows: reached, not reached, or a path it did not measure. */
    private fun daemonRow(candidate: ProbeCandidate): ProbeRowUi {
        val endpoint = candidate.address?.let(::hostPort)
        val rttMs = candidate.rttMs?.roundToLong()
        // The bridge marks a path it never probed with "not_measured": that is a third state, not
        // an unreachable one, and it never carries an RTT.
        val notMeasured = candidate.reason == NOT_MEASURED
        // The daemon's own words stay available verbatim under Details, whatever the verdict says.
        val details = listOfNotNull(
            candidate.detail?.takeIf { it.isNotBlank() },
            candidate.reason?.takeIf { it.isNotBlank() }?.let { "reason: $it" }
        ).joinToString("\n").ifBlank { null }
        return when {
            notMeasured -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.NOT_CHECKED,
                note = reasonText(candidate.reason),
                details = details
            )

            candidate.available && candidate.reachable -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.REACHED,
                rttMs = rttMs,
                note = rttMs?.let { UiText.Res(R.string.lab_probe_note_bridge_reached, listOf(millisText(it))) }
                    ?: candidate.detail?.let { UiText.Literal(it) },
                details = details
            )

            candidate.available -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.NOT_REACHED,
                note = reasonText(candidate.reason) ?: UiText.Res(R.string.lab_probe_reason_listener),
                details = details
            )

            else -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.NOT_CHECKED,
                note = reasonText(candidate.reason) ?: UiText.Res(R.string.lab_probe_reason_none),
                details = details
            )
        }
    }

    /**
     * Replaces the bridge's LAN answer with this phone's own measurement.
     *
     * The bridge can only report whether its listener answers on its own LAN address; the question
     * the lab asks is whether this phone's network reaches that address. The same pinned TLS
     * handshake link selection uses answers it from here. A check that could not run leaves the row
     * "Not checked" with the reason; only a check that ran and failed makes it "Not reached".
     */
    private suspend fun withPhoneCheck(candidates: List<ProbeCandidate>): List<ProbeRowUi> {
        val rows = candidates.map(::daemonRow)
        val lan = candidates.firstOrNull { it.name == "lan" } ?: return rows
        // The addresses and the credential that probes them must come from the
        // same pairing.  A re-pair while the answer was in flight is refused
        // before any probe: the pinned handshake would otherwise carry the new
        // pairing's device token to the previous daemon.
        val epoch = PairingGate.epoch()
        val expected = PairingGate.expectedIdentity()
        val info = (api.info() as? ApiOutcome.Ok)?.value
        val urls = info?.connection?.lan.orEmpty()
        val pin = info?.connection?.certificate
        val stored = BridgeConfigLoader.loadOrNull(context)
        // The pinned TLS handshake authenticates with the DEVICE credential the
        // daemon issued, never the app-API token: only the stored pairing carries
        // it, and only while the gate still expects that very pairing.
        val token = stored
            ?.takeIf { expected != null && CandidateMerge.identity(it) == expected }
            ?.token
        val stale = PairingGate.epoch() != epoch
        val probes = if (!stale && urls.isNotEmpty() && token != null) {
            withContext(Dispatchers.IO) { runCatching { lanPathCheck.probe(urls, pin, token) }.getOrNull() }
        } else {
            null
        }
        // The pairing may have moved while the handshake ran: the result then
        // belongs to the previous daemon and replaces nothing.
        val stillCurrent = PairingGate.epoch() == epoch && PairingGate.expectedIdentity() == expected
        val answered = probes?.firstOrNull { it.reachable }
        val updated = when {
            stale || !stillCurrent -> notChecked(lan, R.string.lab_probe_note_stale)

            info == null -> notChecked(lan, R.string.lab_probe_note_no_info)

            urls.isEmpty() -> notChecked(lan, R.string.lab_probe_note_no_lan)

            token == null -> notChecked(lan, R.string.lab_probe_note_unpaired)

            probes == null -> notChecked(lan, R.string.lab_probe_note_check_failed)

            probes.isEmpty() -> notChecked(lan, R.string.lab_probe_note_no_result)

            answered != null -> ProbeRowUi(
                key = lan.name,
                endpoint = hostPort(answered.address),
                verdict = ProbeVerdict.REACHED,
                rttMs = answered.rttMs,
                note = UiText.Res(
                    R.string.lab_probe_note_reached,
                    listOf(hostPort(answered.address), millisText(answered.rttMs ?: 0L))
                )
            )

            else -> ProbeRowUi(
                key = lan.name,
                endpoint = hostPort(urls.first()),
                verdict = ProbeVerdict.NOT_REACHED,
                note = UiText.Res(R.string.lab_probe_note_unreached, listOf(hostPort(urls.first()))),
                listenerNote = listenerNote(lan)
            )
        }
        // This phone's own check replaces the verdict; the daemon's diagnostics stay under Details.
        return rows.map { if (it.key == lan.name) updated.copy(details = updated.details ?: it.details) else it }
    }

    /** A row this phone could not measure; the bridge's own listener answer stays a second line. */
    private fun notChecked(candidate: ProbeCandidate, @StringRes note: Int): ProbeRowUi = ProbeRowUi(
        key = candidate.name,
        endpoint = candidate.address?.let(::hostPort),
        verdict = ProbeVerdict.NOT_CHECKED,
        note = UiText.Res(note),
        listenerNote = listenerNote(candidate)
    )

    private fun listenerNote(candidate: ProbeCandidate): UiText = UiText.Res(
        if (candidate.reachable) R.string.lab_probe_listener_answers else R.string.lab_probe_listener_silent
    )

    private fun reasonText(reason: String?): UiText? = when (reason) {
        null, "" -> null
        "not_measured" -> UiText.Res(R.string.lab_probe_reason_not_measured)
        "not_installed" -> UiText.Res(R.string.lab_probe_reason_not_installed)
        "not_connected" -> UiText.Res(R.string.lab_probe_reason_not_connected)
        "no_lan_address" -> UiText.Res(R.string.lab_probe_reason_no_lan_address)
        "no_listener_answer" -> UiText.Res(R.string.lab_probe_reason_listener)
        "unreachable_from_phone" -> UiText.Res(R.string.lab_probe_reason_unreachable_phone)
        "inconclusive" -> UiText.Res(R.string.lab_probe_reason_unchecked)
        else -> UiText.Literal(humanize(reason))
    }

    // -- loopback and tone ----------------------------------------------

    fun setLoopback(enabled: Boolean) {
        val current = _state.value
        if (!current.audioEnabled || current.audio.busy) return
        _state.update { it.copy(audio = it.audio.copy(busy = true, error = null)) }
        viewModelScope.launch {
            when (val outcome = api.loopback(enabled, current.callId)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        audio = it.audio.copy(
                            busy = false,
                            loopbackOn = outcome.value.enabled,
                            note = if (outcome.value.enabled) UiText.Res(R.string.lab_audio_on) else null
                        )
                    )
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(audio = it.audio.copy(busy = false, error = outcome.failure()))
                }
            }
        }
    }

    fun setToneHz(hz: Int) = _state.update { it.copy(audio = it.audio.copy(toneHz = hz.coerceIn(100, 4_000))) }

    fun setToneDbfs(dbfs: Double) = _state.update { it.copy(audio = it.audio.copy(toneDbfs = dbfs.coerceIn(-60.0, 0.0))) }

    fun playTone() {
        val current = _state.value
        if (!current.audioEnabled || current.audio.busy) return
        _state.update { it.copy(audio = it.audio.copy(busy = true, error = null)) }
        viewModelScope.launch {
            when (val outcome = api.tone(current.audio.toneHz, current.audio.toneDbfs, TONE_SECONDS, current.callId)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        audio = it.audio.copy(
                            busy = false,
                            note = UiText.Res(R.string.lab_audio_played, listOf(TONE_SECONDS.toInt()))
                        )
                    )
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(audio = it.audio.copy(busy = false, error = outcome.failure()))
                }
            }
        }
    }

    // -- test call ------------------------------------------------------

    fun setTestNumber(number: String) {
        // The draft is what the field shows. A leading `+` survives on its own, so the first
        // keystroke of an international number is no longer erased; storage and dialling keep
        // digits with one optional leading `+`.
        val draft = PhoneSettings.draftNumber(number)
        numberEdited = true
        // The draft is authoritative the moment it changes; the write is a consequence of it.
        _state.update { it.copy(testCall = it.testCall.copy(number = draft, error = null)) }
        pendingNumberWrite.value = PhoneSettings.sanitizeNumber(draft)
    }

    fun requestTestCall() {
        val current = _state.value.testCall
        if (!current.numberReady) return
        _state.update { it.copy(testCall = current.copy(confirming = true)) }
    }

    fun dismissTestCall() = _state.update { it.copy(testCall = it.testCall.copy(confirming = false)) }

    fun setTestCallLoopback(enabled: Boolean) = _state.update {
        it.copy(testCall = it.testCall.copy(loopback = enabled))
    }

    fun setTestCallTone(enabled: Boolean) = _state.update {
        it.copy(testCall = it.testCall.copy(tone = enabled))
    }

    /** The daemon accepts 1 to 10 s, and the call's own limit has to outlast the tone. */
    fun setTestCallToneSeconds(seconds: Int) = _state.update {
        it.copy(testCall = it.testCall.copy(toneSeconds = seconds.coerceIn(1, 10)))
    }

    fun confirmTestCall() {
        val request = _state.value.testCall
        if (request.placing) return
        val payload = _state.value.toRequest()
        // This placement owns the record from here: the previous call's read stops and may not
        // publish, and its id is gone with it.
        placement++
        resultJob?.cancel()
        resultJob = null
        resultCallId = null
        _state.update {
            it.copy(
                testCall = it.testCall.copy(
                    confirming = false,
                    placing = true,
                    error = null,
                    result = null,
                    callId = null
                )
            )
        }
        // From this moment Telecom's in-call screen may cover the lab, and only this phone's own
        // placement may pull it back over that screen.
        LiveCallFront.expectPlacement()
        viewModelScope.launch {
            when (val outcome = api.testCall(payload)) {
                is ApiOutcome.Ok -> {
                    // This phone asked for this call: only then may the lab come back over Telecom's
                    // in-call UI, and a second cover of the same call finds the token spent.
                    LiveCallFront.remember(outcome.value.callId)
                    _state.update {
                        it.copy(
                            testCall = it.testCall.copy(placing = false, callId = outcome.value.callId),
                            message = UiText.Res(R.string.lab_test_placed)
                        )
                    }
                    // Voice startup can fail before dialing, and then Telecom never reports the
                    // call here: the daemon's own record is what says the call is over.
                    readTestCallResult(outcome.value.callId)
                }

                is ApiOutcome.Failure -> {
                    LiveCallFront.cancelPlacement()
                    _state.update {
                        it.copy(testCall = it.testCall.copy(placing = false, error = outcome.failure()))
                    }
                }
            }
        }
    }

    /**
     * The probes travel only when the daemon advertises them and this phone has the one asked for:
     * an older daemon would read the extra keys as a validation error, and a phone without the
     * capability answers the whole placement with 409. The tone takes the frequency and level the
     * lab's audio card shows, and the length chosen on the test-call card.
     */
    private fun LabUiState.toRequest(): TestCallRequest = TestCallRequest(
        destination = PhoneSettings.sanitizeNumber(testCall.number),
        maxDurationSeconds = testCall.maxDurationSeconds,
        loopback = true.takeIf { testCallLoopback },
        tone = if (testCallTone) {
            TestCallTone(hz = audio.toneHz, dbfs = audio.toneDbfs, seconds = testCall.toneSeconds.coerceIn(1, 10))
        } else {
            null
        }
    )

    /**
     * Starts reading [callId]'s record unless the Lab is hidden or this call's record is already
     * read or being read. A published card that is not waiting is final, whether it holds the
     * summary, an error or the daemon's unfinished call: repeated hints never start a new read.
     */
    private fun readTestCallResult(callId: String) {
        if (!visible) return
        val result = _state.value.testCall.result
        if (result?.callId == callId && !result.waiting) return
        if (resultCallId == callId && resultJob?.isActive == true) return
        resultJob?.cancel()
        val generation = placement
        resultCallId = callId
        resultJob = viewModelScope.launch { collectTestCallResult(callId, generation) }
    }

    /**
     * Reads the accepted call's record until the daemon has finished the call and written its
     * summary.
     *
     * The phone's own ended state is not the only way a test call ends: when voice startup fails
     * before dialing, the daemon finalizes the call and Telecom never reports it to this screen.
     * So the record itself is watched for its terminal state, and the result card appears only
     * once the daemon calls the call over. The summary is then read in a bounded number of
     * attempts; [RESULT_ATTEMPTS] with the retry delay is the longest a person waits for it.
     *
     * A read that gives up says so: [RESULT_ATTEMPTS] failed reads in a row end on an error card,
     * and a call the daemon has not finished after [LIVE_POLLS] reads ends on an unfinished card.
     */
    private suspend fun collectTestCallResult(callId: String, generation: Int) {
        var polls = 0
        var failuresInARow = 0
        var record: CallSnapshot? = null
        var failure: LabFailure? = null
        var finished = false
        // Waiting for the daemon's verdict: the daemon caps a test call at 60 s, so a record that
        // outlives this is not one this screen is going to see finish.
        while (isCurrent(generation, callId)) {
            polls++
            when (val outcome = api.call(callId)) {
                is ApiOutcome.Ok -> {
                    record = outcome.value
                    failure = null
                    failuresInARow = 0
                    if (outcome.value.finished()) {
                        finished = true
                        break
                    }
                }

                is ApiOutcome.Failure -> {
                    failure = outcome.failure()
                    failuresInARow++
                }
            }
            if (failuresInARow >= RESULT_ATTEMPTS || polls >= LIVE_POLLS) break
            delay(resultRetryDelayMs)
        }
        if (!isCurrent(generation, callId)) return
        if (!finished) {
            val unreadable = failuresInARow >= RESULT_ATTEMPTS
            val card = (record?.toResultCard(callId) ?: TestCallResultUi(callId = callId)).copy(
                error = failure.takeIf { unreadable },
                unfinished = !unreadable
            )
            publish(generation) { it.copy(testCall = it.testCall.copy(result = card)) }
            return
        }
        publish(generation) {
            it.copy(testCall = it.testCall.copy(result = TestCallResultUi(callId = callId, waiting = true)))
        }
        var attempt = 1
        while (isCurrent(generation, callId)) {
            if (record?.resultStatus == RESULT_READY || attempt >= RESULT_ATTEMPTS) break
            attempt++
            delay(resultRetryDelayMs)
            when (val outcome = api.call(callId)) {
                is ApiOutcome.Ok -> {
                    record = outcome.value
                    failure = null
                }

                is ApiOutcome.Failure -> failure = outcome.failure()
            }
        }
        if (!isCurrent(generation, callId)) return
        val card = record?.toResultCard(callId) ?: TestCallResultUi(callId = callId, error = failure)
        publish(generation) { it.copy(testCall = it.testCall.copy(result = card)) }
    }

    private fun CallSnapshot.toResultCard(callId: String): TestCallResultUi = TestCallResultUi(
        callId = callId,
        outcome = callOutcome,
        resultStatus = resultStatus,
        summary = summary,
        durationSeconds = durationSeconds(),
        codec = audioCodec,
        waiting = false
    )

    /**
     * Whether a read still belongs to the current placement and the call the Lab accepted, while
     * the Lab is on screen.
     */
    private fun isCurrent(generation: Int, callId: String): Boolean = visible && generation == placement && _state.value.testCall.callId == callId

    /** Applies a read's answer only while its placement is current and the Lab is on screen. */
    private fun publish(generation: Int, change: (LabUiState) -> LabUiState) {
        if (!visible || generation != placement) return
        _state.update(change)
    }

    /** The daemon's terminal states: after one of these the record has what it will ever have. */
    private fun CallSnapshot.finished(): Boolean = state?.lowercase(Locale.US) in TERMINAL_STATES || resultStatus == RESULT_READY

    private fun CallSnapshot.durationSeconds(): Long? {
        val connected = connectedAt ?: return null
        val ended = endedAt ?: return null
        return (ended - connected).roundToLong().coerceAtLeast(0L)
    }

    // -- codec trial ----------------------------------------------------

    fun runCodecTrial() {
        if (_state.value.trial.running) return
        _state.update { it.copy(trial = CodecTrialUi(running = true, step = UiText.Res(R.string.lab_trial_step_opus_plain))) }
        viewModelScope.launch {
            val runs = mutableListOf<CodecTrialRun>()
            for (profile in TRIAL_PROFILES) {
                _state.update { it.copy(trial = it.trial.copy(step = profile.step())) }
                when (val outcome = api.linkTest(TRIAL_SECONDS, profile.codec, "stream", profile.bitrate)) {
                    is ApiOutcome.Ok -> runs += CodecTrialRun(profile.codec, profile.bitrate, outcome.value)

                    is ApiOutcome.Failure -> {
                        _state.update {
                            it.copy(trial = CodecTrialUi(running = false, runs = runs.toList(), error = outcome.failure()))
                        }
                        return@launch
                    }
                }
                _state.update { it.copy(trial = it.trial.copy(runs = runs.toList())) }
            }
            val recommendation = recommendTrial(runs)
            history.appendAll(runs.map { it.result })
            val stored = history.load()
            _state.update {
                it.copy(
                    trial = CodecTrialUi(runs = runs, recommendation = recommendation),
                    link = it.link.copy(history = stored)
                )
            }
        }
    }

    /** Writes the recommended profile into the phone preferences, which apply to the next call. */
    fun applyTrialRecommendation() {
        val recommended = _state.value.trial.recommendation ?: return
        viewModelScope.launch {
            phonePreferences.update {
                it.copy(
                    preferredCodec = recommended.codec,
                    maxBitrate = recommended.bitrate ?: it.maxBitrate
                )
            }
            _state.update { it.copy(message = UiText.Res(R.string.lab_trial_kept)) }
        }
    }

    // -- diagnostics ----------------------------------------------------

    fun loadDiagnostics() {
        if (_state.value.diagnostics.loading) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val outcome = api.diagnostics()) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(diagnostics = it.diagnostics.copy(loading = false, payload = outcome.value.pretty()))
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(diagnostics = it.diagnostics.copy(loading = false, error = outcome.failure()))
                }
            }
        }
    }

    fun exportBundle() {
        val current = _state.value.diagnostics
        if (current.exporting) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(exporting = true, error = null)) }
        viewModelScope.launch {
            when (val created = api.createDiagnosticsBundle()) {
                is ApiOutcome.Failure -> _state.update {
                    it.copy(diagnostics = it.diagnostics.copy(exporting = false, error = created.failure()))
                }

                is ApiOutcome.Ok -> {
                    val bundle = created.value
                    val file = withContext(Dispatchers.IO) {
                        LabShare.directory(context).resolve("phonxiety-diagnostics-${bundle.bundleId}.zip")
                    }
                    val downloaded = withContext(Dispatchers.IO) {
                        file.outputStream().use { sink -> api.downloadBundle(bundle.bundleId, sink) }
                    }
                    when (downloaded) {
                        is ApiResult.Success -> _state.update {
                            it.copy(
                                diagnostics = it.diagnostics.copy(
                                    exporting = false,
                                    bundleId = bundle.bundleId,
                                    payload = it.diagnostics.payload
                                ),
                                share = ShareRequest(
                                    LabShare.uriFor(context, file),
                                    "application/zip",
                                    UiText.Res(R.string.lab_diagnostics_bundle_title, listOf(bundle.bundleId))
                                )
                            )
                        }

                        is ApiResult.Failure -> {
                            file.delete()
                            _state.update {
                                it.copy(
                                    diagnostics = it.diagnostics.copy(
                                        exporting = false,
                                        error = LabFailure.of(downloaded.message, downloaded.code)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // -- logs -----------------------------------------------------------

    fun setLogFilter(filter: String) {
        _state.update { it.copy(logs = it.logs.copy(filter = filter)) }
        refreshLogs()
    }

    fun setLogLevel(level: String) {
        if (level !in PhoneSettings.LOG_LEVELS) return
        _state.update { it.copy(logs = it.logs.copy(level = level)) }
        refreshLogs()
    }

    /** Copies the visible lines through the screen's clipboard; the screen owns the toast. */
    fun logText(): String = _state.value.logs.lines.joinToString("\n")

    fun exportLogs() {
        val logs = _state.value.logs
        if (logs.exporting) return
        _state.update { it.copy(logs = it.logs.copy(exporting = true)) }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val target = LabShare.directory(context).resolve("phonxiety-phone-log.txt")
                target.writeText(logs.lines.joinToString("\n"))
                target
            }
            _state.update {
                it.copy(
                    logs = it.logs.copy(exporting = false),
                    share = ShareRequest(
                        LabShare.uriFor(context, file),
                        "text/plain",
                        UiText.Res(R.string.lab_log_chooser)
                    )
                )
            }
        }
    }

    fun consumeShare() = _state.update { it.copy(share = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun refreshLogs() {
        val logs = _state.value.logs
        val minimum = BridgeLog.levelOf(logs.level) ?: BridgeLog.INFO
        val query = logs.filter.trim()
        val lines = BridgeLog.snapshot()
            .asSequence()
            .filter { it.level >= minimum }
            .filter { query.isEmpty() || it.message.contains(query, ignoreCase = true) || it.tag.contains(query, ignoreCase = true) }
            .map { it.format() }
            .toList()
        _state.update { it.copy(logs = it.logs.copy(lines = lines)) }
    }

    private suspend fun LabHistoryStore.appendAll(results: List<LinkTestResult>): List<LinkTestResult> {
        var stored = load()
        for (result in results) stored = append(result)
        return stored
    }

    /** The daemon's failure as the screen shows it: the sentence, then the code behind details. */
    private fun ApiOutcome.Failure.failure(): LabFailure = LabFailure.of(message, code)

    private fun CodecProfile.step(): UiText = when {
        codec == "opus" && bitrate != null -> UiText.Res(R.string.lab_trial_step_opus, listOf(bitrate / 1_000))
        codec == "opus" -> UiText.Res(R.string.lab_trial_step_opus_plain)
        else -> UiText.Res(R.string.lab_trial_step_pcm)
    }

    private companion object {
        const val TONE_SECONDS = 5.0
        const val TRIAL_SECONDS = 3.0

        /** Eight attempts is long enough for a summary and short enough to give up in view. */
        const val RESULT_ATTEMPTS = 8
        const val RESULT_RETRY_MS = 1_000L

        /** The daemon's test call is capped at 60 s; a minute and a half of waiting outlives it. */
        const val LIVE_POLLS = 90
        const val RESULT_READY = "ready"
        const val PHASE_ENDED = "ended"
        val TERMINAL_STATES = setOf("ended", "cancelled", "canceled", "failed", "unknown")
    }
}

/** `192.0.2.10:18763` from the daemon's `wss://192.0.2.10:18763/device`. */
internal fun hostPort(url: String): String {
    val authority = url.substringAfter("://", url).substringBefore('/')
    return authority.substringAfterLast('@')
}

/** A latency as the screen combines it: the unit travels with the number. */
internal fun millisText(millis: Long): String = "$millis\u00A0ms"

/** The daemon's marker for a path it did not probe; it is a state of its own, not "unreachable". */
internal const val NOT_MEASURED = "not_measured"
