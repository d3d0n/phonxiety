package com.phonxiety.bridge.feature.livecall

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.ApiLimits
import com.phonxiety.bridge.model.FailureKind
import com.phonxiety.bridge.model.PlaceCallRequest
import com.phonxiety.bridge.model.ValidationIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The defaults a fresh brief starts with; the daemon's own limits narrow them further. */
internal const val COMPOSER_DEFAULT_MAX_DURATION = 900
internal const val COMPOSER_DEFAULT_RING_TIMEOUT = 60

/** The daemon's goal length limit (`src/phonxiety/models.py`), kept visible beside the field. */
internal const val COMPOSER_GOAL_LIMIT = 8_000

/**
 * One validation failure in the shape the screen draws: [headline] is local copy for a problem the
 * app knows, or the daemon's own sentence when it is not; [detail] keeps the verbatim diagnostic
 * for the details disclosure, where a code belongs.
 */
@Immutable
data class ComposerIssue(
    val location: String,
    val headline: UiText,
    val detail: String? = null
)

/**
 * The phone-originated call: a number, a brief, the limits, live validation from the daemon, then
 * one confirmation that names exactly what will be dialed.
 */
data class ComposerUiState(
    val destination: String = "",
    val goal: String = "",
    val context: String = "",
    val constraints: String = "",
    val resultFields: List<String> = emptyList(),
    val fieldDraft: String = "",
    val maxDurationSeconds: Int = COMPOSER_DEFAULT_MAX_DURATION,
    val ringTimeoutSeconds: Int = COMPOSER_DEFAULT_RING_TIMEOUT,
    val limits: ApiLimits = ApiLimits(),
    val valid: Boolean? = null,
    val validating: Boolean = false,
    val errors: List<ComposerIssue> = emptyList(),
    val warnings: List<UiText> = emptyList(),
    val confirming: Boolean = false,
    val placing: Boolean = false,
    val placedCallId: String? = null,
    val loadedFromPrefill: Boolean = false,
    /** The placement itself failed; the card under the form says so. */
    val placeError: UiText? = null,
    val placeErrorDetail: String? = null,
    val placeErrorKind: FailureKind = FailureKind.DAEMON
) {
    /** The exact payload: nothing is added between this and the dial. */
    val request: PlaceCallRequest
        get() = PlaceCallRequest(
            destination = destination.filterNot { it.isWhitespace() },
            goal = goal.trim(),
            context = context.trim(),
            constraints = constraints.trim(),
            maxDurationSeconds = maxDurationSeconds,
            ringTimeoutSeconds = ringTimeoutSeconds,
            resultFields = resultFields
        )

    val ready: Boolean get() = valid == true && errors.isEmpty() && !placing

    val destinationIssue: ComposerIssue? get() = issueFor("destination")

    val goalIssue: ComposerIssue? get() = issueFor("goal")

    val destinationError: UiText? get() = destinationIssue?.headline

    val goalError: UiText? get() = goalIssue?.headline

    /** The first field error, in reading order, for the action's supporting text. */
    val firstFieldError: UiText? get() = listOfNotNull(destinationError, goalError).firstOrNull()

    /** A failure of the check itself, not of a field: the daemon or the link refused the request. */
    val requestIssue: ComposerIssue? get() = issueFor("request")

    fun issueFor(field: String): ComposerIssue? = errors.firstOrNull { it.location == field }
        ?: errors.firstOrNull { it.location.startsWith("$field.") }
}

/**
 * A daemon validation message as the screen can draw it. Only a reason the app recognizes is
 * translated; anything else stays verbatim, because inventing a sentence for a diagnostic nobody
 * predicted is how a screen starts lying. [ComposerIssue.detail] keeps the daemon's own words for
 * the details disclosure whenever the app did translate.
 */
internal fun localIssue(location: String, message: String): ComposerIssue {
    val reason = message.lowercase()
    val headline = when {
        location.startsWith("destination") && reason.contains("emergency") ->
            UiText.Res(R.string.composer_error_destination_emergency)

        location.startsWith("destination") && reason.contains("match pattern") ->
            UiText.Res(R.string.composer_error_destination)

        location.startsWith("goal") && reason.contains("at least 1 character") ->
            UiText.Res(R.string.composer_error_goal)

        // The only length the goal can fail on: the daemon's 8,000-character field limit.
        location.startsWith("goal") && reason.contains("at most $COMPOSER_GOAL_LIMIT") ->
            UiText.Res(R.string.composer_error_goal_too_long)

        else -> UiText.Literal(message)
    }
    return ComposerIssue(
        location = location,
        headline = headline,
        detail = message.takeIf { headline != UiText.Literal(message) }
    )
}

@HiltViewModel
class ComposerViewModel @Inject constructor(
    private val api: DaemonApi
) : ViewModel() {
    private val _state = MutableStateFlow(ComposerUiState())
    val state: StateFlow<ComposerUiState> = _state.asStateFlow()

    private var validationJob: Job? = null

    init {
        viewModelScope.launch { loadLimits() }
    }

    /** "Call again" prefills the brief; anything the user already typed wins. */
    fun preload(destination: String?, goal: String?, context: String?, constraints: String?) {
        if (_state.value.loadedFromPrefill) return
        _state.update {
            it.copy(
                destination = it.destination.ifBlank { destination.orEmpty() },
                goal = it.goal.ifBlank { goal.orEmpty() },
                context = it.context.ifBlank { context.orEmpty() },
                constraints = it.constraints.ifBlank { constraints.orEmpty() },
                loadedFromPrefill = true
            )
        }
        validate()
    }

    fun onDestinationChange(value: String) = edit { it.copy(destination = value) }

    fun onGoalChange(value: String) = edit { it.copy(goal = value) }

    fun onContextChange(value: String) = edit { it.copy(context = value) }

    fun onConstraintsChange(value: String) = edit { it.copy(constraints = value) }

    fun onFieldDraftChange(value: String) = _state.update { it.copy(fieldDraft = value) }

    fun addField() {
        val field = _state.value.fieldDraft.trim()
        if (field.isEmpty()) return
        _state.update {
            it.copy(
                resultFields = (it.resultFields + field).distinct().take(MAX_FIELDS),
                fieldDraft = ""
            )
        }
        validate()
    }

    fun removeField(field: String) {
        _state.update { it.copy(resultFields = it.resultFields - field) }
        validate()
    }

    fun onMaxDurationChange(seconds: Int) {
        val max = _state.value.limits.maxDurationSeconds ?: MAX_DURATION_LIMIT
        _state.update { it.copy(maxDurationSeconds = seconds.coerceIn(MIN_DURATION, max)) }
        validate()
    }

    fun onRingTimeoutChange(seconds: Int) {
        val max = _state.value.limits.ringTimeoutSeconds ?: RING_TIMEOUT_LIMIT
        _state.update { it.copy(ringTimeoutSeconds = seconds.coerceIn(MIN_RING_TIMEOUT, max)) }
        validate()
    }

    /** The confirmation step: only reachable once the daemon has validated the brief. */
    fun review() {
        if (_state.value.valid != true || _state.value.errors.isNotEmpty()) return
        _state.update { it.copy(confirming = true, placeError = null, placeErrorDetail = null) }
    }

    fun dismissReview() {
        _state.update { it.copy(confirming = false) }
    }

    fun confirm() {
        val snapshot = _state.value
        if (snapshot.placing || snapshot.placedCallId != null) return
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val payload = snapshot.request.copy(
            confirmationId = "app-$suffix",
            requestId = "app-$suffix"
        )
        _state.update { it.copy(placing = true, placeError = null, placeErrorDetail = null) }
        // From this moment the in-call screen Telecom draws may cover the composer, and only this
        // phone's own placement may pull the live screen back over it.
        LiveCallFront.expectPlacement()
        viewModelScope.launch {
            when (val outcome = api.placeCall(payload)) {
                is ApiOutcome.Ok -> {
                    // This phone asked for this call: only then may it pull the live screen back
                    // over the system in-call UI once Telecom draws it.
                    LiveCallFront.remember(outcome.value.callId)
                    _state.update {
                        it.copy(placing = false, confirming = false, placedCallId = outcome.value.callId)
                    }
                }

                is ApiOutcome.Failure -> {
                    LiveCallFront.cancelPlacement()
                    _state.update {
                        it.copy(
                            placing = false,
                            confirming = false,
                            placeError = UiText.Literal(outcome.message),
                            placeErrorDetail = outcome.code,
                            placeErrorKind = FailureKind.of(outcome.status, outcome.code)
                        )
                    }
                }
            }
        }
    }

    private fun edit(change: (ComposerUiState) -> ComposerUiState) {
        _state.update(change)
        validate()
    }

    /** Debounced: one request per typing pause, not one per keystroke. */
    private fun validate() {
        validationJob?.cancel()
        val request = _state.value.request
        if (request.destination.isBlank() || request.goal.isBlank()) {
            _state.update { it.copy(validating = false, valid = null, errors = emptyList(), warnings = emptyList()) }
            return
        }
        _state.update { it.copy(validating = true) }
        validationJob = viewModelScope.launch {
            delay(VALIDATE_DEBOUNCE_MS)
            when (val outcome = api.validateCall(request)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(
                        validating = false,
                        valid = outcome.value.valid,
                        errors = outcome.value.errors.map { issue -> issue.asComposerIssue() },
                        warnings = outcome.value.warnings.map(UiText::Literal)
                    )
                }

                // The check itself did not run: the daemon's sentence stays verbatim, its code
                // goes behind details.
                is ApiOutcome.Failure -> _state.update {
                    it.copy(
                        validating = false,
                        valid = false,
                        errors = listOf(
                            ComposerIssue(
                                location = "request",
                                headline = UiText.Literal(outcome.message),
                                detail = outcome.code
                            )
                        ),
                        warnings = emptyList()
                    )
                }
            }
        }
    }

    private suspend fun loadLimits() {
        val info = (api.info() as? ApiOutcome.Ok)?.value ?: return
        val limits = info.limits
        _state.update {
            it.copy(
                limits = limits,
                maxDurationSeconds = (limits.maxDurationSeconds ?: MAX_DURATION_LIMIT)
                    .coerceAtMost(COMPOSER_DEFAULT_MAX_DURATION),
                ringTimeoutSeconds = (limits.ringTimeoutSeconds ?: RING_TIMEOUT_LIMIT)
                    .coerceAtMost(COMPOSER_DEFAULT_RING_TIMEOUT)
            )
        }
    }

    companion object {
        const val VALIDATE_DEBOUNCE_MS = 350L
        const val MIN_DURATION = 10
        const val MAX_DURATION_LIMIT = 3600
        const val MIN_RING_TIMEOUT = 5
        const val RING_TIMEOUT_LIMIT = 180
        const val MAX_FIELDS = 64
    }
}

private fun ValidationIssue.asComposerIssue(): ComposerIssue = localIssue(location, message)
