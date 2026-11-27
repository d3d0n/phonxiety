package com.phonxiety.bridge.feature.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.DaemonApi
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.designsystem.UiText
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private var appliedPaired: Boolean? = null
    private var hasResumed = false
    private val loadMutex = Mutex()
    private val publishMutex = Mutex()

    init {
        viewModelScope.launch(io) { load(auto = true) }
        viewModelScope.launch {
            // The link is not the only input that moves on its own: the pairing cache is refreshed
            // on IO, so it can catch up after the link's final transition. Both trigger a pass.
            combine(bridgeState.state, pairing.paired) { bridge, cached -> bridge to cached }
                .distinctUntilChanged()
                .collect { publishFromCache() }
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
                current.copy(message = UiText.Res(R.string.doctor_message_call_blocks_fix))
            }
        }
    }

    fun requestDaemonFix(fix: String) {
        val offer = _state.value.daemonFixes.firstOrNull { it.fix == fix } ?: return
        when (val risk = DoctorPolicy.effectiveRisk(offer.plan, _state.value.callActive)) {
            FixClass.AUTOMATIC -> run(
                UiText.Literal(offer.description ?: fix),
                null,
                offer.plan.copy(risk = risk)
            )

            FixClass.CONFIRM -> _state.update {
                it.copy(
                    pending = FixRequest(
                        rowId = DAEMON_FIX_PREFIX + fix,
                        checkTitle = UiText.Literal(offer.description ?: fix),
                        plan = offer.plan.copy(risk = risk)
                    )
                )
            }

            FixClass.NEVER -> _state.update { current ->
                current.copy(message = UiText.Res(R.string.doctor_message_call_blocks_daemon))
            }
        }
    }

    /**
     * Runs the question's fix exactly once. The pending question is taken out of the state in the
     * same atomic swap that reads it, so a second tap — a double tap, or one landing while the
     * first already started — finds nothing to confirm and runs nothing.
     */
    fun confirmFix() {
        val pending = takePending() ?: return
        run(pending.checkTitle, pending.subject, pending.plan)
    }

    private fun takePending(): FixRequest? {
        while (true) {
            val current = _state.value
            val pending = current.pending ?: return null
            if (_state.compareAndSet(current, current.copy(pending = null))) return pending
        }
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

    private fun run(title: UiText, subject: String?, plan: FixPlan) {
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

    private suspend fun execute(title: UiText, subject: String?, plan: FixPlan) {
        when (plan.fix) {
            DoctorFixId.RESTART_SERVICE -> {
                serviceControl.restart()
                record(DoctorLogEvent.BRIDGE_RESTARTED)
            }

            DoctorFixId.REGRANT_RUNTIME_PERMISSION -> {
                val permission = subject ?: RuntimePermission.RECORD_AUDIO.androidName
                _effects.tryEmit(DoctorEffect.RequestRuntimePermissions(listOf(permission)))
                record(DoctorLogEvent.PERMISSION_ASKED, permission)
            }

            DoctorFixId.REGRANT_NOTIFICATION -> {
                if (subject == DoctorEvaluator.SUBJECT_NOTIFICATION_SETTINGS) {
                    _effects.tryEmit(DoctorEffect.OpenNotificationSettings)
                } else {
                    _effects.tryEmit(
                        DoctorEffect.RequestRuntimePermissions(listOf(RuntimePermission.POST_NOTIFICATIONS.androidName))
                    )
                }
                record(DoctorLogEvent.NOTIFICATIONS_ASKED)
            }

            DoctorFixId.REQUEST_BATTERY_EXEMPTION -> {
                _effects.tryEmit(DoctorEffect.RequestBatteryExemption)
                record(DoctorLogEvent.BACKGROUND_ASKED)
            }

            DoctorFixId.ENABLE_MODULE -> {
                val result = source.enableModule()
                recordResult(DoctorLogEvent.MODULE_ENABLED, DoctorLogEvent.MODULE_ENABLED_OUTPUT, result)
                if (result.isSuccess) reboot()
            }

            DoctorFixId.REINSTALL_MODULE -> {
                val result = source.reinstallModule()
                recordResult(DoctorLogEvent.MODULE_REINSTALLED, DoctorLogEvent.MODULE_REINSTALLED_OUTPUT, result)
                if (result.isSuccess) reboot()
            }

            DoctorFixId.REPAIR_PAIRING -> {
                _state.update { it.copy(page = RecoveryBook.page(RecoveryId.PAIRING_REJECTED, it.provider)) }
                record(DoctorLogEvent.PAIRING_RECOVERY_OPENED)
            }

            DoctorFixId.DAEMON_FIX -> applyDaemonFix(plan.daemonFix ?: return)
        }
    }

    private suspend fun applyDaemonFix(fix: String) {
        when (val outcome = withTimeoutOrNull(DAEMON_TIMEOUT_MS) { daemonApi.doctorFix(fix) }) {
            null -> report(UiText.Res(R.string.doctor_message_daemon_timeout, listOf(DAEMON_TIMEOUT_MS / 1000)))

            is ApiOutcome.Ok -> {
                val applied = outcome.value.applied.joinToString(", ") { it.fix }
                if (applied.isEmpty()) {
                    record(DoctorLogEvent.DAEMON_FIX_APPLIED, fix)
                } else {
                    record(DoctorLogEvent.DAEMON_FIX_APPLIED_MORE, fix, applied)
                }
                if (outcome.value.restartRequired) {
                    report(UiText.Res(R.string.doctor_message_daemon_restart))
                }
            }

            // The daemon's own code and message stay verbatim inside the sentence.
            is ApiOutcome.Failure -> report(
                UiText.Res(
                    R.string.doctor_message_daemon_refused,
                    listOf(fix, outcome.code, outcome.message)
                )
            )
        }
    }

    private suspend fun reboot() {
        val result = source.reboot()
        if (result.isSuccess) {
            record(DoctorLogEvent.REBOOT_REQUESTED)
        } else {
            record(DoctorLogEvent.REBOOT_FAILED, result.detail())
        }
    }

    private fun recordResult(successEvent: DoctorLogEvent, outputEvent: DoctorLogEvent, result: ProviderResult) {
        when (result) {
            is ProviderResult.Success -> {
                val output = result.tail(1).joinToString()
                if (output.isEmpty()) {
                    record(successEvent)
                } else {
                    record(outputEvent, output)
                }
            }

            else -> {
                val detail = result.detail()
                if (detail.isEmpty()) {
                    record(DoctorLogEvent.FIX_FAILED)
                } else {
                    record(DoctorLogEvent.FIX_FAILED, detail)
                }
                report(UiText.Res(R.string.doctor_message_fix_failed))
            }
        }
    }

    private fun ProviderResult.detail(): String = tail().joinToString(" ")

    private fun record(event: DoctorLogEvent, vararg args: String) {
        changeLog.record(DoctorChangeEntry(System.currentTimeMillis(), event, args.toList()))
        _state.update { it.copy(changeLog = changeLog.entries()) }
    }

    private fun report(text: UiText) {
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
                    readError = error.message?.let(UiText::Literal)
                        ?: UiText.Res(R.string.doctor_message_read_failed)
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
        val rows = DoctorEvaluator.evaluate(read, bridge, paired, probe, callActive)
        var published = read

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
                published = fresh
            }
        }

        publish(published)
    }

    /**
     * Publishes a finished read under the same lock the link and pairing collector uses, re-sampling
     * both inputs inside it: a link or a pairing that moved while the daemon probe ran is reflected
     * in these rows, and the collector cannot lose that change to a stale overwrite afterwards.
     */
    private suspend fun publish(read: DoctorSnapshot): Unit = publishMutex.withLock {
        val bridge = bridgeState.state.value
        val paired = pairing.paired.value
        val callActive = bridge.call?.phase?.let { it != CallPhase.ENDED } == true
        val probe = daemon ?: DaemonProbe.Unreachable("pending", "the daemon has not answered yet")
        appliedLink = bridge.link
        appliedCallActive = callActive
        appliedPaired = paired
        _state.update {
            it.copy(
                checking = false,
                rows = DoctorEvaluator.evaluate(
                    snapshot = read,
                    bridge = bridge,
                    paired = paired,
                    daemon = probe,
                    callActive = callActive
                ),
                daemonFixes = daemonFixes(probe),
                provider = read.root.provider?.id,
                callActive = callActive,
                checkedAtEpochMs = read.checkedAtEpochMs,
                changeLog = changeLog.entries()
            )
        }
    }

    /** Re-evaluates from the cached read when the link or the pairing cache moves between passes. */
    private suspend fun publishFromCache(): Unit = publishMutex.withLock {
        val read = snapshot ?: return@withLock
        val bridge = bridgeState.state.value
        val paired = pairing.paired.value
        val callActive = bridge.call?.phase?.let { it != CallPhase.ENDED } == true
        if (bridge.link == appliedLink && callActive == appliedCallActive && paired == appliedPaired) {
            return@withLock
        }
        appliedLink = bridge.link
        appliedCallActive = callActive
        appliedPaired = paired
        val probe = daemon ?: DaemonProbe.Unreachable("pending", "the daemon has not answered yet")
        val rows = DoctorEvaluator.evaluate(
            snapshot = read,
            bridge = bridge,
            paired = paired,
            daemon = probe,
            callActive = callActive
        )
        _state.update { it.copy(rows = rows, callActive = callActive) }
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
