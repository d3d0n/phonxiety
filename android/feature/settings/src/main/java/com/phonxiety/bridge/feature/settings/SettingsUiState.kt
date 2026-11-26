package com.phonxiety.bridge.feature.settings

import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.net.StoredCandidate
import com.phonxiety.bridge.net.isTailnet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The daemon's own settings, typed exactly as `/app/v1/settings` sends them.  The phone edits a
 * text [form] mirror; [diff] sends only the keys whose value actually changed.
 */
@Serializable
data class DaemonSettingsValues(
    @SerialName("audio_codec") val audioCodec: String = "opus",
    @SerialName("opus_bitrate") val opusBitrate: Int = 32_000,
    @SerialName("voice_backend") val voiceBackend: String = "codex",
    @SerialName("codex_voice") val codexVoice: String = "sol",
    @SerialName("gemini_voice") val geminiVoice: String = "",
    @SerialName("codex_task_model") val codexTaskModel: String = "",
    @SerialName("gemini_model") val geminiModel: String = "",
    @SerialName("language_hint") val languageHint: String = "",
    @SerialName("ring_timeout_s") val ringTimeout: Int = 60,
    @SerialName("max_duration_s") val maxDuration: Int = 900,
    @SerialName("default_result_fields") val defaultResultFields: List<String> = emptyList(),
    @SerialName("default_constraints") val defaultConstraints: String = "",
    @SerialName("iroh_relays") val irohRelays: List<String> = emptyList()
)

/** The same values as editable text; converting back tolerates half-typed numbers. */
data class DaemonForm(
    val audioCodec: String = "opus",
    val opusBitrate: String = "32000",
    val voiceBackend: String = "codex",
    val codexVoice: String = "sol",
    val geminiVoice: String = "",
    val codexTaskModel: String = "",
    val geminiModel: String = "",
    val languageHint: String = "",
    val ringTimeout: String = "60",
    val maxDuration: String = "900",
    val defaultResultFields: String = "",
    val defaultConstraints: String = "",
    val irohRelays: String = ""
)

fun DaemonSettingsValues.toForm(): DaemonForm = DaemonForm(
    audioCodec = audioCodec,
    opusBitrate = opusBitrate.toString(),
    voiceBackend = voiceBackend,
    codexVoice = codexVoice,
    geminiVoice = geminiVoice,
    codexTaskModel = codexTaskModel,
    geminiModel = geminiModel,
    languageHint = languageHint,
    ringTimeout = ringTimeout.toString(),
    maxDuration = maxDuration.toString(),
    defaultResultFields = defaultResultFields.joinToString(", "),
    defaultConstraints = defaultConstraints,
    irohRelays = irohRelays.joinToString("\n")
)

/** The numeric fields the form edits; a half-typed or blank one blocks the save. */
enum class DaemonNumericField { OPUS_BITRATE, RING_TIMEOUT, MAX_DURATION }

/** Fields whose text is not a whole positive number yet. Never silently replaced by the old value. */
fun DaemonForm.invalidNumericFields(): Set<DaemonNumericField> = buildSet {
    if (!opusBitrate.isWholePositiveNumber()) add(DaemonNumericField.OPUS_BITRATE)
    if (!ringTimeout.isWholePositiveNumber()) add(DaemonNumericField.RING_TIMEOUT)
    if (!maxDuration.isWholePositiveNumber()) add(DaemonNumericField.MAX_DURATION)
}

private fun String.isWholePositiveNumber(): Boolean = trim().toIntOrNull()?.let { it > 0 } == true

/** The form as values, or null while a numeric field cannot be saved as typed. */
fun DaemonForm.toValuesOrNull(): DaemonSettingsValues? {
    if (invalidNumericFields().isNotEmpty()) return null
    return DaemonSettingsValues(
        audioCodec = audioCodec,
        opusBitrate = requireNotNull(opusBitrate.trim().toIntOrNull()),
        voiceBackend = voiceBackend,
        codexVoice = codexVoice.trim(),
        geminiVoice = geminiVoice.trim(),
        codexTaskModel = codexTaskModel.trim(),
        geminiModel = geminiModel.trim(),
        languageHint = languageHint.trim(),
        ringTimeout = requireNotNull(ringTimeout.trim().toIntOrNull()),
        maxDuration = requireNotNull(maxDuration.trim().toIntOrNull()),
        defaultResultFields = defaultResultFields.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        defaultConstraints = defaultConstraints.trim(),
        irohRelays = irohRelays.lines().map { it.trim() }.filter { it.isNotEmpty() }
    )
}

private val formJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** Only the keys whose value differs, as `/app/v1/settings` expects them. */
fun diff(before: DaemonSettingsValues, after: DaemonSettingsValues): JsonObject {
    val beforeObject = formJson.encodeToJsonElement(DaemonSettingsValues.serializer(), before).jsonObject
    val afterObject = formJson.encodeToJsonElement(DaemonSettingsValues.serializer(), after).jsonObject
    return JsonObject(afterObject.filterKeys { key -> beforeObject[key] != afterObject[key] })
}

data class DaemonSettingsUi(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: UiText? = null,
    /** True when [error] came from a refused save: the edits stay, and reloading would drop them. */
    val errorOnSave: Boolean = false,
    val values: DaemonSettingsValues = DaemonSettingsValues(),
    val form: DaemonForm = DaemonForm(),
    val restartRequiredFields: List<String> = emptyList(),
    val changesApplied: List<String> = emptyList()
) {
    /** Numeric fields that cannot be saved yet; the screen marks each one. */
    val invalidFields: Set<DaemonNumericField> get() = form.invalidNumericFields()

    val valid: Boolean get() = invalidFields.isEmpty()

    /**
     * True when the text would send something different. An invalid field counts as edited when its
     * text differs from the canonical form, so the save button still explains itself.
     */
    val dirty: Boolean
        get() = form.toValuesOrNull()?.let { it != values } ?: (form != values.toForm())
}

data class AutoTuneUi(
    val enabled: Boolean = false,
    val changes: List<AutoTuneLogEntry> = emptyList(),
    val suggestions: List<UiText> = emptyList(),
    val lastEvaluatedAtMs: Long? = null
)

/** The addresses the phone may reach the daemon at, learned from the daemon itself. */
data class ConnectionUi(
    val candidates: List<StoredCandidate> = emptyList(),
    val refreshing: Boolean = false,
    val error: UiText? = null
) {
    val tailnetCount: Int get() = candidates.count { it.isTailnet }

    val lanCount: Int get() = candidates.size - tailnetCount
}

data class SettingsUiState(
    val daemon: DaemonSettingsUi = DaemonSettingsUi(),
    val phone: PhoneSettings = PhoneSettings(),
    val connection: ConnectionUi = ConnectionUi(),
    val autoTune: AutoTuneUi = AutoTuneUi(),
    val message: UiText? = null,
    /** True while the last message can be undone from its snackbar (a bulk revert). */
    val undoAvailable: Boolean = false
)
