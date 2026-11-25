package com.phonxiety.bridge.feature.lab

import com.phonxiety.bridge.model.LinkTestResult
import com.phonxiety.bridge.model.ProbeCandidate
import kotlinx.serialization.json.JsonObject

/** Everything the test lab renders; the screen is a pure function of this. */
data class LabUiState(
    val link: LinkTestUi = LinkTestUi(),
    val probe: ProbeUi = ProbeUi(),
    val audio: AudioUi = AudioUi(),
    val testCall: TestCallUi = TestCallUi(),
    val trial: CodecTrialUi = CodecTrialUi(),
    val diagnostics: DiagnosticsUi = DiagnosticsUi(),
    val logs: LogUi = LogUi(),
    val callPhase: String? = null,
    val callId: String? = null,
    val loopbackSupported: Boolean? = null,
    val toneSupported: Boolean? = null,
    val message: String? = null,
    val share: ShareRequest? = null
) {
    /** Loopback and a tone only make sense while this phone owns a live call. */
    val audioEnabled: Boolean get() = callPhase == "active" && callId != null

    val audioDisabledReason: String?
        get() = when {
            loopbackSupported == false && toneSupported == false -> "This phone build offers neither loopback nor a test tone"
            callPhase == null -> "Start a call first: loopback and the tone run inside one"
            callPhase != "active" -> "The call has to be active before audio can loop back"
            else -> null
        }
}

data class LinkTestUi(
    val seconds: Float = 5f,
    val codec: String? = null,
    val transport: String = "stream",
    val running: Boolean = false,
    val last: LinkTestResult? = null,
    val history: List<LinkTestResult> = emptyList(),
    val error: String? = null
)

data class ProbeUi(
    val selected: Set<String> = setOf("iroh", "lan", "tailscale"),
    val running: Boolean = false,
    val candidates: List<ProbeCandidate> = emptyList(),
    val checkedAt: String? = null,
    val error: String? = null
)

data class AudioUi(
    val loopbackOn: Boolean = false,
    val toneHz: Int = 1_000,
    val toneDbfs: Double = -20.0,
    val busy: Boolean = false,
    val error: String? = null,
    val note: String? = null
)

data class TestCallUi(
    val number: String = "",
    val maxDurationSeconds: Int = 30,
    val confirming: Boolean = false,
    val placing: Boolean = false,
    val callId: String? = null,
    val error: String? = null
) {
    val numberReady: Boolean get() = number.filter { it.isDigit() }.length >= 3
}

data class CodecTrialUi(
    val running: Boolean = false,
    val step: String? = null,
    val runs: List<CodecTrialRun> = emptyList(),
    val recommendation: CodecTrialRun? = null,
    val error: String? = null
)

/** One measured profile of the codec trial. */
data class CodecTrialRun(
    val codec: String,
    val bitrate: Int?,
    val result: LinkTestResult
) {
    val label: String get() = if (codec == "opus" && bitrate != null) "Opus ${bitrate / 1000} kbit/s" else codec.uppercase()
}

data class DiagnosticsUi(
    val loading: Boolean = false,
    val payload: String? = null,
    val exporting: Boolean = false,
    val bundleId: String? = null,
    val error: String? = null
)

data class LogUi(
    val lines: List<String> = emptyList(),
    val filter: String = "",
    val level: String = "info",
    val exporting: Boolean = false
)

/** A finished cache file waiting for the screen to hand it to the system share sheet. */
data class ShareRequest(val uri: android.net.Uri, val mimeType: String, val title: String)

/** One persisted run: the daemon's record plus when this phone asked for it. */
typealias LabLinkTest = LinkTestResult

/** The diagnostics payload as the screen shows it. */
fun JsonObject.pretty(): String = kotlinx.serialization.json.Json { prettyPrint = true }
    .encodeToString(JsonObject.serializer(), this)
