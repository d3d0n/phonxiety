package com.phonxiety.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The health set Dashboard reads: a grant the module does not carry shows up as missing. */
class PermissionsTest {
    @Test
    fun `the background start grant is part of the privileged health set`() {
        assertTrue(Permissions.PRIVILEGED.contains(Permissions.START_FOREGROUND_SERVICES_FROM_BACKGROUND))
    }

    @Test
    fun `a module without the background start grant reports exactly that permission`() {
        val missing = Permissions.privilegedMissing {
            it != Permissions.START_FOREGROUND_SERVICES_FROM_BACKGROUND
        }

        assertEquals(
            listOf(Permissions.START_FOREGROUND_SERVICES_FROM_BACKGROUND),
            missing
        )
    }

    @Test
    fun `a module that grants everything leaves the health set empty`() {
        assertTrue(Permissions.privilegedMissing { true }.isEmpty())
    }
}
