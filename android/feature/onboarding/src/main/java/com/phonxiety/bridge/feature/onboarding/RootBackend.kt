package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootCapabilities
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.VerifyReport

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
}

/** Whether the runtime permissions the wizard asks for are still missing. */
fun interface RuntimePermissionChecker {
    fun missing(): List<com.phonxiety.bridge.root.RuntimePermission>
}
