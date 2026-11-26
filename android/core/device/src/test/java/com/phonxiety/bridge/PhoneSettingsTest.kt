package com.phonxiety.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone preferences clamp to the ranges the daemon accepts and normalise the set values. */
class PhoneSettingsTest {
    @Test
    fun `unknown codecs and levels fall back to auto and info`() {
        val settings = PhoneSettings.sanitized(
            preferredCodec = "MP3",
            maxBitrate = 32_000,
            wifiLowLatency = false,
            thermalAwareness = true,
            batteryAwareness = true,
            logLevel = "verbose",
            testNumber = null,
            autoTuneEnabled = false
        )
        assertEquals(PhoneCodec.AUTO, settings.preferredCodec)
        assertEquals("info", settings.logLevel)
        assertEquals("", settings.testNumber)
    }

    @Test
    fun `a bitrate outside the daemon range is clamped, not rejected`() {
        assertEquals(
            PhoneSettings.MIN_BITRATE,
            PhoneSettings.sanitized(null, 1_000, false, true, true, null, null, false).maxBitrate
        )
        assertEquals(
            PhoneSettings.MAX_BITRATE,
            PhoneSettings.sanitized(null, 512_000, false, true, true, null, null, false).maxBitrate
        )
    }

    @Test
    fun `the test number keeps one leading plus and digits only`() {
        assertEquals("+12025550123", PhoneSettings.sanitizeNumber(" +1 (202) 555-0123 "))
        assertEquals("+12025550123", PhoneSettings.sanitizeNumber("++1 202 555 0123"))
        assertEquals("", PhoneSettings.sanitizeNumber("   "))
        assertEquals("911", PhoneSettings.sanitizeNumber("911"))
    }

    @Test
    fun `the draft keeps a plus typed before its digits`() {
        assertEquals("+", PhoneSettings.draftNumber("+"))
        assertEquals("+", PhoneSettings.draftNumber(" + "))
        assertEquals("+9", PhoneSettings.draftNumber("+9"))
        assertEquals("", PhoneSettings.draftNumber(""))
        assertEquals("+", PhoneSettings.draftNumber("+ "))
        assertEquals("911", PhoneSettings.draftNumber("911"))
    }

    @Test
    fun `typing a plus one key at a time reaches the whole number`() {
        val typed = "+998901234567"
        var draft = ""
        for (index in typed.indices) {
            // What the field hands the view model: the draft plus the new key.
            draft = PhoneSettings.draftNumber(draft + typed[index])
            assertEquals("the draft keeps everything typed so far", typed.substring(0, index + 1), draft)
        }
        assertEquals(typed, PhoneSettings.sanitizeNumber(draft))
    }

    @Test
    fun `the codec normaliser accepts the three values the protocol knows`() {
        assertEquals("opus", PhoneCodec.normalize(" OPUS "))
        assertEquals("pcm", PhoneCodec.normalize("pcm"))
        assertEquals("auto", PhoneCodec.normalize("auto"))
        assertEquals("auto", PhoneCodec.normalize(null))
    }

    @Test
    fun `the log level accepts debug through error only`() {
        assertEquals(BridgeLog.DEBUG, BridgeLog.levelOf("debug"))
        assertEquals(BridgeLog.ERROR, BridgeLog.levelOf("ERROR"))
        assertEquals(null, BridgeLog.levelOf("trace"))
        assertTrue(BridgeLog.levelName(BridgeLog.WARN) == "warn")
    }
}
