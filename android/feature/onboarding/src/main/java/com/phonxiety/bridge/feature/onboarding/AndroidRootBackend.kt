package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import android.content.pm.PackageManager
import com.phonxiety.bridge.root.AndroidAppFactsProbe
import com.phonxiety.bridge.root.AndroidPackageInventory
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.PowerManagerRebooter
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootCapabilities
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootDetector
import com.phonxiety.bridge.root.RootProvider
import com.phonxiety.bridge.root.RootProviders
import com.phonxiety.bridge.root.RootShell
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.ShellRootEnvironment
import com.phonxiety.bridge.root.VerifyChecker
import com.phonxiety.bridge.root.VerifyReport
import com.phonxiety.bridge.root.moduleZipBuilder
import java.io.File

/**
 * The production [RootBackend]: one `su`-backed shell, the detector and providers from `:core:root`,
 * and the module zip built from this very installation's APK. Every call is a suspend function on
 * the caller's IO dispatcher; nothing here touches the main thread.
 */
class AndroidRootBackend(
    private val context: Context,
    private val zipBuilder: suspend () -> File = {
        moduleZipBuilder(
            context = context,
            outputFile = File(context.cacheDir, "phonxiety_bridge.zip")
        ).build()
    },
    private val metamoduleSource: MetamoduleSource = HttpMetamoduleSource(File(context.cacheDir, "metamodules"))
) : RootBackend {
    private val shell = RootShell()
    private val environment = ShellRootEnvironment(shell, AndroidPackageInventory(context))
    private val detector = RootDetector(shell, environment)
    private val rebooter = PowerManagerRebooter(context)
    private val verifyChecker = VerifyChecker(AndroidAppFactsProbe(context))

    override suspend fun detect(): RootDetection = detector.detect()

    override suspend fun capabilities(provider: DetectedProvider): RootCapabilities = provider(provider).capabilities()

    override suspend fun moduleState(provider: DetectedProvider): ModuleState = provider(provider).moduleState(ModuleController.MODULE_ID)

    override suspend fun verify(module: ModuleState): VerifyReport = verifyChecker.check(module)

    override suspend fun install(provider: DetectedProvider, operation: InstallOperation): ProviderResult {
        val rootProvider = provider(provider)
        return when (operation) {
            InstallOperation.INSTALL, InstallOperation.UPDATE -> rootProvider.installModule(zipBuilder())

            // There is no provider-level "previous version": rolling back an install means removing
            // the module and letting the next boot restore the stock overlay.
            InstallOperation.ROLLBACK -> rootProvider.removeModule(ModuleController.MODULE_ID)

            // One reboot total: the metamodule is mounted by the next boot, and Phonxiety is staged
            // in the same module store, so both apply together when the phone comes back.
            InstallOperation.PREPARE_METAMODULE -> prepareMetamoduleAndModule(rootProvider)
        }
    }

    override suspend fun reboot(provider: DetectedProvider, reason: RebootReason): ProviderResult = provider(provider).reboot(reason)

    private suspend fun prepareMetamoduleAndModule(rootProvider: RootProvider): ProviderResult {
        val metamodule = when (val result = metamoduleSource.zip()) {
            is MetamoduleResult.Ready -> result.file
            is MetamoduleResult.Failed -> return ProviderResult.Failure(result.reason, exitCode = METAMODULE_EXIT)
        }
        val meta = rootProvider.installModule(metamodule)
        if (!meta.isSuccess) {
            return ProviderResult.Failure(
                output = "meta-overlayfs: ${meta.outputText()}",
                exitCode = METAMODULE_EXIT
            )
        }
        val app = rootProvider.installModule(zipBuilder())
        return if (app.isSuccess) {
            ProviderResult.Success(
                output = "meta-overlayfs: ${meta.outputText()}\nphonxiety: ${app.outputText()}"
            )
        } else {
            ProviderResult.Failure(
                output = "meta-overlayfs: ${meta.outputText()}\nphonxiety: ${app.outputText()}",
                exitCode = METAMODULE_EXIT
            )
        }
    }

    private fun provider(detected: DetectedProvider) = RootProviders.create(
        detected = detected,
        shell = shell,
        environment = environment,
        rebooter = rebooter
    )

    private companion object {
        const val METAMODULE_EXIT = 3
    }
}

/** The provider's captured output, or a placeholder when it printed nothing. */
private fun ProviderResult.outputText(): String = tail(lines = 20).joinToString("\n").ifEmpty { "the provider printed nothing" }

/** Runtime permissions from `Context.checkSelfPermission`, in the wizard's fixed order. */
class AndroidPermissionChecker(context: Context) : RuntimePermissionChecker {
    private val appContext = context.applicationContext

    override fun missing(): List<RuntimePermission> = RuntimePermission.entries.filter { permission ->
        appContext.checkSelfPermission(permission.androidName) != PackageManager.PERMISSION_GRANTED
    }
}
