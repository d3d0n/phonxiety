package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.BridgeService
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.DevicePairingRepository
import com.phonxiety.bridge.root.FileProgressStore
import com.phonxiety.bridge.root.ProgressStore
import com.phonxiety.bridge.root.WizardEvent
import com.phonxiety.bridge.root.WizardState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the wizard keeps the install journal; the boot receiver reads the same file. */
fun setupProgressStore(context: Context): ProgressStore = FileProgressStore(File(context.filesDir, PROGRESS_FILE))

private const val PROGRESS_FILE = "module-install.properties"

/**
 * The wizard's state holder. Every probe starts from a fresh read of the stored pairing — the file
 * can change while the app runs (`mise run phone:pair`, the service rewriting it on start) and the
 * repository serves a cache, so the read happens here, on the IO dispatcher, before the probe.
 */
@HiltViewModel
class OnboardingViewModel internal constructor(
    context: Context,
    private val pairingRepository: DevicePairingRepository,
    appApi: AppApi,
    backend: RootBackend,
    permissions: RuntimePermissionChecker = AndroidPermissionChecker(context)
) : ViewModel() {
    /** The wired wizard: the app's root backend, its permissions and the process-wide service. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        pairingRepository: DevicePairingRepository,
        appApi: AppApi
    ) : this(context, pairingRepository, appApi, AndroidRootBackend(context))

    private val preferences = SetupPreferences(context)

    private val _requests = MutableSharedFlow<SetupHostRequest>(extraBufferCapacity = 32)
    val requests: SharedFlow<SetupHostRequest> = _requests.asSharedFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val coordinator = SetupCoordinator(
        backend = backend,
        pairing = pairingRepository,
        appApi = appApi,
        progressStore = setupProgressStore(context),
        preferences = preferences,
        permissions = permissions,
        serviceStarter = { BridgeService.start(context) },
        host = { request ->
            if (request is SetupHostRequest.PairingRejected) {
                _pairingError.value = request.reason
            }
            _requests.tryEmit(request)
        }
    )

    val wizard: StateFlow<WizardState> = coordinator.wizard

    init {
        viewModelScope.launch(Dispatchers.IO) {
            pairingRepository.refresh()
            coordinator.start()
        }
    }

    /** The one button: install, retry, reboot, recheck — whatever the current state needs. */
    fun onPrimary() {
        viewModelScope.launch(Dispatchers.IO) {
            when (coordinator.wizard.value) {
                is WizardState.ReadyToInstall -> coordinator.dispatch(WizardEvent.InstallRequested)

                is WizardState.InstallFailed -> coordinator.dispatch(WizardEvent.RetryRequested)

                is WizardState.RebootPending -> coordinator.dispatch(WizardEvent.RebootRequested)

                is WizardState.ProviderPrep -> coordinator.dispatch(WizardEvent.PrepareProviderRequested)

                is WizardState.NeedsRuntime -> coordinator.requestRuntimePermissions()

                is WizardState.NeedsPairing, WizardState.Checking, is WizardState.Installing,
                is WizardState.FirstLinkTest, WizardState.Ready -> Unit

                else -> recheck()
            }
        }
    }

    /** The secondary action, when a state offers one beyond "later". */
    fun onSecondary() {
        when (coordinator.wizard.value) {
            is WizardState.NeedsRuntime -> coordinator.requestBatteryExemption()
            is WizardState.ProviderPrep -> launchRecheck()
            else -> Unit
        }
    }

    fun onRebootLater() {
        _requests.tryEmit(
            SetupHostRequest.Message(
                "The install is on disk. Reboot whenever you are ready; the wizard resumes here."
            )
        )
    }

    /**
     * Root grants happen in the provider's own UI and a pairing can arrive while the app is in the
     * background (`mise run phone:pair` writes the config and restarts the service); coming back is
     * exactly when a re-read matters. `NeedsRuntime` is deliberately not re-probed: its dialog is
     * system-owned, and re-asking on every resume would nag a user who denied a permission.
     */
    fun onReturnedToForeground() {
        when (coordinator.wizard.value) {
            is WizardState.RootDenied, is WizardState.ProviderPrep, is WizardState.Indeterminate,
            is WizardState.Conflict, is WizardState.NeedsPairing -> launchRecheck()

            else -> Unit
        }
    }

    fun onPermissionsResult() {
        viewModelScope.launch(Dispatchers.IO) { coordinator.onPermissionsChanged() }
    }

    fun requestBatteryExemption() {
        coordinator.requestBatteryExemption()
    }

    fun pair(link: String) {
        _pairingError.value = null
        viewModelScope.launch(Dispatchers.IO) { coordinator.pair(link) }
    }

    fun dismissPairingError() {
        _pairingError.value = null
    }

    fun finish() {
        _requests.tryEmit(SetupHostRequest.Finished)
    }

    private fun launchRecheck() {
        viewModelScope.launch(Dispatchers.IO) { recheck() }
    }

    /**
     * The stored pairing can move while the app runs and the repository serves a cache, so the
     * probe after a resume re-reads it first — on this dispatcher, never on the caller's thread.
     */
    private suspend fun recheck() {
        pairingRepository.refresh()
        coordinator.dispatch(WizardEvent.RecheckRequested)
    }
}
