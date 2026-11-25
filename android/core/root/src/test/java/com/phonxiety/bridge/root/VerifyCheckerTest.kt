package com.phonxiety.bridge.root

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyCheckerTest {
    private val goodFacts = AppFacts(
        packageName = "com.phonxiety.bridge",
        isSystemApp = true,
        apkPath = "/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk",
        grantedPermissions = PrivilegedPermissions.ALL
    )

    private suspend fun check(facts: AppFacts?, module: ModuleState): VerifyReport = VerifyChecker { facts }.check(module)

    @Test
    fun installedPrivilegedModulePassesEveryCheck() = runTest {
        val report = check(goodFacts, ModuleState.Enabled("0.3.0", 3))

        assertTrue(report.passed)
        assertTrue(report.failures.isEmpty())
        assertTrue(report.unknown.isEmpty())
    }

    @Test
    fun appInDataAppFailsThePrivAppCheck() = runTest {
        val report = check(
            goodFacts.copy(apkPath = "/data/app/~~x/com.phonxiety.bridge-y/base.apk", isSystemApp = false),
            ModuleState.Enabled("0.3.0", 3)
        )

        assertFalse(report.passed)
        assertEquals(
            listOf(VerifyCheck.PACKAGE_IS_SYSTEM, VerifyCheck.APK_IN_PRIV_APP),
            report.failures
        )
    }

    @Test
    fun missingPrivilegedPermissionFailsOnlyThatCheck() = runTest {
        val report = check(
            goodFacts.copy(
                grantedPermissions = PrivilegedPermissions.ALL - PrivilegedPermissions.REBOOT
            ),
            ModuleState.Enabled("0.3.0", 3)
        )

        assertFalse(report.passed)
        assertEquals(listOf(VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED), report.failures)
    }

    @Test
    fun disabledOrRemovedModuleFailsItsChecks() = runTest {
        val disabled = check(goodFacts, ModuleState.Disabled("0.3.0", 3))
        assertEquals(
            listOf(VerifyCheck.MODULE_ENABLED, VerifyCheck.NO_DISABLE_FLAG),
            disabled.failures
        )

        // The module is still present until the next boot; only the remove flag fails.
        val removed = check(goodFacts, ModuleState.RemovePending)
        assertEquals(
            listOf(VerifyCheck.MODULE_ENABLED, VerifyCheck.NO_REMOVE_FLAG),
            removed.failures
        )
    }

    @Test
    fun unreadableModuleDirectoryFailsPresence() = runTest {
        val report = check(goodFacts, ModuleState.Unknown)

        assertEquals(listOf(VerifyCheck.MODULE_PRESENT, VerifyCheck.MODULE_ENABLED), report.failures)
    }

    @Test
    fun absentAppFactsFailThePackageChecks() = runTest {
        val report = check(null, ModuleState.Enabled("0.3.0", 3))

        assertEquals(
            listOf(
                VerifyCheck.PACKAGE_IS_SYSTEM,
                VerifyCheck.APK_IN_PRIV_APP,
                VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED
            ),
            report.failures
        )
    }
}
