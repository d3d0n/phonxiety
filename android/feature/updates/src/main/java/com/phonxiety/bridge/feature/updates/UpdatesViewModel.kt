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
    val moduleRollback: RollbackState = RollbackState.Idle
)

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val service: UpdatesService
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val check = MutableStateFlow<UpdateCheck?>(null)
    private val confirmation = MutableStateFlow<RollbackPlan.WithPreviousModule?>(null)

    /** The service's flows paired once, so the state combine below stays within typed overloads. */
    private data class ServiceState(
        val appInstall: AppInstallState,
        val moduleInstall: ModuleInstallState,
        val rollbackPlan: RollbackPlan?,
        val rollbackRecovery: RollbackRecovery?,
        val moduleRollback: RollbackState
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

    val state: StateFlow<UpdatesUiState> = combine(
        services,
        check,
        checking,
        confirmation
    ) { current, checked, loading, confirming ->
        UpdatesUiState(
            loading = loading,
            check = checked,
            appInstall = current.appInstall,
            moduleInstall = current.moduleInstall,
            rollbackPlan = current.rollbackPlan,
            rollbackRecovery = current.rollbackRecovery,
            rollbackConfirmation = confirming?.takeIf { current.rollbackPlan is RollbackPlan.WithPreviousModule },
            moduleRollback = current.moduleRollback
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
        viewModelScope.launch {
            checking.value = true
            check.value = service.check()
            checking.value = false
        }
    }

    fun installApp() {
        val manifest = check.value?.manifest ?: return
        viewModelScope.launch { service.installApp(manifest) }
    }

    fun installModule() {
        val manifest = check.value?.manifest ?: return
        viewModelScope.launch { service.installModule(manifest) }
    }

    /**
     * The combined rollback removes this app's own update and reboots the phone, so it asks first;
     * an app-only rollback is a single reinstall and runs right away.
     */
    fun rollback() {
        val plan = state.value.rollbackPlan
        if (plan is RollbackPlan.WithPreviousModule) {
            confirmation.value = plan
            return
        }
        viewModelScope.launch { service.rollbackApp() }
    }

    fun confirmRollback() {
        confirmation.value = null
        viewModelScope.launch { service.rollbackApp() }
    }

    fun dismissRollbackConfirmation() {
        confirmation.value = null
    }

    fun dismiss() {
        confirmation.value = null
        service.dismiss()
        refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
