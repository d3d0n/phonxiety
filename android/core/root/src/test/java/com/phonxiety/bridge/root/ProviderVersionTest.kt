package com.phonxiety.bridge.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The four CLI answer shapes, and what the wizard shows for each. */
class ProviderVersionTest {
    @Test
    fun magiskPrintsTheNameAndTheCodeInTwoCommands() {
        assertEquals("31.1 (31100)", parseProviderVersion("31.1:MAGISK:R", "31100"))
    }

    @Test
    fun magiskNameWithoutACodeStaysAName() {
        assertEquals("31.1", parseProviderVersion("31.1:MAGISK:R"))
    }

    @Test
    fun magiskCodeAloneIsTheCode() {
        assertEquals("31100", parseProviderVersion("31100\n"))
    }

    @Test
    fun kernelSuPrintsCommandNameVersionAndUapi() {
        assertEquals("3.1.0", parseProviderVersion("ksud 3.1.0 (uapi: 2)"))
    }

    @Test
    fun kernelSuNextTaggedNameKeepsTheSuffix() {
        assertEquals("1.0.7-45-gabcdef", parseProviderVersion("ksud 1.0.7-45-gabcdef (uapi: 3)"))
    }

    @Test
    fun apatchPrintsCommandNameAndBareCode() {
        assertEquals("11125", parseProviderVersion("apd 11125"))
    }

    @Test
    fun aDottedNameAndAParenthesisedCodeKeepBoth() {
        assertEquals("1.0.5 (12000)", parseProviderVersion("ksud 1.0.5 (12000)"))
    }

    @Test
    fun anAnswerWithoutAVersionIsNotAVersion() {
        assertNull(ProviderVersion.parse("magisk: not found"))
        assertNull(ProviderVersion.parse("uid=0(root) gid=0(root)"))
        assertNull(ProviderVersion.parse(""))
        assertNull(ProviderVersion.parse(null))
    }

    @Test
    fun theUapiNumberIsNotABuildCode() {
        assertEquals(ProviderVersion(name = "3.1.0", code = null), ProviderVersion.parse("ksud 3.1.0 (uapi: 2)"))
    }
}
