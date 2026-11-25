package com.phonxiety.bridge.data.updates

import java.io.File

/** Where the Module row is: rebuild the zip, install through the provider, reboot. */
sealed interface ModuleInstallState {
    data object Idle : ModuleInstallState

    data object Building : ModuleInstallState

    data object Installing : ModuleInstallState

    /** Installed and waiting for the boot that mounts the overlay; [detail] explains a refused reboot. */
    data class RebootPending(val detail: String? = null) : ModuleInstallState

    data class Failed(val code: ModuleInstallFailure, val detail: String? = null) : ModuleInstallState

    val busy: Boolean
        get() = this is Building || this is Installing
}

enum class ModuleInstallFailure {
    BUILD_FAILED,
    ROOT_UNAVAILABLE,
    PROVIDER_CONFLICT,
    INSTALL_FAILED,
    REBOOT_FAILED
}

sealed interface ProviderInstall {
    data object Installed : ProviderInstall

    data class Failed(val code: ModuleInstallFailure, val detail: String? = null) : ProviderInstall
}

/**
 * Builds the module zip for this very installation (`ModuleZipBuilder` takes the APK from
 * `ApplicationInfo.sourceDir`, so the module's priv-app copy is exactly the running app) and hands
 * it to the detected root provider. Faked in `ModuleUpdateCoordinatorTest`.
 */
interface ModuleInstallGateway {
    suspend fun buildZip(updateJsonUrl: String?): File

    suspend fun install(zip: File): ProviderInstall

    suspend fun reboot(): ProviderInstall
}

/**
 * The Module row's state machine. A module update always ends in a reboot: the overlay carries a
 * new APK and possibly new privileged grants, and nothing applies it without a boot.
 */
class ModuleUpdateCoordinator(
    private val gateway: ModuleInstallGateway,
    private val onState: (ModuleInstallState) -> Unit
) {
    suspend fun install(manifest: UpdateManifest): ModuleInstallState {
        onState(ModuleInstallState.Building)
        val zip = try {
            gateway.buildZip(manifest.updateJsonUrl)
        } catch (error: Exception) {
            return fail(ModuleInstallState.Failed(ModuleInstallFailure.BUILD_FAILED, error.message))
        }
        onState(ModuleInstallState.Installing)
        when (val installed = gateway.install(zip)) {
            is ProviderInstall.Failed -> return fail(ModuleInstallState.Failed(installed.code, installed.detail))
            ProviderInstall.Installed -> Unit
        }
        onState(ModuleInstallState.RebootPending())
        return when (val reboot = gateway.reboot()) {
            ProviderInstall.Installed -> ModuleInstallState.RebootPending()

            is ProviderInstall.Failed -> {
                val state = ModuleInstallState.RebootPending(
                    detail = "The update is installed but the reboot was not accepted: ${reboot.detail ?: "no output"}. " +
                        "Reboot this phone to apply it."
                )
                fail(state)
            }
        }
    }

    private fun fail(state: ModuleInstallState): ModuleInstallState {
        onState(state)
        return state
    }
}
