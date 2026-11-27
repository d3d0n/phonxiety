package com.phonxiety.bridge

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content identity is namespaced by the section. The same destination key can exist in two stacks
 * — the Doctor, one call's record — and the display keys composition and saveable state by that
 * identity; without the prefix, switching tabs reuses the other section's composition and its
 * remembered state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SectionContentKeyTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun theSameDestinationGetsADifferentContentKeyInEachSection() {
        assertNotEquals(
            contentKeyOf(Section.DASHBOARD, DoctorKey),
            contentKeyOf(Section.CALLS, DoctorKey)
        )
        assertEquals("DASHBOARD:$DoctorKey", contentKeyOf(Section.DASHBOARD, DoctorKey))
    }

    @Test
    fun equalDestinationsInDifferentSectionsKeepTheirOwnSaveableState() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContentView(
            ComposeView(activity).apply {
                setContent { TwoSections() }
            }
        )
        composeRule.waitForIdle()

        assertState("closed")

        // Open the record from the Lab and disclose its details.
        composeRule.onNodeWithText("switch").performClick()
        composeRule.waitForIdle()
        assertState("closed")
        composeRule.onNodeWithText("disclose").performClick()
        composeRule.waitForIdle()
        assertState("open")

        // The Dashboard's equal destination has its own state, not the Lab's.
        composeRule.onNodeWithText("switch").performClick()
        composeRule.waitForIdle()
        assertState("closed")

        // And the Lab's state is still the Lab's on the way back.
        composeRule.onNodeWithText("switch").performClick()
        composeRule.waitForIdle()
        assertState("open")
    }

    @Composable
    private fun TwoSections() {
        val dashboard = remember { NavBackStack<NavKey>(DashboardKey, DetailKey) }
        val lab = remember { NavBackStack<NavKey>(LabKey, DetailKey) }
        var active by remember { mutableStateOf(Section.DASHBOARD) }

        fun providerFor(section: Section): (NavKey) -> NavEntry<NavKey> = entryProvider {
            entryIn<DashboardKey>(section) {}
            entryIn<LabKey>(section) {}
            entryIn<DetailKey>(section) { Detail() }
        }

        val dashboardEntries = sectionEntries(dashboard, providerFor(Section.DASHBOARD))
        val labEntries = sectionEntries(lab, providerFor(Section.LAB))

        PhonxietyTheme(dynamicColor = false) {
            Column {
                Button(
                    onClick = {
                        active = if (active == Section.DASHBOARD) Section.LAB else Section.DASHBOARD
                    }
                ) {
                    Text("switch")
                }
                NavDisplay(
                    entries = if (active == Section.DASHBOARD) dashboardEntries else labEntries,
                    transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                    predictivePopTransitionSpec = {
                        EnterTransition.None togetherWith ExitTransition.None
                    },
                    onBack = {}
                )
            }
        }
    }

    @Composable
    private fun Detail() {
        var open by rememberSaveable { mutableStateOf(false) }
        Column {
            Text(
                text = if (open) "open" else "closed",
                modifier = Modifier.testTag(STATE_TAG)
            )
            Button(onClick = { open = true }) {
                Text("disclose")
            }
        }
    }

    private fun assertState(value: String) {
        composeRule.onNodeWithTag(STATE_TAG).assertTextEquals(value)
    }

    private companion object {
        const val STATE_TAG = "section_state"
    }
}

/** A destination both stacks can hold. */
private data object DetailKey : NavKey
