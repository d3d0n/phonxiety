package com.phonxiety.bridge.data.updates

import android.content.Context
import com.phonxiety.bridge.root.AndroidPackageInventory
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.PowerManagerRebooter
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootDetector
import com.phonxiety.bridge.root.RootProvider
import com.phonxiety.bridge.root.RootProviders
import com.phonxiety.bridge.root.RootShell
import com.phonxiety.bridge.root.ShellRootEnvironment
import com.phonxiety.bridge.root.SuStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The module installs the update flow needs: an app-side zip, or one published under `/data/adb`. */
interface RootModuleInstaller {
    suspend fun installModule(zip: File): ProviderInstall

    /**
     * Installs a zip already published under root-owned storage. `/data/adb` is root-only, so this
     * goes through `su` for both the existence check and the copy; the app never stats that path.
     */
    suspend fun installPublishedModule(sourcePath: String): ProviderInstall
}

/** The installed module the rollback card reads, through the detected provider. */
fun interface RootModuleState {
    suspend fun moduleState(): ModuleState?
}

/**
 * The root side of the update flow, through the public `:core:root` API only: detect the provider,
 * read the module state, install a zip and ask for the reboot.
 *
 * Detection runs per call because the provider's policy can change while the app is open; one
 * update action costs one `su -c id` plus the provider's own probes.
 */
@Singleton
class AndroidRootAccess @Inject constructor(
    @ApplicationContext private val context: Context
) : RootModuleInstaller,
    RootModuleState {
    private val appContext = context.applicationContext

    override suspend fun moduleState(): ModuleState? = when (val found = detect()) {
        is Detection.Refused -> null
        is Detection.Ready -> found.provider.moduleState(MODULE_ID)
    }

    override suspend fun installModule(zip: File): ProviderInstall = when (val found = detect()) {
        is Detection.Refused -> found.failure
        is Detection.Ready -> found.provider.installModule(zip).asInstall()
    }

    override suspend fun installPublishedModule(sourcePath: String): ProviderInstall = when (val found = detect()) {
        is Detection.Refused -> found.failure
        is Detection.Ready -> found.provider.installPublishedModule(sourcePath).asInstall()
    }

    suspend fun rebootForUpdate(): ProviderInstall {
        val shell = RootShell()
        val environment = ShellRootEnvironment(shell, AndroidPackageInventory(appContext))
        val detection = RootDetector(shell, environment).detect()
        val detected = detection.provider
            ?: return ProviderInstall.Failed(ModuleInstallFailure.ROOT_UNAVAILABLE, "no root provider answered")
        return when (val result = provider(detected, shell, environment).reboot(RebootReason.MODULE_UPDATE)) {
            is ProviderResult.Success -> ProviderInstall.Installed
            else -> ProviderInstall.Failed(ModuleInstallFailure.REBOOT_FAILED, result.tail().joinToString("\n"))
        }
    }

    private suspend fun detect(): Detection {
        val shell = RootShell()
        val environment = ShellRootEnvironment(shell, AndroidPackageInventory(appContext))
        val detection = RootDetector(shell, environment).detect()
        if (detection.conflict) {
            return Detection.Refused(
                ProviderInstall.Failed(
                    ModuleInstallFailure.PROVIDER_CONFLICT,
                    detection.providers.joinToString { it.id.name }
                )
            )
        }
        val detected = detection.provider
            ?: return Detection.Refused(ProviderInstall.Failed(ModuleInstallFailure.ROOT_UNAVAILABLE, "no root provider answered"))
        if (detection.su != SuStatus.GRANTED) {
            return Detection.Refused(ProviderInstall.Failed(ModuleInstallFailure.ROOT_UNAVAILABLE, detection.suOutput))
        }
        return Detection.Ready(provider(detected, shell, environment))
    }

    private fun ProviderResult.asInstall(): ProviderInstall = when (this) {
        is ProviderResult.Success -> ProviderInstall.Installed
        else -> ProviderInstall.Failed(ModuleInstallFailure.INSTALL_FAILED, tail().joinToString("\n"))
    }

    private fun provider(
        detected: com.phonxiety.bridge.root.DetectedProvider,
        shell: RootShell,
        environment: ShellRootEnvironment
    ): RootProvider = RootProviders.create(
        detected = detected,
        shell = shell,
        environment = environment,
        rebooter = PowerManagerRebooter(appContext)
    )

    private sealed interface Detection {
        data class Ready(val provider: RootProvider) : Detection

        data class Refused(val failure: ProviderInstall.Failed) : Detection
    }

    private companion object {
        val MODULE_ID: String = ModuleController.MODULE_ID
    }
}
