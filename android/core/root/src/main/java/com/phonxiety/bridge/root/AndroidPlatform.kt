package com.phonxiety.bridge.root

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.PowerManager

// Android-side adapters for the abstractions in this module. They stay small on purpose: the
// shell, the providers and the wizard are pure Kotlin and are unit-tested without a device.

/**
 * The installed root-manager packages. Android 11+ package visibility hides most apps from bulk
 * queries, so each known manager is queried by name; the library manifest declares them in
 * `<queries>`.
 */
class AndroidPackageInventory(
    context: Context,
    private val packageNames: Set<String> = ROOT_MANAGER_PACKAGES
) : PackageInventory {
    private val packageManager = context.applicationContext.packageManager

    override fun installed(): Set<String> = packageNames.filterTo(mutableSetOf()) { name ->
        runCatching {
            packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
        }.isSuccess
    }

    companion object {
        val ROOT_MANAGER_PACKAGES: Set<String> = setOf(
            MagiskProvider.MANAGER_PACKAGE,
            KernelSuProvider.KERNELSU_MANAGER_PACKAGE,
            KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE,
            ApatchProvider.MANAGER_PACKAGE
        )
    }
}

/** `PackageManager` facts for the Verify state: system flag, APK path and granted permissions. */
class AndroidAppFactsProbe(
    context: Context,
    private val packageName: String = context.packageName
) : AppFactsProbe {
    private val appContext = context.applicationContext

    override suspend fun appFacts(): AppFacts? {
        val packageManager = appContext.packageManager
        val info = runCatching {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
        }.getOrNull() ?: return null
        val granted = PrivilegedPermissions.ALL.filterTo(mutableSetOf()) { permission ->
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        return AppFacts(
            packageName = packageName,
            isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            apkPath = info.sourceDir,
            grantedPermissions = granted
        )
    }
}

/**
 * `PowerManager.reboot()` when `android.permission.REBOOT` is held (the app is a priv-app installed
 * by the module); [RootProvider] falls back to `su -c reboot` when it is not.
 */
class PowerManagerRebooter(context: Context) : SystemRebooter {
    private val appContext = context.applicationContext

    override fun hasRebootPermission(): Boolean = appContext.checkSelfPermission(Manifest.permission.REBOOT) ==
        PackageManager.PERMISSION_GRANTED

    override fun reboot(reason: String): Boolean = runCatching {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return false
        powerManager.reboot(reason)
        true
    }.getOrDefault(false)
}

/** Payload files shipped in the app's assets by `android/core/root/build.gradle.kts`. */
class AndroidAssetSource(context: Context, private val assetBase: String = "") : AssetSource {
    private val assets = context.applicationContext.assets

    override fun open(path: String): java.io.InputStream? = runCatching { assets.open(assetBase + path) }.getOrNull()
}

/**
 * Builds the module zip for this very installation: the APK comes from `ApplicationInfo.sourceDir`,
 * the version from `PackageManager`, and the scripts, icon, WebUI and allowlist from the assets.
 */
fun moduleZipBuilder(
    context: Context,
    outputFile: java.io.File,
    updateJson: String? = null
): ModuleZipBuilder {
    val app = context.applicationContext
    val info = app.packageManager.getPackageInfo(
        app.packageName,
        PackageManager.PackageInfoFlags.of(0)
    )
    val versionCode = info.longVersionCode
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return ModuleZipBuilder(
        assets = AndroidAssetSource(app),
        apkFile = java.io.File(app.applicationInfo.sourceDir),
        appVersion = info.versionName ?: "0.0.0",
        appVersionCode = versionCode,
        outputFile = outputFile,
        updateJson = updateJson
    )
}
