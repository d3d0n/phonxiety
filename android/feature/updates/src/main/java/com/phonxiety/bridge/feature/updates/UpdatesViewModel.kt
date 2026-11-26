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
    val appInstall: AppInstallState = AppInstallState.Idle,
    val moduleInstall: ModuleInstallState = ModuleInstallState.Idle,
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

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val service: UpdatesService,
    bridgeState: BridgeStateRepository
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val check = MutableStateFlow<UpdateCheck?>(null)
    private val confirmation = MutableStateFlow<RollbackPlan?>(null)
    private val recoveryDismissed = MutableStateFlow(false)

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

    /** The screen's own state: the check, the confirm dialog, and the two dismissals. */
    private data class LocalState(
        val loading: Boolean,
        val confirmation: RollbackPlan?,
        val recoveryDismissed: Boolean,
        val appRollback: Boolean
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

    private val local = combine(checking, confirmation, recoveryDismissed, appRollback) { loading, confirming, dismissed, rollbackApp ->
        LocalState(loading, confirming, dismissed, rollbackApp)
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
            appInstall = current.appInstall,
            moduleInstall = current.moduleInstall,
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

    fun installApp() {
        if (callActive.value) return
        val manifest = check.value?.manifest ?: return
        appRollback.value = false
        viewModelScope.launch { service.installApp(manifest) }
    }

    fun installModule() {
        if (callActive.value) return
        val manifest = check.value?.manifest ?: return
        viewModelScope.launch { service.installModule(manifest) }
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

    fun confirmRollback() {
        val confirmed = confirmation.value
        confirmation.value = null
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
        recoveryDismissed.value = false
        appRollback.value = false
        service.dismiss()
        refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
