package com.phonxiety.bridge.root

/** The privileged permissions the module's privapp allowlist grants, and nothing else. */
object PrivilegedPermissions {
    const val CAPTURE_AUDIO_OUTPUT: String = "android.permission.CAPTURE_AUDIO_OUTPUT"
    const val MODIFY_PHONE_STATE: String = "android.permission.MODIFY_PHONE_STATE"
    const val CONTROL_INCALL_EXPERIENCE: String = "android.permission.CONTROL_INCALL_EXPERIENCE"
    const val INSTALL_SELF_UPDATES: String = "android.permission.INSTALL_SELF_UPDATES"
    const val REBOOT: String = "android.permission.REBOOT"

    val ALL: Set<String> = setOf(
        CAPTURE_AUDIO_OUTPUT,
        MODIFY_PHONE_STATE,
        CONTROL_INCALL_EXPERIENCE,
        INSTALL_SELF_UPDATES,
        REBOOT
    )
}

/** What `PackageManager` says about this app; produced by the app layer (Android types stay there). */
data class AppFacts(
    val packageName: String,
    val isSystemApp: Boolean,
    val apkPath: String?,
    val grantedPermissions: Set<String>,
    val requiredPermissions: Set<String> = PrivilegedPermissions.ALL
) {
    /** The permission names still missing from the allowlist grant. */
    val missingPermissions: Set<String> get() = requiredPermissions - grantedPermissions
}

fun interface AppFactsProbe {
    suspend fun appFacts(): AppFacts?
}

/** Every check the Verify state runs after the reboot. */
enum class VerifyCheck {
    MODULE_PRESENT,
    MODULE_ENABLED,
    NO_DISABLE_FLAG,
    NO_REMOVE_FLAG,
    PACKAGE_IS_SYSTEM,
    APK_IN_PRIV_APP,
    PRIVILEGED_PERMISSIONS_GRANTED
}

enum class VerifyOutcome { PASS, FAIL, UNKNOWN }

data class VerifyReport(val checks: Map<VerifyCheck, VerifyOutcome>) {
    val passed: Boolean get() = checks.values.all { it == VerifyOutcome.PASS }

    val failures: List<VerifyCheck>
        get() = VerifyCheck.entries.filter { checks[it] == VerifyOutcome.FAIL }

    val unknown: List<VerifyCheck>
        get() = VerifyCheck.entries.filter { checks[it] == VerifyOutcome.UNKNOWN }

    companion object {
        const val PRIV_APP_PREFIX: String = "/system/priv-app/"
    }
}

/**
 * The post-boot check list: the module directory is present and enabled without a
 * `disable`/`remove` flag, the package is a system app whose APK is under `/system/priv-app`, and
 * every allowlisted privileged permission is granted.
 */
class VerifyChecker(private val probe: AppFactsProbe) {
    suspend fun check(moduleState: ModuleState): VerifyReport {
        val facts = probe.appFacts()
        return VerifyReport(
            mapOf(
                VerifyCheck.MODULE_PRESENT to
                    outcomeOf(moduleState != ModuleState.Absent && moduleState != ModuleState.Unknown),
                VerifyCheck.MODULE_ENABLED to
                    outcomeOf(moduleState is ModuleState.Enabled),
                VerifyCheck.NO_DISABLE_FLAG to
                    outcomeOf(moduleState !is ModuleState.Disabled),
                VerifyCheck.NO_REMOVE_FLAG to
                    outcomeOf(moduleState != ModuleState.RemovePending),
                VerifyCheck.PACKAGE_IS_SYSTEM to outcomeOf(facts?.isSystemApp == true),
                VerifyCheck.APK_IN_PRIV_APP to
                    outcomeOf(facts?.apkPath?.startsWith(VerifyReport.PRIV_APP_PREFIX) == true),
                VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED to
                    outcomeOf(facts != null && facts.missingPermissions.isEmpty())
            )
        )
    }

    private fun outcomeOf(condition: Boolean): VerifyOutcome = if (condition) VerifyOutcome.PASS else VerifyOutcome.FAIL
}
