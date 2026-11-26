package com.phonxiety.bridge.feature.livecall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer's text: local copy for known problems, and a number a person can check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun aKnownProblemGetsTheProductsWords() {
        val emergency = localIssue("destination", "emergency numbers cannot be called by the agent")
        assertEquals(UiText.Res(R.string.composer_error_destination_emergency), emergency.headline)
        assertEquals("emergency numbers cannot be called by the agent", emergency.detail)

        // Any other refusal to dial that number reads as a sentence, not as the daemon's voice.
        val rejected = localIssue("destination", "String should match pattern '^(\\+[1-9][0-9]{3,14}|[0-9]{3,6})$'")
        assertEquals(UiText.Res(R.string.composer_error_destination), rejected.headline)

        val goal = localIssue("goal", "String should have at least 1 character")
        assertEquals(UiText.Res(R.string.composer_error_goal), goal.headline)
    }

    @Test
    fun anUnknownDiagnosticStaysVerbatim() {
        val issue = localIssue("result_fields.3", "field names must be at most 40 characters")
        assertEquals(UiText.Literal("field names must be at most 40 characters"), issue.headline)
    }

    @Test
    fun limitsReadInTheirOwnUnits() {
        assertEquals("45\u00A0s", formatMinutes(45, resources))
        assertEquals("1\u00A0min", formatMinutes(60, resources))
        assertEquals("1\u00A0min 30\u00A0s", formatMinutes(90, resources))
    }

    @Test
    fun aNumberKeepsItsCountryAndThePlus() {
        for (e164 in listOf("+998901234567", "+14155550123", "+442071234567")) {
            val formatted = formatDestination(e164)
            assertTrue(formatted, formatted.startsWith("+"))
            // Same digits, same country: only the grouping may change.
            assertEquals(e164.filter { it.isDigit() }, formatted.filter { it.isDigit() })
        }
        // A service short number is dialled exactly as typed.
        assertEquals("1050", formatDestination("1050"))
    }

    @Test
    fun theRegionComesFromTheNumbersOwnCallingCode() {
        assertEquals("UZ", regionOf("+998901234567"))
        assertEquals("US", regionOf("+14155550123"))
        assertEquals("GB", regionOf("+442071234567"))
        assertEquals("", regionOf("+99912345"))
    }
}
