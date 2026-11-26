package com.phonxiety.bridge.feature.lab

import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.LinkTestResult
import kotlinx.serialization.json.JsonObject

/** A failure from the daemon: the sentence a person reads, and the code support needs. */
data class LabFailure(val message: UiText, val code: String? = null) {
    companion object {
        fun of(message: String, code: String?): LabFailure = LabFailure(UiText.Literal(message), code)
    }
}

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
    val message: UiText? = null,
    val share: ShareRequest? = null
) {
    /** Loopback and a tone only make sense while this phone owns a live call. */
    val audioEnabled: Boolean get() = callPhase == "active" && callId != null

    // The bridge keeps the last call it saw after it ends. That record is no call to run audio
    // inside any more, so the card asks for a new one instead of one to go active.
    val audioDisabledReason: UiText?
        get() = when {
            loopbackSupported == false && toneSupported == false -> UiText.Res(R.string.lab_audio_reason_none)
            callPhase == null || callPhase == "ended" -> UiText.Res(R.string.lab_audio_reason_no_call)
            callPhase != "active" -> UiText.Res(R.string.lab_audio_reason_not_active)
            else -> null
        }

    /**
     * The automatic loopback is offered only when the daemon starts it and this phone has it:
     * asking for a probe the phone does not support rejects the whole placement with 409.
     */
    val testCallLoopbackAvailable: Boolean get() = testCall.probesAvailable && loopbackSupported == true

    /** The automatic tone, under the same two conditions as [testCallLoopbackAvailable]. */
    val testCallToneAvailable: Boolean get() = testCall.probesAvailable && toneSupported == true

    /** The loopback this placement will actually ask for. */
    val testCallLoopback: Boolean get() = testCallLoopbackAvailable && testCall.loopback

    /** The automatic tone this placement will actually ask for. */
    val testCallTone: Boolean get() = testCallToneAvailable && testCall.tone
}

data class LinkTestUi(
    val seconds: Float = 5f,
    val codec: String? = null,
    val transport: String = "stream",
    val running: Boolean = false,
    val last: LinkTestResult? = null,
    val history: List<LinkTestResult> = emptyList(),
    val error: LabFailure? = null
)

data class ProbeUi(
    val selected: Set<String> = setOf("iroh", "lan", "tailscale"),
    val running: Boolean = false,
    val rows: List<ProbeRowUi> = emptyList(),
    val checkedAt: String? = null,
    val error: LabFailure? = null
)

/** How one path answered, as three states a reader can tell apart. */
enum class ProbeVerdict { REACHED, NOT_REACHED, NOT_CHECKED }

/**
 * One path in the probe list. [verdict] never stands in for the other two: a path this phone could
 * not measure is "Not checked" with a [note] saying why, never "Not reached". For the LAN path the
 * measurement is this phone's own handshake; for the others it is the bridge's.
 */
data class ProbeRowUi(
    val key: String,
    val endpoint: String? = null,
    val verdict: ProbeVerdict = ProbeVerdict.NOT_CHECKED,
    val rttMs: Long? = null,
    val note: UiText? = null,
    val listenerNote: UiText? = null,
    /** The daemon's own detail and reason, verbatim, shown only under Details. */
    val details: String? = null
)

data class AudioUi(
    val loopbackOn: Boolean = false,
    val toneHz: Int = 1_000,
    val toneDbfs: Double = -20.0,
    val busy: Boolean = false,
    val error: LabFailure? = null,
    val note: UiText? = null
)

data class TestCallUi(
    val number: String = "",
    val maxDurationSeconds: Int = 30,
    val confirming: Boolean = false,
    val placing: Boolean = false,
    val callId: String? = null,
    val error: LabFailure? = null,
    /** `voice_controls.test_call_probes`; null while the daemon's info has not answered yet. */
    val probesSupported: Boolean? = null,
    val loopback: Boolean = false,
    val tone: Boolean = false,
    val toneSeconds: Int = 5,
    val result: TestCallResultUi? = null
) {
    val numberReady: Boolean get() = number.filter { it.isDigit() }.length >= 3

    /** The toggles appear only when the daemon says it starts and stops the probes itself. */
    val probesAvailable: Boolean get() = probesSupported == true
}

/**
 * What the finished test call left behind: the daemon's own record, refetched until its summary is
 * ready. [waiting] is false once the refetch is over, whether the summary arrived or not.
 */
data class TestCallResultUi(
    val callId: String,
    val outcome: String? = null,
    val resultStatus: String? = null,
    val summary: String? = null,
    val durationSeconds: Long? = null,
    val codec: String? = null,
    val waiting: Boolean = false,
    val error: LabFailure? = null,
    /** The daemon had not finished the call when the Lab stopped reading its record. */
    val unfinished: Boolean = false
) {
    /** The summary is only shown once the daemon says it is ready; pending is not an answer. */
    val summaryReady: Boolean get() = resultStatus == "ready" && !summary.isNullOrBlank()

    /**
     * The daemon marks a record `ready` even when it wrote no summary at all: that is an absent
     * summary, not one still on its way.
     */
    val summaryAbsent: Boolean
        get() = !summaryReady && (resultStatus.isNullOrBlank() || resultStatus == "ready")
}

data class CodecTrialUi(
    val running: Boolean = false,
    val step: UiText? = null,
    val runs: List<CodecTrialRun> = emptyList(),
    val recommendation: CodecTrialRun? = null,
    val error: LabFailure? = null
)

/** One measured profile of the codec trial; the screen names it from [codec] and [bitrate]. */
data class CodecTrialRun(
    val codec: String,
    val bitrate: Int?,
    val result: LinkTestResult
)

data class DiagnosticsUi(
    val loading: Boolean = false,
    val payload: String? = null,
    val exporting: Boolean = false,
    val bundleId: String? = null,
    val error: LabFailure? = null
)

data class LogUi(
    val lines: List<String> = emptyList(),
    val filter: String = "",
    val level: String = "info",
    val exporting: Boolean = false
)

/** A finished cache file waiting for the screen to hand it to the system share sheet. */
data class ShareRequest(val uri: android.net.Uri, val mimeType: String, val title: UiText)

/**
 * A daemon value this build has no words for: still readable, never an underscore in the UI. Known
 * values always get a resource; this is the last resort for a value the daemon may add.
 */
internal fun humanize(value: String): String {
    val words = value.replace('_', ' ').replace('-', ' ').trim()
    if (words.isEmpty()) return value
    return words.replaceFirstChar { it.uppercase() }
}

/** One persisted run: the daemon's record plus when this phone asked for it. */
typealias LabLinkTest = LinkTestResult

/** The diagnostics payload as the screen shows it. */
fun JsonObject.pretty(): String = kotlinx.serialization.json.Json { prettyPrint = true }
    .encodeToString(JsonObject.serializer(), this)
