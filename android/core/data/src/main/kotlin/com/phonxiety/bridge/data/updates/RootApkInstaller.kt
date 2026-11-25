package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.root.RootCommand
import com.phonxiety.bridge.root.RootShell
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reinstalls the kept previous APK as root. A rollback is always a downgrade, and only the
 * `pm install -r -d` command can put a lower version code back; the APK is staged in
 * `/data/local/tmp` first so `pm` never reads the app's private directory.
 */
@Singleton
class RootApkInstaller @Inject constructor() : PrivilegedApkInstaller {
    override suspend fun installWithRoot(apk: File): ApkInstallOutcome {
        val shell = RootShell()
        val staged = shell.exec(RootCommand.copy(apk.absolutePath, STAGED_NAME))
        if (!staged.ok) {
            return ApkInstallOutcome.Failed(
                InstallFailure.ROOT_UNAVAILABLE,
                staged.combined.ifEmpty { "su could not stage the previous APK" }
            )
        }
        val install = shell.exec(RootCommand.pmInstall("${RootCommand.STAGING_DIR}/$STAGED_NAME"))
        return if (install.ok) {
            ApkInstallOutcome.Installed(null)
        } else {
            ApkInstallOutcome.Failed(
                InstallFailure.ROLLBACK_FAILED,
                install.combined.ifEmpty { "pm install exited ${install.exitCode}" }
            )
        }
    }

    private companion object {
        const val STAGED_NAME: String = "phonxiety-previous.apk"
    }
}
