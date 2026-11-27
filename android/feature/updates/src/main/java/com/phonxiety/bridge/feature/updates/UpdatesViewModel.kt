package com.phonxiety.bridge.feature.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateStatus
import com.phonxiety.bridge.data.updates.UpdateTarget
import com.phonxiety.bridge.data.updates.UpdatesService
import com.phonxiety.bridge.model.CallPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the Updates screen renders: one check, the two actions, and the rollback offer with
 * any rollback a previous boot left behind.
 */
data class UpdatesUiState(
    val loading: Boolean = false,
    val check: UpdateCheck? = null,
    /**
     * An update install's progress and failure, for the app row. While an app-only rollback runs it
     * stays idle: the row's retry would install the update over the APK the rollback restores.
     */
    val appInstall: AppInstallState = AppInstallState.Idle,
    val moduleInstall: ModuleInstallState = ModuleInstallState.Idle,
    /**
     * The install the person tapped, waiting for its confirmation. An app install replaces the
     * running app and a module install reboots the phone, so neither starts without this.
     */
    val updateConfirmation: UpdateTarget? = null,
    val rollbackPlan: RollbackPlan? = null,
    val rollbackRecovery: RollbackRecovery? = null,
    /** Set while a confirm dialog is up; a rollback never runs without it. */
    val rollbackConfirmation: RollbackPlan? = null,
    val moduleRollback: RollbackState = RollbackState.Idle,
    /**
     * An app-only rollback's own install: progress and failure for the rollback section, which
     * reads the phone and therefore shows it with the device link down too.
     */
    val rollbackAppInstall: AppInstallState = AppInstallState.Idle,
    /** The pending/completed block was dismissed for this visit; the journal is untouched. */
    val rollbackRecoveryDismissed: Boolean = false,
    /** A call in progress blocks every action that would end it. */
    val callActive: Boolean = false
)

/**
 * Whether [target] may still be asked about. The offer itself has to be the one that was tapped
 * (a re-read check can withdraw it), and no install may be running: a download, a build or the
 * platform's own dialog owns both rows until it reaches a terminal state (Failed, Installed, …).
 * A terminal state is not a reason to refuse: it is exactly when the row's retry, or the next
 * install, is allowed to ask.
 */
private fun offerEligible(
    target: UpdateTarget,
    check: UpdateCheck?,
    app: AppInstallState,
    module: ModuleInstallState
): Boolean {
    if (app.busy || module.busy) return false
    val row = when (target) {
        UpdateTarget.APP -> check?.verdict?.app
        UpdateTarget.MODULE -> check?.verdict?.module
        UpdateTarget.DAEMON -> null
    } ?: return false
    return row.status == UpdateStatus.UPDATE_AVAILABLE && !row.needsAppFirst
}

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val service: UpdatesService,
    bridgeState: BridgeStateRepository
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val check = MutableStateFlow<UpdateCheck?>(null)
    private val confirmation = MutableStateFlow<RollbackPlan?>(null)
    private val recoveryDismissed = MutableStateFlow(false)

    /** The install tapped on a row, asked back once before it runs. */
    private val pendingUpdate = MutableStateFlow<UpdateTarget?>(null)

    /** True while the app-install flow on screen is a rollback, not an update install. */
    private val appRollback = MutableStateFlow(false)

    /**
     * A call in progress. Every action here replaces the app or reboots the phone, which drops the
     * call, so the screen disables them and this class refuses them again.
     */
    private val callActive: StateFlow<Boolean> = bridgeState.state
        .map { state -> state.call?.phase?.let { it != CallPhase.ENDED } == true }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    /** The service's flows paired once, so the state combine below stays within typed overloads. */
    private data class ServiceState(
        val appInstall: AppInstallState,
        val moduleInstall: ModuleInstallState,
        val rollbackPlan: RollbackPlan?,
        val rollbackRecovery: RollbackRecovery?,
        val moduleRollback: RollbackState
    )

    /** The screen's own state: the check, the confirm dialogs, and the two dismissals. */
    private data class LocalState(
        val loading: Boolean,
        val confirmation: RollbackPlan?,
        val recoveryDismissed: Boolean,
        val appRollback: Boolean,
        val pendingUpdate: UpdateTarget?
    )

    private val services = combine(
        service.appInstall,
        service.moduleInstall,
        service.rollbackPlan,
        service.rollbackRecovery,
        service.moduleRollback
    ) { app, module, plan, recovery, rollback ->
        ServiceState(app, module, plan, recovery, rollback)
    }

    private val local = combine(
        checking,
        confirmation,
        recoveryDismissed,
        appRollback,
        pendingUpdate
    ) { loading, confirming, dismissed, rollbackApp, update ->
        LocalState(loading, confirming, dismissed, rollbackApp, update)
    }

    val state: StateFlow<UpdatesUiState> = combine(
        services,
        check,
        local,
        callActive
    ) { current, checked, localState, onCall ->
        UpdatesUiState(
            loading = localState.loading,
            check = checked,
            appInstall = current.appInstall.takeUnless { localState.appRollback } ?: AppInstallState.Idle,
            moduleInstall = current.moduleInstall,
            updateConfirmation = localState.pendingUpdate?.takeIf { target ->
                offerEligible(target, checked, current.appInstall, current.moduleInstall)
            },
            rollbackPlan = current.rollbackPlan,
            rollbackRecovery = current.rollbackRecovery,
            rollbackConfirmation = localState.confirmation?.takeIf { planned ->
                // A re-read plan of another kind closes the dialog instead of confirming a stale one.
                when (planned) {
                    is RollbackPlan.AppOnly -> current.rollbackPlan is RollbackPlan.AppOnly
                    is RollbackPlan.WithPreviousModule -> current.rollbackPlan is RollbackPlan.WithPreviousModule
                    is RollbackPlan.Unavailable -> false
                }
            },
            moduleRollback = current.moduleRollback,
            rollbackAppInstall = current.appInstall.takeIf { localState.appRollback } ?: AppInstallState.Idle,
            rollbackRecoveryDismissed = localState.recoveryDismissed,
            callActive = onCall
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UpdatesUiState(loading = true)
    )

    init {
        refresh()
        // An install that finished without restarting this process (the fallback confirmation
        // path) must not leave stale "installed" versions on the rows.
        viewModelScope.launch {
            service.appInstall.collect { install ->
                if (install is AppInstallState.Installed) refresh()
            }
        }
        // A question whose offer was withdrawn, or whose install started elsewhere, is dropped
        // rather than hidden: otherwise it would come back on its own when the row offers again,
        // and a later confirm would spend it without a tap.
        viewModelScope.launch {
            combine(
                pendingUpdate,
                check,
                service.appInstall,
                service.moduleInstall
            ) { target, checked, app, module ->
                target to (target != null && offerEligible(target, checked, app, module))
            }
                .collect { (target, eligible) ->
                    if (target != null && !eligible) pendingUpdate.compareAndSet(target, null)
                }
        }
    }

    fun refresh() {
        if (checking.value) return
        recoveryDismissed.value = false
        viewModelScope.launch {
            checking.value = true
            check.value = service.check()
            checking.value = false
        }
    }

    /** The app row's action: it asks once, then [confirmUpdate] runs it. */
    fun installApp() {
        if (callActive.value) return
        if (check.value?.manifest == null) return
        pendingUpdate.value = UpdateTarget.APP
    }

    /**
     * The module zip is rebuilt from the running APK. Before this phone runs the release's app the
     * rebuild would put the previous allowlist back, so the action waits for the app row.
     */
    fun installModule() {
        if (callActive.value) return
        val checked = check.value ?: return
        if (checked.verdict?.module?.needsAppFirst == true) return
        if (checked.manifest == null) return
        pendingUpdate.value = UpdateTarget.MODULE
    }

    /**
     * The confirmation the dialog is showing: the one place an install starts from a row. The
     * pending target is consumed first, atomically, so a double tap starts nothing twice.
     */
    fun confirmUpdate() {
        val target = pendingUpdate.value ?: return
        if (!pendingUpdate.compareAndSet(target, null)) return
        // The dialog's own state can outlive the call it was opened before; a call still wins.
        if (callActive.value) return
        val checked = check.value ?: return
        val manifest = checked.manifest ?: return
        // The offer can be withdrawn while the dialog is up (a re-read check) or its row taken
        // by an install that started elsewhere; the consumed confirmation must not spend itself.
        if (!offerEligible(target, checked, service.appInstall.value, service.moduleInstall.value)) return
        when (target) {
            UpdateTarget.APP -> {
                appRollback.value = false
                viewModelScope.launch { service.installApp(manifest) }
            }

            UpdateTarget.MODULE -> {
                viewModelScope.launch { service.installModule(manifest) }
            }

            UpdateTarget.DAEMON -> Unit
        }
    }

    fun dismissUpdateConfirmation() {
        pendingUpdate.value = null
    }

    /**
     * Both rollbacks replace the app under the running process — the combined one reboots the
     * phone, and the app-only one is a root downgrade — so each one asks first. A rollback a
     * previous attempt left pending is never asked for again: retrying finishes what the journal
     * recorded. A call in progress blocks all of them until it ends.
     */
    fun rollback() {
        if (callActive.value) return
        if (state.value.rollbackRecovery is RollbackRecovery.Pending) {
            appRollback.value = false
            viewModelScope.launch { service.rollbackApp() }
            return
        }
        val plan = state.value.rollbackPlan
        if (plan is RollbackPlan.AppOnly || plan is RollbackPlan.WithPreviousModule) {
            confirmation.value = plan
            return
        }
        appRollback.value = false
        viewModelScope.launch { service.rollbackApp() }
    }

    /**
     * Consumes the pending plan atomically before starting, so a double tap on the dialog runs one
     * rollback; with nothing pending it starts nothing, rather than running an unconfirmed one.
     */
    fun confirmRollback() {
        val confirmed = confirmation.value ?: return
        if (!confirmation.compareAndSet(confirmed, null)) return
        if (callActive.value) return
        appRollback.value = confirmed is RollbackPlan.AppOnly
        viewModelScope.launch { service.rollbackApp() }
    }

    fun dismissRollbackConfirmation() {
        confirmation.value = null
    }

    /** Hides the unfinished-rollback block until the next check; the root journal stays. */
    fun dismissRollbackRecovery() {
        recoveryDismissed.value = true
    }

    fun dismiss() {
        confirmation.value = null
        pendingUpdate.value = null
        recoveryDismissed.value = false
        appRollback.value = false
        service.dismiss()
        refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
