package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
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
import com.phonxiety.bridge.root.RootEnvironment
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
    context: Context,
    private val shell: RootShell = RootShell(),
    private val environment: RootEnvironment = ShellRootEnvironment(shell, AndroidPackageInventory(context)),
    private val zipBuilder: suspend () -> File = {
        moduleZipBuilder(
            context = context,
            outputFile = File(context.cacheDir, "phonxiety_bridge.zip")
        ).build()
    },
    private val metamoduleSource: MetamoduleSource = HttpMetamoduleSource(File(context.cacheDir, "metamodules"))
) : RootBackend {
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

            // The metamodule mounts on the next boot; Phonxiety is installed after that boot, because
            // ksud refuses a module install while a metamodule is still staged.
            InstallOperation.PREPARE_METAMODULE -> prepareMetamodule(rootProvider)
        }
    }

    override suspend fun reboot(provider: DetectedProvider, reason: RebootReason): ProviderResult = provider(provider).reboot(reason)

    /** Exactly one module: the pinned metamodule. The app zip is not built and not installed here. */
    private suspend fun prepareMetamodule(rootProvider: RootProvider): ProviderResult {
        val metamodule = when (val result = metamoduleSource.zip()) {
            is MetamoduleResult.Ready -> result.file
            is MetamoduleResult.Failed -> return ProviderResult.Failure(result.reason, exitCode = METAMODULE_EXIT)
        }
        val meta = rootProvider.installModule(metamodule)
        return if (meta.isSuccess) {
            ProviderResult.Success("meta-overlayfs: ${meta.outputText()}")
        } else {
            ProviderResult.Failure(
                output = "meta-overlayfs: ${meta.outputText()}",
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

/**
 * Runtime permissions from the platform, in the wizard's fixed order.
 *
 * The set is filtered twice, because `checkSelfPermission` alone answers "not granted" for
 * permissions a dialog cannot grant: [RuntimePermission.ACCESS_LOCAL_NETWORK] exists as a runtime
 * permission only from Android 17 (before that the platform does not know the name, and the app's
 * local-network access is implicit), and a permission that is declared but not dangerous is granted
 * at install. Asking for either would park the wizard on `NeedsRuntime` forever.
 */
class AndroidPermissionChecker(context: Context) : RuntimePermissionChecker {
    private val appContext = context.applicationContext

    override fun missing(): List<RuntimePermission> = permissionsToRequest(
        needed = RuntimePermission.entries,
        declared = declaredPermissions(),
        runtime = runtimePermissions(),
        granted = RuntimePermission.entries
            .filter { appContext.checkSelfPermission(it.androidName) == PackageManager.PERMISSION_GRANTED }
            .map { it.androidName }
            .toSet()
    )

    private fun declaredPermissions(): Set<String> = runCatching {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    private fun runtimePermissions(): Set<String> = RuntimePermission.entries
        .map { it.androidName }
        .filterTo(mutableSetOf()) { name ->
            val info = runCatching {
                appContext.packageManager.getPermissionInfo(name, 0)
            }.getOrNull() ?: return@filterTo false
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
        }
}

/**
 * The wizard's permission set: everything the engine needs that this build declares and treats as a
 * runtime permission, minus what is already granted. A dialog cannot grant an undeclared or
 * non-runtime permission, so those never hold `NeedsRuntime`.
 */
internal fun permissionsToRequest(
    needed: List<RuntimePermission>,
    declared: Set<String>,
    runtime: Set<String>,
    granted: Set<String>
): List<RuntimePermission> = needed.filter { permission ->
    val name = permission.androidName
    name in declared && name in runtime && name !in granted
}
