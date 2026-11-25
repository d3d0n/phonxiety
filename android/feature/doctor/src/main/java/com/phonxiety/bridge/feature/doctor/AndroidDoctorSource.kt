package com.phonxiety.bridge.feature.doctor

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import com.phonxiety.bridge.BridgeService
import com.phonxiety.bridge.CrashReports
import com.phonxiety.bridge.root.AndroidAppFactsProbe
import com.phonxiety.bridge.root.AndroidPackageInventory
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.PowerManagerRebooter
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootDetector
import com.phonxiety.bridge.root.RootProvider
import com.phonxiety.bridge.root.RootProviders
import com.phonxiety.bridge.root.RootShell
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.ShellRootEnvironment
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.moduleZipBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The production [DoctorSource]: the `:core:root` shell, detector and providers, the package
 * manager's facts, the runtime grants, notifications and the battery exemption. Every read is on
 * the caller's IO dispatcher; nothing here touches the main thread.
 */
class AndroidDoctorSource(context: Context) : DoctorSource {
    private val appContext = context.applicationContext
    private val shell = RootShell()
    private val environment = ShellRootEnvironment(shell, AndroidPackageInventory(appContext))
    private val detector = RootDetector(shell, environment)
    private val factProbe = AndroidAppFactsProbe(appContext)
    private val rebooter = PowerManagerRebooter(appContext)

    @Volatile
    private var provider: RootProvider? = null

    override suspend fun read(): DoctorSnapshot = withContext(Dispatchers.IO) {
        val detection = try {
            detector.detect()
        } catch (error: Throwable) {
            return@withContext DoctorSnapshot(
                root = RootDetection(su = SuStatus.NOT_ROOTED),
                checkedAtEpochMs = System.currentTimeMillis(),
                readError = error.message ?: "the root probe threw"
            )
        }
        val current = detection.provider
            ?.takeIf { detection.su == SuStatus.GRANTED }
            ?.let { detected ->
                RootProviders.create(detected = detected, shell = shell, environment = environment, rebooter = rebooter)
            }
        provider = current

        val module = current?.let { rootProvider ->
            runCatching { rootProvider.moduleState(ModuleController.MODULE_ID) }.getOrNull()
                ?: ModuleState.Unknown
        }
        val overlay = current?.let { rootProvider ->
            runCatching { rootProvider.capabilities().systemOverlay }.getOrNull()
        }
        DoctorSnapshot(
            root = detection,
            module = module,
            overlay = overlay,
            appFacts = runCatching { factProbe.appFacts() }.getOrNull(),
            runtimeMissing = missingRuntimePermissions(),
            notificationsEnabled = notificationsEnabled(),
            batteryExempt = batteryExempt(),
            // The recorder writes before the process dies, so this read is the same answer the
            // diagnostics bundle gets.
            lastCrash = try {
                CrashReports.latest(appContext)
            } catch (_: Exception) {
                null
            },
            checkedAtEpochMs = System.currentTimeMillis()
        )
    }

    override suspend fun enableModule(): ProviderResult = withContext(Dispatchers.IO) {
        currentProvider { rootProvider -> rootProvider.enableModule(ModuleController.MODULE_ID) }
    }

    override suspend fun reinstallModule(): ProviderResult = withContext(Dispatchers.IO) {
        if (provider == null) {
            return@withContext ProviderResult.Denied("no root provider is available")
        }
        runCatching {
            val zip = moduleZipBuilder(
                context = appContext,
                outputFile = File(appContext.cacheDir, "phonxiety_bridge.zip")
            ).build()
            currentProvider { rootProvider -> rootProvider.installModule(zip) }
        }.getOrElse { error ->
            ProviderResult.Failure(error.message ?: "the module zip could not be built", exitCode = 1)
        }
    }

    override suspend fun reboot(): ProviderResult = withContext(Dispatchers.IO) {
        currentProvider { rootProvider -> rootProvider.reboot(RebootReason.MODULE_INSTALL) }
    }

    private suspend fun currentProvider(block: suspend (RootProvider) -> ProviderResult): ProviderResult {
        val current = provider ?: return ProviderResult.Denied("no root provider is available")
        return runCatching { block(current) }
            .getOrElse { error -> ProviderResult.Failure(error.message ?: "the provider command threw", exitCode = 1) }
    }

    private fun missingRuntimePermissions(): Set<RuntimePermission> = RuntimePermission.entries
        .filter { permission ->
            appContext.checkSelfPermission(permission.androidName) != PackageManager.PERMISSION_GRANTED
        }
        .toSet()

    private fun notificationsEnabled(): Boolean = runCatching {
        appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }.getOrDefault(true)

    private fun batteryExempt(): Boolean? = runCatching {
        appContext.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName)
    }.getOrNull()
}

/** Stops and starts the bridge service; the service reopens the link from the stored pairing. */
class AndroidDoctorServiceControl(context: Context) : DoctorServiceControl {
    private val appContext = context.applicationContext

    override fun restart() {
        BridgeService.stop(appContext)
        BridgeService.start(appContext)
    }
}
