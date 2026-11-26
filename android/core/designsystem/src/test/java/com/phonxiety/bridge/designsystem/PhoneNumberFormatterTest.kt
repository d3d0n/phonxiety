package com.phonxiety.bridge.designsystem

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one phone formatter: the country comes from the number, never from a table of calling codes,
 * and a number the platform cannot parse is still the number the user typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneNumberFormatterTest {
    private val previousLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun everyCountryKeepsItsOwnCallingCodeAndThePlus() {
        // Uzbekistan, the United States, the United Kingdom and Russia: four calling codes whose
        // grouping differs, so a locale-driven formatter would show on at least one of them.
        for (e164 in listOf("+998901234567", "+14155550123", "+442071234567", "+79161234567")) {
            val formatted = formatPhoneNumber(e164)
            assertTrue("$e164 -> $formatted", formatted.startsWith("+"))
            assertEquals(e164, formatted.filter { it == '+' } + formatted.filter { it.isDigit() })
        }
    }

    @Test
    fun thePhonesLocaleDoesNotDecideTheCountry() {
        Locale.setDefault(Locale.US)
        val fromUsLocale = formatPhoneNumber("+998901234567")
        Locale.setDefault(Locale.GERMANY)
        val fromDeLocale = formatPhoneNumber("+998901234567")
        assertEquals(fromUsLocale, fromDeLocale)
        assertTrue(fromUsLocale, fromUsLocale.contains("998"))
    }

    @Test
    fun aNationalFormattingPlatformMayNotRewriteTheCountry() {
        // Android formats Japanese and Korean numbers in the national style under those locales,
        // replacing the country code with a domestic prefix. The dialled identity has to survive.
        for ((locale, e164) in listOf(
            Locale.JAPAN to "+81312345678",
            Locale.KOREA to "+82212345678"
        )) {
            Locale.setDefault(locale)
            val formatted = formatPhoneNumber(e164)
            assertTrue("$locale $e164 -> $formatted", formatted.startsWith("+"))
            assertEquals(e164.digits(), formatted.digits())
        }
    }

    @Test
    fun aShortServiceNumberIsDialedAsTyped() {
        assertEquals("1050", formatPhoneNumber("1050"))
        assertEquals("+998 90 123 45 67", formatPhoneNumber("  +998 90 123 45 67  "))
    }

    @Test
    fun aMaskedNumberIsNeverInvented() {
        // The dashboard shows the mask the daemon sends; formatting may not turn it into digits.
        val masked = "+998 ·· 42"
        assertEquals(masked, formatPhoneNumber(masked))
    }

    private fun String.digits(): String = filter { it.isDigit() }
}
