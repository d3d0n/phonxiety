package com.phonxiety.bridge.feature.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdatesService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** Set while the confirm dialog is up; the combined rollback never runs without it. */
    val rollbackConfirmation: RollbackPlan.WithPreviousModule? = null,
    val moduleRollback: RollbackState = RollbackState.Idle,
    /**
     * An app-only rollback's own install: progress and failure for the rollback section, which
     * reads the phone and therefore shows it with the device link down too.
     */
    val rollbackAppInstall: AppInstallState = AppInstallState.Idle,
    /** The pending/completed block was dismissed for this visit; the journal is untouched. */
    val rollbackRecoveryDismissed: Boolean = false
)

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val service: UpdatesService
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val check = MutableStateFlow<UpdateCheck?>(null)
    private val confirmation = MutableStateFlow<RollbackPlan.WithPreviousModule?>(null)
    private val recoveryDismissed = MutableStateFlow(false)

    /** True while the app-install flow on screen is a rollback, not an update install. */
    private val appRollback = MutableStateFlow(false)

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
        val confirmation: RollbackPlan.WithPreviousModule?,
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
        local
    ) { current, checked, localState ->
        UpdatesUiState(
            loading = localState.loading,
            check = checked,
            appInstall = current.appInstall,
            moduleInstall = current.moduleInstall,
            rollbackPlan = current.rollbackPlan,
            rollbackRecovery = current.rollbackRecovery,
            rollbackConfirmation = localState.confirmation?.takeIf { current.rollbackPlan is RollbackPlan.WithPreviousModule },
            moduleRollback = current.moduleRollback,
            rollbackAppInstall = current.appInstall.takeIf { localState.appRollback } ?: AppInstallState.Idle,
            rollbackRecoveryDismissed = localState.recoveryDismissed
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
        val manifest = check.value?.manifest ?: return
        appRollback.value = false
        viewModelScope.launch { service.installApp(manifest) }
    }

    fun installModule() {
        val manifest = check.value?.manifest ?: return
        viewModelScope.launch { service.installModule(manifest) }
    }

    /**
     * The combined rollback removes this app's own update and reboots the phone, so it asks first;
     * an app-only rollback is a single reinstall and runs right away. A rollback a previous attempt
     * left pending is never asked for again: retrying finishes what the journal recorded.
     */
    fun rollback() {
        if (state.value.rollbackRecovery is RollbackRecovery.Pending) {
            appRollback.value = false
            viewModelScope.launch { service.rollbackApp() }
            return
        }
        val plan = state.value.rollbackPlan
        if (plan is RollbackPlan.WithPreviousModule) {
            confirmation.value = plan
            return
        }
        appRollback.value = plan is RollbackPlan.AppOnly
        viewModelScope.launch { service.rollbackApp() }
    }

    fun confirmRollback() {
        confirmation.value = null
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
