package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootCapabilities
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.VerifyReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a metamodule install is: the archive is downloaded first and installed second, and the two
 * steps are named separately so a minute of silence reads as progress instead of a hang.
 */
sealed interface SetupInstallPhase {
    /** [bytes] received so far; [total] is null when the server sent no length. */
    data class Downloading(val bytes: Long, val total: Long?) : SetupInstallPhase

    data object Installing : SetupInstallPhase
}

private val idleInstallPhase = MutableStateFlow<SetupInstallPhase?>(null)

/**
 * The device operations the wizard performs, as one seam. Production is [AndroidRootBackend], which
 * composes the `:core:root` shell, detector, providers, zip builder, verifier and rebooter; tests
 * substitute a fake so every effect and its order is asserted without a device.
 */
interface RootBackend {
    suspend fun detect(): RootDetection

    suspend fun capabilities(provider: DetectedProvider): RootCapabilities

    suspend fun moduleState(provider: DetectedProvider): ModuleState

    suspend fun verify(module: ModuleState): VerifyReport

    suspend fun install(provider: DetectedProvider, operation: InstallOperation): ProviderResult

    suspend fun reboot(provider: DetectedProvider, reason: RebootReason): ProviderResult

    /** The metamodule download/install step, or null when nothing is being prepared. */
    val installPhase: StateFlow<SetupInstallPhase?>
        get() = idleInstallPhase
}

/** Whether the runtime permissions the wizard asks for are still missing. */
fun interface RuntimePermissionChecker {
    fun missing(): List<com.phonxiety.bridge.root.RuntimePermission>
}
