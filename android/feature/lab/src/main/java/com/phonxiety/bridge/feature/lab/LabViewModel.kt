package com.phonxiety.bridge.feature.lab

import android.content.Context
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
                    it.copy(link = it.link.copy(running = false, error = outcome.describe()))
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
                    val candidates = if ("lan" in selected) checkedLan(outcome.value.candidates) else outcome.value.candidates
                    _state.update {
                        it.copy(
                            probe = it.probe.copy(
                                running = false,
                                candidates = candidates,
                                checkedAt = outcome.value.checkedAt,
                                error = null
                            )
                        )
                    }
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(probe = it.probe.copy(running = false, error = outcome.describe()))
                }
            }
        }
    }

    /**
     * Replaces the daemon's LAN answer with this phone's own measurement.
     *
     * The daemon can only report whether its listener answers on its own LAN
     * address; the question the lab asks is whether this phone's network
     * reaches that address. The same pinned TLS handshake and bearer upgrade
     * link selection uses answers it from here; the daemon's finding stays in
     * the row's detail so both halves are visible.
     */
    private suspend fun checkedLan(candidates: List<ProbeCandidate>): List<ProbeCandidate> {
        val row = candidates.firstOrNull { it.name == "lan" } ?: return candidates
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

        // Without the phone's own measurement the row says so: the daemon's listener answering is
        // not this phone reaching it.
        fun inconclusive(note: String) = row.copy(reachable = false, rttMs = null, reason = "inconclusive", detail = row.notes(note))
        val updated = when {
            info == null -> inconclusive("the phone could not read the daemon's LAN addresses")

            urls.isEmpty() -> inconclusive("the daemon reports no LAN address")

            token == null -> inconclusive("this phone is not paired, so it cannot authenticate a probe")

            probes == null -> inconclusive("the phone's own check failed")

            else -> row.copy(
                available = true,
                reachable = answered != null,
                rttMs = answered?.rttMs?.toDouble(),
                address = answered?.address ?: urls.first(),
                detail = "${phoneResult(probes, answered)} · ${daemonListener(row)}",
                reason = when {
                    answered != null -> null
                    row.reachable -> "unreachable_from_phone"
                    else -> row.reason ?: "no_listener_answer"
                }
            )
        }
        return candidates.map { if (it.name == row.name) updated else it }
    }

    private fun ProbeCandidate.notes(note: String): String = listOfNotNull(detail, note).joinToString(" · ")

    private fun phoneResult(probes: List<LanPathProbe>, answered: LanPathProbe?): String = when (answered) {
        null -> "this phone could not reach ${probes.joinToString(", ") { it.address }}"
        else -> "this phone: tls+auth to ${answered.address} in ${answered.rttMs} ms"
    }

    private fun daemonListener(row: ProbeCandidate): String = if (row.reachable) {
        "the daemon's listener answers on its own LAN address"
    } else {
        "the daemon's listener does not answer there either"
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
                            note = if (outcome.value.enabled) "The daemon echoes the uplink back" else null
                        )
                    )
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(audio = it.audio.copy(busy = false, error = outcome.describe()))
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
                    it.copy(audio = it.audio.copy(busy = false, note = "Tone played for ${TONE_SECONDS.toInt()} s"))
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(audio = it.audio.copy(busy = false, error = outcome.describe()))
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
                        message = "Test call placed"
                    )
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(testCall = it.testCall.copy(placing = false, error = outcome.describe()))
                }
            }
        }
    }

    // -- codec trial ----------------------------------------------------

    fun runCodecTrial() {
        if (_state.value.trial.running) return
        _state.update { it.copy(trial = CodecTrialUi(running = true, step = "Starting")) }
        viewModelScope.launch {
            val runs = mutableListOf<CodecTrialRun>()
            for (profile in TRIAL_PROFILES) {
                _state.update { it.copy(trial = it.trial.copy(step = "Measuring ${profile.label}")) }
                when (val outcome = api.linkTest(TRIAL_SECONDS, profile.codec, "stream", profile.bitrate)) {
                    is ApiOutcome.Ok -> runs += CodecTrialRun(profile.codec, profile.bitrate, outcome.value)

                    is ApiOutcome.Failure -> {
                        _state.update {
                            it.copy(trial = CodecTrialUi(running = false, runs = runs.toList(), error = outcome.describe()))
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
            _state.update { it.copy(message = "Kept ${recommended.label} for the next call") }
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
                    it.copy(diagnostics = it.diagnostics.copy(loading = false, error = outcome.describe()))
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
                    it.copy(diagnostics = it.diagnostics.copy(exporting = false, error = created.describe()))
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
                                    "Phonxiety diagnostics ${bundle.bundleId}"
                                )
                            )
                        }

                        is ApiResult.Failure -> {
                            file.delete()
                            _state.update {
                                it.copy(
                                    diagnostics = it.diagnostics.copy(exporting = false, error = "${downloaded.code}: ${downloaded.message}")
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
                    share = ShareRequest(LabShare.uriFor(context, file), "text/plain", "Phonxiety phone log")
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

    private fun ApiOutcome.Failure.describe(): String = "$code: $message"

    private companion object {
        const val TONE_SECONDS = 5.0
        const val TRIAL_SECONDS = 3.0
    }
}
