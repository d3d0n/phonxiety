package com.phonxiety.bridge.feature.calls

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.calls.CallsRepository
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.CallSnapshot
import com.phonxiety.bridge.model.TranscriptTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The brief a finished call can be repeated with: exactly the fields the composer takes. */
data class CallBrief(
    val destination: String,
    val goal: String,
    val context: String = "",
    val constraints: String = "",
    val resultFields: List<String> = emptyList()
)

data class CallAudioUiState(
    val downloading: Boolean = false,
    val filePath: String? = null,
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val error: CallsFailure? = null
)

data class CallDetailUiState(
    val callId: String = "",
    val snapshot: CallSnapshot? = null,
    val transcript: List<TranscriptTurn> = emptyList(),
    val marks: List<CallMark> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val missing: Boolean = false,
    val error: CallsFailure? = null,
    val hasMoreTranscript: Boolean = false,
    val loadingTranscript: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    /** The question is open; only the ViewModel opens and closes it, so a rotation keeps it. */
    val confirmingDelete: Boolean = false,
    val marking: Boolean = false,
    val audio: CallAudioUiState = CallAudioUiState()
) {
    val live: Boolean
        get() = snapshot?.live != null && (snapshot?.state?.let { it !in TERMINAL_STATES } ?: true)
    val hasRecording: Boolean get() = snapshot?.recording != null
    val brief: CallBrief?
        get() {
            val snapshot = snapshot ?: return null
            val request = snapshot.request
            val destination = snapshot.destination ?: request?.string("destination") ?: return null
            return CallBrief(
                destination = destination,
                goal = request?.string("goal").orEmpty(),
                context = request?.string("context").orEmpty(),
                constraints = request?.string("constraints").orEmpty(),
                resultFields = request?.stringList("result_fields").orEmpty()
            )
        }

    val resultFields: List<String>
        get() = snapshot?.request?.stringList("result_fields").orEmpty()

    val summaryFields: JsonObject
        get() = snapshot?.fields ?: JsonObject(emptyMap())

    private companion object {
        val TERMINAL_STATES = setOf("ended", "cancelled", "failed", "unknown")
    }
}

/**
 * One call: the record, its paged transcript, its marks, its recording and the two destructive
 * actions. The cache answers when the daemon cannot be reached; the copy is labelled as such.
 */
@HiltViewModel
class CallDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CallsRepository,
    private val player: CallAudioPlayer
) : ViewModel() {
    private val _state = MutableStateFlow(CallDetailUiState())
    val state: StateFlow<CallDetailUiState> = _state.asStateFlow()

    private var boundCallId: String? = null
    private var transcriptJob: Job? = null
    private var playerJob: Job? = null

    fun bind(callId: String) {
        if (callId.isBlank() || boundCallId == callId) return
        boundCallId = callId
        _state.value = CallDetailUiState(callId = callId)
        transcriptJob?.cancel()
        transcriptJob = viewModelScope.launch {
            repository.observeTranscript(callId).collect { turns ->
                _state.update { it.copy(transcript = turns.ifEmpty { it.snapshot?.transcript ?: it.transcript }) }
            }
        }
        refresh()
    }

    fun refresh() {
        val callId = boundCallId ?: return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.detail(callId, refresh = true)
            val snapshot = result.snapshot
            val failure = result.failure
            if (snapshot == null && failure != null) {
                _state.update {
                    it.copy(
                        refreshing = false,
                        loading = false,
                        offline = failure.code !in MISSING_CODES,
                        missing = failure.code in MISSING_CODES,
                        error = CallsFailure.of(failure.message, failure.code)
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    snapshot = snapshot,
                    marks = snapshot?.marks ?: it.marks,
                    transcript = it.transcript.ifEmpty { snapshot?.transcript.orEmpty() },
                    hasMoreTranscript = snapshot?.hasMore ?: false,
                    loading = false,
                    refreshing = false,
                    offline = failure != null,
                    error = failure?.let { f -> CallsFailure.of(f.message, f.code) },
                    missing = false
                )
            }
        }
    }

    /** One transcript page past what is loaded; the cache keeps it for the next visit. */
    fun loadMoreTranscript() {
        val callId = boundCallId ?: return
        val current = _state.value
        if (current.loadingTranscript || !current.hasMoreTranscript) return
        val cursor = current.transcript.maxOfOrNull { it.sequence } ?: 0
        _state.update { it.copy(loadingTranscript = true) }
        viewModelScope.launch {
            val result = repository.transcript(callId, cursor)
            val failure = result.failure
            _state.update {
                it.copy(
                    loadingTranscript = false,
                    hasMoreTranscript = result.hasMore,
                    error = failure?.let { f -> CallsFailure.of(f.message, f.code) } ?: it.error
                )
            }
        }
    }

    fun mark(kind: String) {
        val callId = boundCallId ?: return
        if (_state.value.marking) return
        _state.update { it.copy(marking = true) }
        viewModelScope.launch {
            when (val outcome = repository.addMark(callId, kind)) {
                is ApiOutcome.Ok -> _state.update { it.copy(marking = false, marks = outcome.value.marks) }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(marking = false, error = CallsFailure.of(outcome.message, outcome.code))
                }
            }
        }
    }

    /** Opens the destructive question; the screen only renders it. */
    fun requestDelete() {
        val current = _state.value
        if (current.deleting || current.deleted || current.missing) return
        _state.update { it.copy(confirmingDelete = true) }
    }

    /** Closes the question without running anything. */
    fun dismissDelete() = _state.update { it.copy(confirmingDelete = false) }

    /**
     * Runs the deletion the reader confirmed. The pending question is consumed atomically before
     * the launch, so a second tap on the same dialog finds nothing to confirm and the daemon is
     * asked exactly once.
     */
    fun confirmDelete(onDeleted: () -> Unit) {
        val callId = boundCallId ?: return
        while (true) {
            val current = _state.value
            if (!current.confirmingDelete || current.deleting) return
            val consumed = current.copy(confirmingDelete = false, deleting = true)
            if (_state.compareAndSet(current, consumed)) break
        }
        viewModelScope.launch {
            when (val outcome = repository.delete(callId)) {
                is ApiOutcome.Ok -> {
                    player.release()
                    _state.update { it.copy(deleting = false, deleted = true) }
                    onDeleted()
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(deleting = false, error = CallsFailure.of(outcome.message, outcome.code))
                }
            }
        }
    }

    /** Downloads the share copy into the app cache, then hands it to the player. */
    fun downloadAudio() {
        val callId = boundCallId ?: return
        val current = _state.value.audio
        if (current.downloading) return
        val file = CallSharing.audioFile(context, callId)
        if (file.exists() && file.length() > 0) {
            prepare(file)
            return
        }
        _state.update { it.copy(audio = it.audio.copy(downloading = true, error = null)) }
        viewModelScope.launch {
            when (val result = repository.downloadAudio(callId, "m4a", file)) {
                is com.phonxiety.bridge.data.ApiResult.Success -> {
                    _state.update { it.copy(audio = it.audio.copy(downloading = false, filePath = file.path)) }
                    prepare(file)
                }

                is com.phonxiety.bridge.data.ApiResult.Failure -> _state.update {
                    it.copy(audio = it.audio.copy(downloading = false, error = CallsFailure.of(result.message, result.code)))
                }
            }
        }
    }

    fun togglePlayback() {
        val audio = _state.value.audio
        if (!audio.prepared) return
        if (audio.playing) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        if (!_state.value.audio.prepared) return
        player.seekTo(positionMs)
    }

    fun audioFile(): File? = _state.value.audio.filePath?.let(::File)

    private fun prepare(file: File) {
        player.prepare(file)
        _state.update { it.copy(audio = it.audio.copy(filePath = file.path, error = null)) }
        playerJob?.cancel()
        playerJob = viewModelScope.launch {
            player.state.collect { playback ->
                _state.update {
                    it.copy(
                        audio = it.audio.copy(
                            prepared = playback.prepared,
                            playing = playback.playing,
                            positionMs = playback.positionMs,
                            durationMs = playback.durationMs
                        )
                    )
                }
            }
        }
    }

    override fun onCleared() {
        player.release()
    }

    private companion object {
        val MISSING_CODES = setOf("call_not_found", "404")
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.stringList(key: String): List<String> = (this[key] as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    .orEmpty()
