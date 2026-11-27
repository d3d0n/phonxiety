package com.phonxiety.bridge.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhonePreferences
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PairingGate
import com.phonxiety.bridge.model.PathKind
import com.phonxiety.bridge.net.CandidateMerge
import com.phonxiety.bridge.net.CandidateStore
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Settings and auto-tune.
 *
 * Daemon-owned values come from `/app/v1/settings` and are written back as one PUT with only the
 * changed keys. Phone-owned values live in DataStore through [PhonePreferences], and the engine
 * turns them into the next `hello`, `set_prefs` and the Wi-Fi lock. Auto-tune is off unless the
 * user turns it on; every change it makes is written to a local change log with its measurement
 * and can be reverted from this screen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: DaemonApi,
    private val bridgeState: BridgeStateRepository,
    private val phonePreferences: PhonePreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val changeLog = AutoTuneLogStore(File(context.filesDir, "settings/auto-tune.json"))
    private val candidateStore = CandidateStore.of(context)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** How long the current path has been relay-only, and the latest measurement auto-tune cites. */
    private var relaySinceMs: Long? = null
    private var latestLinkTest: com.phonxiety.bridge.model.LinkTestResult? = null
    private val evaluateLock = Mutex()

    /** The entries a bulk revert just undid, so its snackbar can put them back. */
    private var lastReverted: List<AutoTuneLogEntry> = emptyList()

    /** The affected preferences as they were just before a bulk revert; Undo restores them as is. */
    private var lastRevertedSnapshot: RevertSnapshot? = null

    /** Only the keys the bulk revert touched; a null value means that key is untouched. */
    private data class RevertSnapshot(
        val codec: String?,
        val maxBitrate: Int?,
        val wifiLowLatency: Boolean?
    )

    init {
        viewModelScope.launch {
            phonePreferences.settings.collect { settings ->
                _state.update { it.copy(phone = settings, autoTune = it.autoTune.copy(enabled = settings.autoTuneEnabled)) }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(autoTune = it.autoTune.copy(changes = changeLog.load())) }
        }
        viewModelScope.launch { loadDaemonSettings() }
        viewModelScope.launch {
            _state.update { it.copy(connection = it.connection.copy(candidates = candidateStore.load())) }
            refreshCandidates()
        }
        viewModelScope.launch {
            bridgeState.state.collect { bridge -> onBridgeState(bridge) }
        }
    }

    // -- daemon settings ------------------------------------------------

    fun refreshDaemonSettings() {
        viewModelScope.launch { loadDaemonSettings() }
        viewModelScope.launch {
            _state.update { it.copy(connection = it.connection.copy(candidates = candidateStore.load())) }
            refreshCandidates()
        }
    }

    private suspend fun loadDaemonSettings() {
        _state.update { it.copy(daemon = it.daemon.copy(loading = true, error = null)) }
        when (val outcome = api.settings()) {
            is ApiOutcome.Ok -> {
                val values = outcome.value.values.toValues()
                _state.update {
                    it.copy(
                        daemon = it.daemon.copy(
                            loading = false,
                            values = values,
                            form = values.toForm(),
                            restartRequiredFields = outcome.value.restartRequiredFields,
                            error = null
                        )
                    )
                }
                mirrorDaemonRelays(values.irohRelays)
            }

            is ApiOutcome.Failure -> _state.update {
                it.copy(
                    daemon = it.daemon.copy(
                        loading = false,
                        error = UiText.Literal(outcome.describe()),
                        errorOnSave = false
                    )
                )
            }
        }
    }

    /** One form edit; keys are the [DaemonForm] property names. */
    fun updateForm(transform: (DaemonForm) -> DaemonForm) = _state.update {
        it.copy(daemon = it.daemon.copy(form = transform(it.daemon.form), error = null))
    }

    fun saveDaemonSettings() {
        val daemon = _state.value.daemon
        if (daemon.saving) return
        val edited = daemon.form.toValuesOrNull()
        if (edited == null) {
            // A numeric field that is not a whole number is never replaced by the old value.
            _state.update { it.copy(message = UiText.Res(R.string.settings_message_unsaved_invalid)) }
            return
        }
        val changes = diff(daemon.values, edited)
        if (changes.isEmpty()) {
            _state.update { it.copy(message = UiText.Res(R.string.settings_message_nothing_changed)) }
            return
        }
        _state.update { it.copy(daemon = it.daemon.copy(saving = true, error = null)) }
        viewModelScope.launch {
            when (val outcome = api.putSettings(changes)) {
                is ApiOutcome.Ok -> {
                    val values = outcome.value.values.toValues()
                    _state.update {
                        it.copy(
                            daemon = it.daemon.copy(
                                saving = false,
                                values = values,
                                form = values.toForm(),
                                restartRequiredFields = outcome.value.restartRequiredFields,
                                changesApplied = outcome.value.changed,
                                error = null
                            ),
                            message = if (outcome.value.restartRequired) {
                                UiText.Plural(
                                    R.plurals.settings_message_saved_restart,
                                    outcome.value.restartRequiredFields.size
                                )
                            } else {
                                UiText.Res(R.string.settings_message_saved)
                            }
                        )
                    }
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(
                        daemon = it.daemon.copy(
                            saving = false,
                            error = UiText.Literal(outcome.describe()),
                            errorOnSave = true
                        )
                    )
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.toValues(): DaemonSettingsValues = try {
        json.decodeFromJsonElement(DaemonSettingsValues.serializer(), this)
    } catch (_: Exception) {
        DaemonSettingsValues()
    }

    // -- phone settings -------------------------------------------------

    fun setCodec(codec: String) {
        val normalized = PhoneCodec.normalize(codec)
        viewModelScope.launch {
            phonePreferences.update { it.copy(preferredCodec = normalized) }
        }
    }

    fun setMaxBitrate(bitrate: Int) {
        viewModelScope.launch {
            phonePreferences.update { it.copy(maxBitrate = bitrate.coerceIn(PhoneSettings.MIN_BITRATE, PhoneSettings.MAX_BITRATE)) }
        }
    }

    fun setWifiLowLatency(enabled: Boolean) {
        viewModelScope.launch { phonePreferences.update { it.copy(wifiLowLatency = enabled) } }
    }

    fun setThermalAwareness(enabled: Boolean) {
        viewModelScope.launch { phonePreferences.update { it.copy(thermalAwareness = enabled) } }
    }

    fun setBatteryAwareness(enabled: Boolean) {
        viewModelScope.launch { phonePreferences.update { it.copy(batteryAwareness = enabled) } }
    }

    fun setLogLevel(level: String) {
        if (level !in PhoneSettings.LOG_LEVELS) return
        viewModelScope.launch { phonePreferences.update { it.copy(logLevel = level) } }
    }

    // -- connection ------------------------------------------------------

    /** Pins one transport or leaves the choice to selection. */
    fun setConnectionMode(mode: String) {
        val chosen = ConnectionMode.of(mode)
        viewModelScope.launch {
            phonePreferences.update { it.copy(connectionMode = chosen.wire) }
            val message = when (chosen) {
                ConnectionMode.AUTO -> R.string.settings_message_connection_auto
                ConnectionMode.IROH_ONLY -> R.string.settings_message_connection_iroh
                ConnectionMode.LAN_ONLY -> R.string.settings_message_connection_lan
                ConnectionMode.TAILSCALE_ONLY -> R.string.settings_message_connection_tailscale
            }
            _state.update { it.copy(message = UiText.Res(message)) }
        }
    }

    /** Picks which relay list the phone's iroh endpoint uses. */
    fun setRelayMode(mode: String) {
        val chosen = RelayModeSetting.of(mode)
        viewModelScope.launch {
            phonePreferences.update { it.copy(relayMode = chosen.wire) }
        }
    }

    /** The phone's own relay URLs, one per line; invalid entries are dropped, not refused. */
    fun setRelayUrls(text: String) {
        val urls = RelayOptions.sanitize(RelayOptions.split(text))
        viewModelScope.launch { phonePreferences.update { it.copy(relayUrls = urls) } }
    }

    /**
     * Re-reads the daemon's other addresses from `/app/v1/info` and stores them with the
     * certificate pin; link selection can then use the LAN and the tailnet when they answer.
     */
    fun refreshCandidates() {
        val current = _state.value.connection
        if (current.refreshing) return
        _state.update { it.copy(connection = it.connection.copy(refreshing = true, error = null)) }
        viewModelScope.launch {
            val epoch = PairingGate.epoch()
            when (val outcome = api.info()) {
                is ApiOutcome.Ok -> {
                    // The answer belongs to the pairing the gateway served; a
                    // re-pair while it was in flight leaves it unapplied.  This
                    // path only merges: binding belongs to pairing and startup
                    // transactions, so a refresh can never discard a newer
                    // pairing's addresses or restore an older identity.
                    val identity = PairingGate.expectedIdentity()?.takeIf { PairingGate.epoch() == epoch }
                    val stored = identity?.let {
                        CandidateMerge.store(candidateStore, outcome.value.connection, it)
                    }
                    _state.update {
                        it.copy(
                            connection = it.connection.copy(
                                candidates = stored ?: candidateStore.load(),
                                refreshing = false,
                                error = null
                            )
                        )
                    }
                }

                is ApiOutcome.Failure -> _state.update {
                    it.copy(
                        connection = it.connection.copy(
                            refreshing = false,
                            error = UiText.Literal(outcome.describe())
                        )
                    )
                }
            }
        }
    }

    /** Keeps the daemon's relay list where the engine can read it for the daemon relay mode. */
    private fun mirrorDaemonRelays(urls: List<String>) {
        viewModelScope.launch {
            phonePreferences.update { it.copy(daemonRelayUrls = RelayOptions.sanitize(urls)) }
        }
    }

    // -- auto-tune ------------------------------------------------------

    fun setAutoTuneEnabled(enabled: Boolean) {
        viewModelScope.launch {
            phonePreferences.update { it.copy(autoTuneEnabled = enabled) }
            if (enabled) refreshMeasurement()
            evaluateAutoTune()
        }
    }

    /** Runs the rules now; the screen also evaluates whenever the bridge state changes. */
    fun evaluateNow() {
        viewModelScope.launch {
            refreshMeasurement()
            evaluateAutoTune()
        }
    }

    private suspend fun refreshMeasurement() {
        when (val outcome = api.linkTests(limit = 1)) {
            is ApiOutcome.Ok -> latestLinkTest = outcome.value.tests.firstOrNull()
            is ApiOutcome.Failure -> Unit // keep the previous measurement; the rules degrade honestly
        }
    }

    private fun onBridgeState(bridge: com.phonxiety.bridge.model.BridgeState) {
        val up = bridge.link as? LinkState.Up
        val now = System.currentTimeMillis()
        relaySinceMs = if (up?.path == PathKind.RELAY) relaySinceMs ?: now else null
        // One evaluation at a time; a state tick while one runs is redundant, an explicit
        // "Evaluate now" waits instead of being dropped.
        if (_state.value.autoTune.enabled && !evaluateLock.isLocked) {
            viewModelScope.launch { evaluateAutoTune() }
        }
    }

    private suspend fun evaluateAutoTune() {
        evaluateLock.withLock {
            val current = _state.value
            if (!current.autoTune.enabled) return@withLock
            applyAutoTune(current)
        }
    }

    private suspend fun applyAutoTune(current: SettingsUiState) {
        val link = bridgeState.state.value.link as? LinkState.Up
        val observation = TuneObservation(
            path = link?.path,
            relayForMs = relaySinceMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
            network = bridgeState.state.value.device.network,
            thermal = bridgeState.state.value.device.thermal,
            batteryPercent = bridgeState.state.value.device.batteryPercent,
            charging = bridgeState.state.value.device.charging,
            lastLinkTest = latestLinkTest,
            missingPermissions = bridgeState.state.value.device.missingPermissions
        )
        val action = AutoTune.evaluate(observation, current.phone)
        val recorded = mutableListOf<AutoTuneLogEntry>()
        for (change in action.changes) {
            if (apply(change)) {
                recorded += AutoTuneLogEntry(
                    id = UUID.randomUUID().toString(),
                    atMs = System.currentTimeMillis(),
                    key = change.key.key,
                    from = change.from,
                    to = change.to,
                    reasonId = change.reason.name,
                    reasonArgs = change.reasonArgs
                )
            }
        }
        var log = current.autoTune.changes
        for (entry in recorded.asReversed()) {
            log = changeLog.append(entry)
        }
        // Apply the writes to the rendered state now, so the next evaluation compares against
        // the new values instead of logging the same change twice.
        val phone = if (recorded.isEmpty()) current.phone else phonePreferences.settings.first()
        _state.update {
            it.copy(
                phone = phone,
                autoTune = it.autoTune.copy(
                    changes = log,
                    suggestions = action.suggestions,
                    lastEvaluatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun apply(change: TuneChange): Boolean {
        when (change.key) {
            TuneKey.CODEC -> phonePreferences.update { it.copy(preferredCodec = PhoneCodec.normalize(change.to)) }

            TuneKey.MAX_BITRATE -> {
                val bitrate = change.to.toIntOrNull() ?: return false
                phonePreferences.update { it.copy(maxBitrate = bitrate) }
            }

            TuneKey.WIFI_LOW_LATENCY -> phonePreferences.update { it.copy(wifiLowLatency = change.to == "true") }
        }
        return true
    }

    fun revertChange(id: String) {
        viewModelScope.launch {
            val entry = _state.value.autoTune.changes.firstOrNull { it.id == id } ?: return@launch
            if (entry.reverted) return@launch
            revert(entry)
            val log = changeLog.markReverted(setOf(entry.id))
            _state.update {
                it.copy(
                    autoTune = it.autoTune.copy(changes = log),
                    message = UiText.Plural(R.plurals.settings_message_reverted, 1)
                )
            }
        }
    }

    fun revertEverything() {
        viewModelScope.launch {
            val pending = _state.value.autoTune.changes.filterNot { it.reverted }
            if (pending.isEmpty()) {
                _state.update {
                    it.copy(
                        autoTune = it.autoTune.copy(changes = _state.value.autoTune.changes),
                        message = UiText.Res(R.string.settings_message_nothing_to_revert),
                        undoAvailable = false
                    )
                }
                return@launch
            }
            // The values just before the revert are what Undo puts back: replaying the log would
            // guess the order of repeated changes and lose a manual adjustment made meanwhile.
            val snapshot = snapshotFor(pending)
            // One write per key, back to the `from` of its oldest pending change, so a key that
            // changed twice in a row ends at the value auto-tune first found.
            val targets = TuneKey.entries.mapNotNull { key -> pending.lastOrNull { TuneKey.of(it.key) == key } }
            for (entry in targets) revert(entry)
            val log = changeLog.markReverted(pending.map { it.id }.toSet())
            lastReverted = pending
            lastRevertedSnapshot = snapshot
            _state.update {
                it.copy(
                    autoTune = it.autoTune.copy(changes = log),
                    message = UiText.Plural(R.plurals.settings_message_reverted, pending.size),
                    undoAvailable = true
                )
            }
        }
    }

    /** Puts back what a bulk revert undid; the snackbar offers this instead of a confirmation. */
    fun undoRevertEverything() {
        viewModelScope.launch {
            val undone = lastReverted
            val snapshot = lastRevertedSnapshot
            if (undone.isEmpty() || snapshot == null) return@launch
            phonePreferences.update { settings ->
                settings.copy(
                    preferredCodec = snapshot.codec ?: settings.preferredCodec,
                    maxBitrate = snapshot.maxBitrate ?: settings.maxBitrate,
                    wifiLowLatency = snapshot.wifiLowLatency ?: settings.wifiLowLatency
                )
            }
            val log = changeLog.markReverted(undone.map { it.id }.toSet(), reverted = false)
            lastReverted = emptyList()
            lastRevertedSnapshot = null
            _state.update {
                it.copy(
                    autoTune = it.autoTune.copy(changes = log),
                    message = UiText.Res(R.string.settings_message_changes_restored),
                    undoAvailable = false
                )
            }
        }
    }

    private suspend fun snapshotFor(entries: List<AutoTuneLogEntry>): RevertSnapshot {
        val keys = entries.mapNotNull { TuneKey.of(it.key) }.toSet()
        val current = phonePreferences.settings.first()
        return RevertSnapshot(
            codec = current.preferredCodec.takeIf { TuneKey.CODEC in keys },
            maxBitrate = current.maxBitrate.takeIf { TuneKey.MAX_BITRATE in keys },
            wifiLowLatency = current.wifiLowLatency.takeIf { TuneKey.WIFI_LOW_LATENCY in keys }
        )
    }

    private suspend fun revert(entry: AutoTuneLogEntry) {
        when (TuneKey.of(entry.key)) {
            TuneKey.CODEC -> phonePreferences.update { it.copy(preferredCodec = PhoneCodec.normalize(entry.from)) }

            TuneKey.MAX_BITRATE -> entry.from.toIntOrNull()?.let { from ->
                phonePreferences.update { it.copy(maxBitrate = from) }
            }

            TuneKey.WIFI_LOW_LATENCY -> phonePreferences.update { it.copy(wifiLowLatency = entry.from == "true") }

            null -> Unit
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null, undoAvailable = false) }

    private fun ApiOutcome.Failure.describe(): String = "$code: $message"
}
