package com.phonxiety.bridge

import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * The four top-level sections. Each owns one back stack, and its root screen registers the
 * scroll-to-top action under [scrollToTopKey]; the keys are shared with the feature modules and
 * must not change.
 *
 * The order is the order of the navigation suite.
 */
internal enum class Section(
    val key: NavKey,
    @StringRes val label: Int,
    val scrollToTopKey: String
) {
    DASHBOARD(DashboardKey, R.string.nav_home, "dashboard"),
    CALLS(CallsKey, R.string.nav_calls, "calls"),
    LAB(LabKey, R.string.nav_lab, "lab"),
    SETTINGS(SettingsKey, R.string.nav_settings, "settings")
}

/** The one stack each section keeps; every stack starts at its own root. */
internal class SectionStacks(
    val dashboard: NavBackStack<NavKey>,
    val calls: NavBackStack<NavKey>,
    val lab: NavBackStack<NavKey>,
    val settings: NavBackStack<NavKey>
) {
    operator fun get(section: Section): NavBackStack<NavKey> = when (section) {
        Section.DASHBOARD -> dashboard
        Section.CALLS -> calls
        Section.LAB -> lab
        Section.SETTINGS -> settings
    }
}

/** The section a top-level key belongs to, or `null` for a destination pushed on top of one. */
internal fun sectionOf(key: NavKey?): Section? = Section.entries.firstOrNull { it.key == key }

/**
 * A full-screen flow: the wizard, the live call and the composer draw their own way out and hide
 * the navigation suite.
 */
internal fun NavKey.isFullScreenFlow(): Boolean = this is OnboardingKey || this is LiveCallKey || this is ComposerKey

/**
 * Whether the navigation suite is drawn for [stack]. It stays visible on every destination nested
 * inside a section, so a detail entry survives a look at another tab; it hides on a full-screen
 * flow and while the wizard is the flow's root, where none of the sections apply yet.
 */
internal fun showsNavigationSuite(stack: List<NavKey>): Boolean = stack.firstOrNull() !is OnboardingKey && stack.lastOrNull()?.isFullScreenFlow() != true
