package com.phonxiety.bridge.feature.livecall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.model.ApiLimits
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

/**
 * The phone-originated call: a number, a brief, the limits, live validation from the daemon, then
 * one confirmation that names exactly what will be dialled.
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
    val errors: List<ValidationIssue> = emptyList(),
    val warnings: List<String> = emptyList(),
    val confirming: Boolean = false,
    val placing: Boolean = false,
    val placedCallId: String? = null,
    val loadedFromPrefill: Boolean = false,
    val error: String? = null
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

    val destinationError: String? get() = errorFor("destination")

    val goalError: String? get() = errorFor("goal")

    fun errorFor(field: String): String? = errors.firstOrNull { it.location == field }?.message
        ?: errors.firstOrNull { it.location.startsWith("$field.") }?.message
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
        _state.update { it.copy(confirming = true, error = null) }
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
        _state.update { it.copy(placing = true, error = null) }
        viewModelScope.launch {
            when (val outcome = api.placeCall(payload)) {
                is ApiOutcome.Ok -> _state.update {
                    it.copy(placing = false, confirming = false, placedCallId = outcome.value.callId)
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(
                        placing = false,
                        confirming = false,
                        error = "${outcome.message} (${outcome.code})"
                    )
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
                        errors = outcome.value.errors,
                        warnings = outcome.value.warnings
                    )
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(
                        validating = false,
                        valid = false,
                        errors = listOf(ValidationIssue("request", "${outcome.message} (${outcome.code})")),
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
