package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.BridgeService
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.DevicePairingRepository
import com.phonxiety.bridge.designsystem.UiText
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
import kotlinx.coroutines.withContext

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
    private val backend: RootBackend,
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

    /**
     * A stored pairing the repository can read. This is the proof the paste sheet closes on: the
     * wizard's own "Checking" is only a probe and says nothing about whether a link was accepted.
     */
    val pairingConfirmed: StateFlow<Boolean> = pairingRepository.paired

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

    /** The metamodule download/install step; null while nothing is being prepared. */
    val installPhase: StateFlow<SetupInstallPhase?> = backend.installPhase

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
            // the coordinator drops the observation if the wizard already moved on or a local attempt
            // is still running.
            pairingRepository.paired.collect { paired ->
                if (!paired) return@collect
                pairingRepository.refresh()
                coordinator.onPairingObserved()
            }
        }
    }

    /** The one button: install, retry, reboot, recheck — whatever the current state needs. */
    fun onPrimary() {
        viewModelScope.launch(Dispatchers.IO) {
            when (coordinator.wizard.value) {
                is WizardState.ReadyToInstall -> coordinator.dispatch(WizardEvent.InstallRequested)

                is WizardState.InstallFailed -> coordinator.dispatch(WizardEvent.RetryRequested)

                is WizardState.RebootPending -> coordinator.requestReboot()

                is WizardState.ProviderPrep -> coordinator.dispatch(WizardEvent.PrepareProviderRequested)

                is WizardState.NeedsRuntime -> coordinator.requestRuntimePermissions()

                is WizardState.NeedsPairing, WizardState.Checking, is WizardState.Installing,
                is WizardState.FirstLinkTest, WizardState.Ready -> Unit

                // Never "Unpaired" (the line above), so this tap does not answer a resume.
                else -> recheck(unpairedAtRequest = false)
            }
        }
    }

    /** The secondary action, when a state offers one beyond "later". */
    fun onSecondary() {
        when (coordinator.wizard.value) {
            is WizardState.NeedsRuntime -> coordinator.requestBatteryExemption()

            // This action is not offered at "Unpaired" (see the states above), so it is not a
            // resume of an unanswered pairing.
            is WizardState.ProviderPrep -> launchRecheck(unpairedAtRequest = false)

            else -> Unit
        }
    }

    fun onRebootLater() {
        _requests.tryEmit(
            SetupHostRequest.Message(UiText.Res(R.string.onboarding_reboot_later_message))
        )
    }

    /**
     * Root grants happen in the provider's own UI and a pairing can arrive while the app is in the
     * background (`mise run phone:pair` writes the config and restarts the service); coming back is
     * exactly when a re-read matters. `NeedsRuntime` is deliberately not re-probed: its dialog is
     * system-owned, and re-asking on every resume would nag a user who denied a permission.
     */
    fun onReturnedToForeground() {
        val state = coordinator.wizard.value
        when (state) {
            is WizardState.RootDenied, is WizardState.ProviderPrep, is WizardState.Indeterminate,
            is WizardState.Conflict, is WizardState.NeedsPairing ->
                launchRecheck(unpairedAtRequest = state is WizardState.NeedsPairing)

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

    /**
     * The stored pairing can move while the app runs and the repository serves a cache, so the
     * probe after a resume re-reads it first — on the IO dispatcher, never on the caller's thread.
     * [unpairedAtRequest] is the admission read by the caller, in the same read that chose to
     * re-probe, and it travels with the request: an observation can advance the wizard before this
     * coroutine starts, and probing again then would repeat the first link test or emit a second
     * [SetupHostRequest.Finished].
     */
    private fun launchRecheck(unpairedAtRequest: Boolean) {
        viewModelScope.launch { recheck(unpairedAtRequest) }
    }

    private suspend fun recheck(unpairedAtRequest: Boolean) = withContext(Dispatchers.IO) {
        pairingRepository.refresh()
        coordinator.probeOnResume(unpairedAtRequest)
    }
}
