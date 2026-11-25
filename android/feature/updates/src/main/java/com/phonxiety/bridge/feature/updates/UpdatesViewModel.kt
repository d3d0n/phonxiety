package com.phonxiety.bridge.feature.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.ModuleInstallState
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

/** Everything the Updates screen renders: one check, the two actions, and the rollback offer. */
data class UpdatesUiState(
    val loading: Boolean = false,
    val check: UpdateCheck? = null,
    val appInstall: AppInstallState = AppInstallState.Idle,
    val moduleInstall: ModuleInstallState = ModuleInstallState.Idle,
    val rollbackAvailable: Boolean = false
)

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val service: UpdatesService
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val check = MutableStateFlow<UpdateCheck?>(null)

    val state: StateFlow<UpdatesUiState> = combine(
        check,
        checking,
        service.appInstall,
        service.moduleInstall,
        service.rollbackAvailable
    ) { current, loading, app, module, rollback ->
        UpdatesUiState(
            loading = loading,
            check = current,
            appInstall = app,
            moduleInstall = module,
            rollbackAvailable = rollback
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

    fun rollback() {
        viewModelScope.launch { service.rollbackApp() }
    }

    fun dismiss() {
        service.dismiss()
        refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
