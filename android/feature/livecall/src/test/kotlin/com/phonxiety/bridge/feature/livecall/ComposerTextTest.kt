package com.phonxiety.bridge.feature.livecall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.designsystem.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's text: local copy only for the reasons the app recognizes, the daemon's own words
 * kept for Details, and the limits read in their own units.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun aRecognizedReasonGetsTheProductsWords() {
        val emergency = localIssue("destination", "Value error, emergency numbers cannot be called by the agent")
        assertEquals(UiText.Res(R.string.composer_error_destination_emergency), emergency.headline)
        assertEquals("Value error, emergency numbers cannot be called by the agent", emergency.detail)

        // A number that fails the daemon's own pattern reads as a sentence, not as the daemon's voice.
        val rejected = localIssue("destination", "String should match pattern '^(\\+[1-9][0-9]{3,14}|[0-9]{3,6})$'")
        assertEquals(UiText.Res(R.string.composer_error_destination), rejected.headline)
        assertNotNull(rejected.detail)

        val emptyGoal = localIssue("goal", "String should have at least 1 character")
        assertEquals(UiText.Res(R.string.composer_error_goal), emptyGoal.headline)
        assertNotNull(emptyGoal.detail)
    }

    @Test
    fun theGoalLengthLimitKeepsItsOwnSentence() {
        // The daemon enforces 8,000 characters; an oversized goal is not "a goal it can act on".
        val tooLong = localIssue("goal", "String should have at most 8000 characters")
        assertEquals(UiText.Res(R.string.composer_error_goal_too_long), tooLong.headline)
        assertEquals("String should have at most 8000 characters", tooLong.detail)
    }

    @Test
    fun anUnrecognizedDiagnosticStaysVerbatim() {
        val field = localIssue("result_fields.3", "field names must be at most 40 characters")
        assertEquals(UiText.Literal("field names must be at most 40 characters"), field.headline)
        assertNull(field.detail)

        // A destination or goal message the app does not know is not replaced by location copy.
        val destination = localIssue("destination", "Value error, the carrier rejects this prefix")
        assertEquals(UiText.Literal("Value error, the carrier rejects this prefix"), destination.headline)
        assertNull(destination.detail)

        val goal = localIssue("goal", "Value error, this goal needs a different language")
        assertEquals(UiText.Literal("Value error, this goal needs a different language"), goal.headline)
        assertNull(goal.detail)
    }

    @Test
    fun limitsReadInTheirOwnUnits() {
        assertEquals("45\u00A0s", formatMinutes(45, resources))
        assertEquals("1\u00A0min", formatMinutes(60, resources))
        assertEquals("1\u00A0min 30\u00A0s", formatMinutes(90, resources))
    }
}
