package com.phonxiety.bridge.feature.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RuntimePermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Things only the hosting UI can do: system dialogs and settings screens. */
sealed interface DoctorEffect {
    data class RequestRuntimePermissions(val permissions: List<String>) : DoctorEffect

    data object RequestBatteryExemption : DoctorEffect

    data object OpenNotificationSettings : DoctorEffect
}

/**
 * Runs the Doctor screen: one read per check pass, the automatic fixes that read allows, and the
 * confirm-first fixes the user picks. The decision tree itself is [DoctorPolicy]; this class only
 * performs what the tree returns.
 */
@HiltViewModel
class DoctorViewModel
@Inject
constructor(
    private val source: DoctorSource,
    private val bridgeState: BridgeStateRepository,
    private val pairing: PairingRepository,
    private val daemonApi: DaemonApi,
    private val changeLog: DoctorChangeLog,
    private val serviceControl: DoctorServiceControl,
    @DoctorDispatcher private val io: CoroutineDispatcher
) : ViewModel() {
    private val _state = MutableStateFlow(DoctorUiState(changeLog = changeLog.entries()))
    val state: StateFlow<DoctorUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DoctorEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<DoctorEffect> = _effects.asSharedFlow()

    private var snapshot: DoctorSnapshot? = null
    private var daemon: DaemonProbe? = null
    private var appliedLink: LinkState? = null
    private var appliedCallActive = false
    private var hasResumed = false
    private val loadMutex = Mutex()

    init {
        viewModelScope.launch(io) { load(auto = true) }
        viewModelScope.launch {
            bridgeState.state.collect { bridge ->
                val last = snapshot ?: return@collect
                val callActive = bridge.call?.phase?.let { it != CallPhase.ENDED } == true
                if (bridge.link == appliedLink && callActive == appliedCallActive) return@collect
                appliedLink = bridge.link
                appliedCallActive = callActive
                val rows = DoctorEvaluator.evaluate(
                    snapshot = last,
                    bridge = bridge,
                    paired = pairing.paired.value,
                    daemon = daemon ?: DaemonProbe.Unreachable("pending", "the daemon has not answered yet"),
                    callActive = callActive
                )
                _state.update { it.copy(rows = rows, callActive = callActive) }
            }
        }
    }

    /** Re-reads the phone and the daemon, then runs whatever the tree classifies as automatic. */
    fun refresh() {
        viewModelScope.launch(io) { load(auto = true) }
    }

    /**
     * Coming back from a system dialog or the root manager is exactly when a re-read matters. The
     * first resume follows `init`, which already reads, so it does not probe a second time.
     */
    fun onReturnedToForeground() {
        if (!hasResumed) {
            hasResumed = true
            return
        }
        if (_state.value.checking) return
        viewModelScope.launch(io) { load(auto = false) }
    }

    fun onPermissionsResult() {
        refresh()
    }

    fun requestFix(rowId: String) {
        val row = _state.value.row(rowId) ?: return
        val plan = row.plan ?: return
        if (!row.needsFix) return
        when (val risk = DoctorPolicy.effectiveRisk(plan, _state.value.callActive)) {
            FixClass.AUTOMATIC -> run(row.title, row.subject, plan.copy(risk = risk))

            FixClass.CONFIRM -> _state.update {
                it.copy(pending = FixRequest(row.id, row.title, plan.copy(risk = risk), row.subject))
            }

            FixClass.NEVER -> _state.update { current ->
                current.copy(message = "Not while a call is running — ${plan.fix.label} would drop it.")
            }
        }
    }

    fun requestDaemonFix(fix: String) {
        val offer = _state.value.daemonFixes.firstOrNull { it.fix == fix } ?: return
        when (val risk = DoctorPolicy.effectiveRisk(offer.plan, _state.value.callActive)) {
            FixClass.AUTOMATIC -> run(fix, null, offer.plan.copy(risk = risk))

            FixClass.CONFIRM -> _state.update {
                it.copy(
                    pending = FixRequest(
                        rowId = DAEMON_FIX_PREFIX + fix,
                        title = offer.description ?: fix,
                        plan = offer.plan.copy(risk = risk)
                    )
                )
            }

            FixClass.NEVER -> _state.update { current ->
                current.copy(message = "Not while a call is running: restarting the daemon would drop it.")
            }
        }
    }

    fun confirmFix() {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        run(pending.title, pending.subject, pending.plan)
    }

    fun dismissConfirm() {
        _state.update { it.copy(pending = null) }
    }

    fun select(rowId: String?) {
        _state.update { it.copy(selected = rowId) }
    }

    fun openRecovery(id: RecoveryId) {
        val provider = _state.value.provider
        _state.update { it.copy(page = RecoveryBook.page(id, provider)) }
    }

    fun closeRecovery() {
        _state.update { it.copy(page = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun run(title: String, subject: String?, plan: FixPlan) {
        if (_state.value.running != null) return
        viewModelScope.launch(io) {
            _state.update { it.copy(running = plan.fix) }
            try {
                execute(title, subject, plan)
            } finally {
                _state.update { it.copy(running = null) }
                load(auto = false)
            }
        }
    }

    private suspend fun execute(title: String, subject: String?, plan: FixPlan) {
        when (plan.fix) {
            DoctorFixId.RESTART_SERVICE -> {
                serviceControl.restart()
                record("Restarted the bridge service to reopen the link")
            }

            DoctorFixId.REGRANT_RUNTIME_PERMISSION -> {
                val permission = subject ?: RuntimePermission.RECORD_AUDIO.androidName
                _effects.tryEmit(DoctorEffect.RequestRuntimePermissions(listOf(permission)))
                record("Asked for $permission again")
            }

            DoctorFixId.REGRANT_NOTIFICATION -> {
                if (subject == DoctorEvaluator.SUBJECT_NOTIFICATION_SETTINGS) {
                    _effects.tryEmit(DoctorEffect.OpenNotificationSettings)
                } else {
                    _effects.tryEmit(
                        DoctorEffect.RequestRuntimePermissions(listOf(RuntimePermission.POST_NOTIFICATIONS.androidName))
                    )
                }
                record("Asked to allow notifications")
            }

            DoctorFixId.REQUEST_BATTERY_EXEMPTION -> {
                _effects.tryEmit(DoctorEffect.RequestBatteryExemption)
                record("Asked for the battery exemption")
            }

            DoctorFixId.ENABLE_MODULE -> {
                val result = source.enableModule()
                recordResult("Re-enabling the module", result)
                if (result.isSuccess) reboot()
            }

            DoctorFixId.REINSTALL_MODULE -> {
                val result = source.reinstallModule()
                recordResult("Reinstalling the module", result)
                if (result.isSuccess) reboot()
            }

            DoctorFixId.REPAIR_PAIRING -> {
                _state.update { it.copy(page = RecoveryBook.page(RecoveryId.PAIRING_REJECTED, it.provider)) }
                record("Opened pairing recovery")
            }

            DoctorFixId.DAEMON_FIX -> applyDaemonFix(plan.daemonFix ?: return)
        }
    }

    private suspend fun applyDaemonFix(fix: String) {
        when (val outcome = withTimeoutOrNull(DAEMON_TIMEOUT_MS) { daemonApi.doctorFix(fix) }) {
            null -> report("The daemon did not answer within ${DAEMON_TIMEOUT_MS / 1000} s")

            is ApiOutcome.Ok -> {
                val applied = outcome.value.applied.joinToString(", ") { it.fix }
                record("Daemon applied $fix" + if (applied.isNotEmpty()) " ($applied)" else "")
                if (outcome.value.restartRequired) {
                    _state.update { it.copy(message = "Restart phonxietyd on the computer so the new value takes effect.") }
                }
            }

            is ApiOutcome.Failure -> report("The daemon refused $fix: ${outcome.code} ${outcome.message}")
        }
    }

    private suspend fun reboot() {
        val result = source.reboot()
        if (result.isSuccess) {
            record("Requested the reboot that applies the module")
        } else {
            recordResult("Rebooting", result)
        }
    }

    private fun recordResult(action: String, result: ProviderResult) {
        when (result) {
            is ProviderResult.Success -> {
                val output = result.tail(1).joinToString()
                record(if (output.isEmpty()) action else "$action: $output")
            }

            else -> {
                val detail = result.tail().joinToString("\n").ifEmpty { "the provider printed nothing" }
                record("$action failed: $detail")
                report("$action failed. Open Guided recovery for the provider's own commands.")
            }
        }
    }

    private fun record(text: String) {
        changeLog.record(DoctorChangeEntry(System.currentTimeMillis(), text))
        _state.update { it.copy(changeLog = changeLog.entries()) }
    }

    private fun report(text: String) {
        _state.update { it.copy(message = text) }
    }

    private suspend fun load(auto: Boolean): Unit = loadMutex.withLock {
        // Rows already on screen stay there while a re-check runs; only the first pass is blank.
        _state.update { it.copy(checking = it.rows.isEmpty(), readError = null) }
        val read = try {
            source.read()
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    checking = false,
                    readError = error.message ?: "the device could not be read"
                )
            }
            return
        }
        val bridge = bridgeState.state.value
        val paired = pairing.paired.value
        var probe = probeDaemon()
        val callActive = bridge.call?.phase?.let { it != CallPhase.ENDED } == true

        snapshot = read
        daemon = probe
        var rows = DoctorEvaluator.evaluate(read, bridge, paired, probe, callActive)

        if (auto) {
            val automatic = DoctorPolicy.automaticFixes(rows, callActive)
            automatic.forEach { row ->
                val plan = requireNotNull(row.plan)
                _state.update { it.copy(running = plan.fix) }
                try {
                    execute(row.title, row.subject, plan)
                } finally {
                    _state.update { it.copy(running = null) }
                }
            }
            if (automatic.isNotEmpty()) {
                val fresh = runCatching { source.read() }.getOrDefault(read)
                probe = probeDaemon()
                snapshot = fresh
                daemon = probe
                rows = DoctorEvaluator.evaluate(fresh, bridge, paired, probe, callActive)
            }
        }

        appliedLink = bridge.link
        appliedCallActive = callActive
        _state.update {
            it.copy(
                checking = false,
                rows = rows,
                daemonFixes = daemonFixes(probe),
                provider = read.root.provider?.id,
                callActive = callActive,
                checkedAtEpochMs = read.checkedAtEpochMs,
                changeLog = changeLog.entries()
            )
        }
    }

    private suspend fun probeDaemon(): DaemonProbe {
        val outcome = withTimeoutOrNull(DAEMON_TIMEOUT_MS) { daemonApi.doctor() }
        return when (outcome) {
            null -> DaemonProbe.Unreachable(
                "timeout",
                "the daemon did not answer within ${DAEMON_TIMEOUT_MS / 1000} s"
            )

            is ApiOutcome.Ok -> DaemonProbe.Reached(outcome.value)

            is ApiOutcome.Failure -> DaemonProbe.Unreachable(outcome.code, outcome.message)
        }
    }

    private fun daemonFixes(probe: DaemonProbe): List<DaemonFixOffer> = (probe as? DaemonProbe.Reached)?.report?.fixes.orEmpty().map { fix ->
        DaemonFixOffer(
            fix = fix.fix,
            description = fix.description,
            plan = DoctorPolicy.daemonPlan(fix.fix, fix.description, fix.restartRequired)
        )
    }

    private companion object {
        const val DAEMON_TIMEOUT_MS = 20_000L
        const val DAEMON_FIX_PREFIX = "daemon_fix:"
    }
}
