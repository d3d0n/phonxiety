package com.phonxiety.bridge.feature.onboarding

import com.phonxiety.bridge.os.Permissions
import com.phonxiety.bridge.root.RuntimePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wizard's permission set against the engine's needs and the platform's rules. */
class RuntimePermissionsTest {
    private fun names(vararg permissions: RuntimePermission) = permissions.map { it.androidName }.toSet()

    @Test
    fun theWizardAsksForEveryRuntimePermissionTheEngineNeeds() {
        val engineNeeds = Permissions.RUNTIME.toSet()
        val wizardAsks = RuntimePermission.entries.map { it.androidName }.toSet()

        assertEquals(emptySet<String>(), engineNeeds - wizardAsks)
    }

    @Test
    fun aMissingLocalNetworkGrantIsRequested() {
        val declared = RuntimePermission.entries.map { it.androidName }.toSet()

        val missing = permissionsToRequest(
            needed = RuntimePermission.entries,
            declared = declared,
            runtime = declared,
            granted = declared - RuntimePermission.ACCESS_LOCAL_NETWORK.androidName
        )

        assertEquals(listOf(RuntimePermission.ACCESS_LOCAL_NETWORK), missing)
    }

    @Test
    fun aPermissionThisAndroidReleaseDoesNotKnowIsNotRequested() {
        // Android 17 introduced ACCESS_LOCAL_NETWORK as a runtime permission. Before it the platform
        // reports an unknown permission as not granted, so a dialog can never satisfy the wizard.
        val missing = permissionsToRequest(
            needed = RuntimePermission.entries,
            declared = names(RuntimePermission.ACCESS_LOCAL_NETWORK),
            runtime = emptySet(),
            granted = emptySet()
        )

        assertTrue(missing.isEmpty())
    }

    @Test
    fun anUndeclaredPermissionIsNotRequested() {
        val missing = permissionsToRequest(
            needed = RuntimePermission.entries,
            declared = emptySet(),
            runtime = names(RuntimePermission.ACCESS_LOCAL_NETWORK),
            granted = emptySet()
        )

        assertTrue(missing.isEmpty())
    }

    @Test
    fun grantedPermissionsAreLeftAlone() {
        val declared = RuntimePermission.entries.map { it.androidName }.toSet()

        val missing = permissionsToRequest(
            needed = RuntimePermission.entries,
            declared = declared,
            runtime = declared,
            granted = declared
        )

        assertTrue(missing.isEmpty())
    }
}
