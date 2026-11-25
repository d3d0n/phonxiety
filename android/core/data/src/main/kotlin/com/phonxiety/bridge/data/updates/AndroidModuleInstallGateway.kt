package com.phonxiety.bridge.data.updates

import android.content.Context
import com.phonxiety.bridge.root.moduleZipBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the module zip for exactly this installation and installs it through the detected root
 * provider. The APK inside comes from `ApplicationInfo.sourceDir`, so the module's priv-app copy and
 * the running app are the same build; the allowlist, scripts and WebUI come from the assets that are
 * copied from `android/magisk/` at build time.
 */
@Singleton
class AndroidModuleInstallGateway @Inject constructor(
    @ApplicationContext context: Context,
    private val rootAccess: AndroidRootAccess
) : ModuleInstallGateway {
    private val appContext = context.applicationContext

    override suspend fun buildZip(updateJsonUrl: String?): File {
        val output = File(appContext.cacheDir, ZIP_NAME)
        if (output.isFile && !output.delete()) {
            throw IllegalStateException("could not replace ${output.path}")
        }
        return moduleZipBuilder(appContext, output, updateJson = updateJsonUrl).build()
    }

    override suspend fun install(zip: File): ProviderInstall = rootAccess.installModule(zip)

    override suspend fun reboot(): ProviderInstall = rootAccess.rebootForUpdate()

    private companion object {
        const val ZIP_NAME: String = "phonxiety_bridge_update.zip"
    }
}
