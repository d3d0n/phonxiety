package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.BridgeService
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.DevicePairingRepository
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.root.FileProgressStore
import com.phonxiety.bridge.root.ProgressStore
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.WizardEvent
import com.phonxiety.bridge.root.WizardState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    permissions: RuntimePermissionChecker = AndroidPermissionChecker(context),
    linkState: Flow<LinkState> = BridgeRuntime.state.map { it.link }
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
        },
        linkState = linkState
    )

    val wizard: StateFlow<WizardState> = coordinator.wizard

    /** The last detection: the wizard shows the leftovers of a provider the phone migrated away from. */
    val detection: StateFlow<RootDetection?> = coordinator.rootDetection

    init {
        viewModelScope.launch(Dispatchers.IO) {
            pairingRepository.refresh()
            coordinator.start()
        }
        viewModelScope.launch(Dispatchers.IO) {
            // A pairing can appear without this screen's scanner or paste field: `mise run phone:pair`,
            // the service rewriting its config, or the repository noticing a link change. Reading the
            // cache afresh and dispatching the resume probe is what makes the wizard advance by itself;
            // the coordinator drops the observation if the wizard already moved on, a local attempt is
            // still running, or the config is the one a rejected attempt left behind. A cleared
            // pairing ends that suppression, so the next one counts.
            pairingRepository.paired.collect { paired ->
                if (paired) {
                    pairingRepository.refresh()
                    coordinator.onPairingObserved()
                } else {
                    coordinator.onPairingCleared()
                }
            }
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
     * The state is read before that refresh: a refresh can publish a stored pairing, and the
     * observation it wakes may admit its probe first, in which case this one must stay quiet.
     */
    private suspend fun recheck() {
        val unpairedAtRequest = coordinator.wizard.value is WizardState.NeedsPairing
        pairingRepository.refresh()
        coordinator.probeOnResume(unpairedAtRequest)
    }
}
