package com.phonxiety.bridge.designsystem

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Resource and plural resolution, including the plural forms the UI uses every day. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiTextResolutionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun resolvesPluralForZeroOneTwoAndFive() {
        assertEquals("0\u00A0min", UiText.Plural(R.plurals.designsystem_duration_minutes, 0).asString(resources))
        assertEquals("1\u00A0min", UiText.Plural(R.plurals.designsystem_duration_minutes, 1).asString(resources))
        assertEquals("2\u00A0min", UiText.Plural(R.plurals.designsystem_duration_minutes, 2).asString(resources))
        assertEquals("5\u00A0min", UiText.Plural(R.plurals.designsystem_duration_minutes, 5).asString(resources))
    }

    @Test
    fun resolvesResWithArgsAndLiteral() {
        assertEquals(
            "45\u00A0s",
            UiText.Res(R.string.designsystem_duration_seconds, listOf(45)).asString(resources)
        )
        assertEquals("daemon timed out", UiText.Literal("daemon timed out").asString(resources))
    }

    @Test
    fun composableResolverReadsTheSameResources() {
        val text = UiText.Plural(R.plurals.designsystem_duration_minutes, 2)
        composeRule.setContent { Text(text.asString()) }
        composeRule.onNodeWithText("2\u00A0min").assertTextEquals("2\u00A0min")
    }
}
