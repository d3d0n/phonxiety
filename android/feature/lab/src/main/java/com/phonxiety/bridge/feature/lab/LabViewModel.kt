package com.phonxiety.bridge.feature.lab

import android.content.Context
import androidx.annotation.StringRes
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
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.ProbeCandidate
import com.phonxiety.bridge.model.TestCallRequest
import com.phonxiety.bridge.net.LanPathProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        viewModelScope.launch {
            _state.update { it.copy(link = it.link.copy(history = history.load())) }
        }
        viewModelScope.launch {
            phonePreferences.settings.collect { settings ->
                _state.update { it.copy(testCall = it.testCall.copy(number = settings.testNumber)) }
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
                        toneSupported = outcome.value.voiceControls.tone
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
        return when {
            notMeasured -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.NOT_CHECKED,
                note = reasonText(candidate.reason)
            )

            candidate.available && candidate.reachable -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.REACHED,
                rttMs = rttMs,
                note = rttMs?.let { UiText.Res(R.string.lab_probe_note_bridge_reached, listOf(millisText(it))) }
                    ?: candidate.detail?.let { UiText.Literal(it) }
            )

            candidate.available -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.NOT_REACHED,
                note = reasonText(candidate.reason) ?: UiText.Res(R.string.lab_probe_reason_listener)
            )

            else -> ProbeRowUi(
                key = candidate.name,
                endpoint = endpoint,
                verdict = ProbeVerdict.NOT_CHECKED,
                note = reasonText(candidate.reason) ?: UiText.Res(R.string.lab_probe_reason_none)
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
        val info = (api.info() as? ApiOutcome.Ok)?.value
        val urls = info?.connection?.lan.orEmpty()
        val pin = info?.connection?.certificate
        val token = BridgeConfigLoader.loadOrNull(context)?.token
        val probes = if (urls.isNotEmpty() && token != null) {
            withContext(Dispatchers.IO) { runCatching { lanPathCheck.probe(urls, pin, token) }.getOrNull() }
        } else {
            null
        }
        val answered = probes?.firstOrNull { it.reachable }
        val updated = when {
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
        return rows.map { if (it.key == lan.name) updated else it }
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
        val clean = PhoneSettings.sanitizeNumber(number)
        _state.update { it.copy(testCall = it.testCall.copy(number = clean, error = null)) }
        viewModelScope.launch {
            phonePreferences.update { it.copy(testNumber = clean) }
        }
    }

    fun requestTestCall() {
        val current = _state.value.testCall
        if (!current.numberReady) return
        _state.update { it.copy(testCall = current.copy(confirming = true)) }
    }

    fun dismissTestCall() = _state.update { it.copy(testCall = it.testCall.copy(confirming = false)) }

    fun confirmTestCall() {
        val request = _state.value.testCall
        if (request.placing) return
        _state.update { it.copy(testCall = request.copy(confirming = false, placing = true, error = null)) }
        viewModelScope.launch {
            val outcome = api.testCall(TestCallRequest(request.number, request.maxDurationSeconds))
            when (outcome) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        testCall = it.testCall.copy(placing = false, callId = outcome.value.callId),
                        message = UiText.Res(R.string.lab_test_placed_message)
                    )
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(testCall = it.testCall.copy(placing = false, error = outcome.failure()))
                }
            }
        }
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
