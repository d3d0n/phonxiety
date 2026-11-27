package com.phonxiety.bridge

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry

/**
 * The host's navigation invariants, as operations on the [SectionStacks].
 *
 * - A section tap activates that section's stack and leaves every other stack as it was; the
 *   active section's depth is what the screen shows, so coming back to a tab returns to the
 *   detail that was open on it.
 * - Re-tapping the active section pops it to its root; at the root it dispatches the root
 *   screen's scroll-to-top action instead, which is how a list returns to its first row.
 * - Back pops the active stack; at a non-Dashboard root it shows Dashboard; at the Dashboard root
 *   it leaves the app.
 * - The wizard is the Dashboard section's setup flow, so [AppSignal.Pair] and [AppSignal.Setup]
 *   bring the Dashboard tab forward and touch nothing else. Everything else opens over the
 *   section the owner is on.
 *
 * The class is the whole state machine: the host only renders what [active] and [stack] return,
 * which keeps every rule testable without a screen.
 */
internal class SectionNavigator(
    val stacks: SectionStacks,
    private val activeSection: () -> Section,
    private val setActiveSection: (Section) -> Unit,
    private val scrollToTop: ScrollToTopRegistry
) {
    /** The section whose stack is on screen. */
    fun active(): Section = activeSection()

    /** The stack on screen; its last entry is the destination being drawn. */
    fun stack(): NavBackStack<NavKey> = stacks[activeSection()]

    /** Whether the navigation suite is drawn for the current stack. */
    fun showsSuite(): Boolean = showsNavigationSuite(stack())

    /** A tab tap: activate another section, or act on the one already active. */
    fun openSection(section: Section) {
        if (section != activeSection()) {
            setActiveSection(section)
            return
        }
        val stack = stacks[section]
        if (stack.size > 1) {
            popToRoot(stack)
        } else {
            scrollToTop.dispatch(section.scrollToTopKey)
        }
    }

    /** System back and every screen's own back arrow. */
    fun back(finish: () -> Unit) {
        val stack = stack()
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return
        }
        if (activeSection() != Section.DASHBOARD) {
            setActiveSection(Section.DASHBOARD)
            return
        }
        finish()
    }

    /** A screen pushed from the one on top of the active stack. */
    fun push(key: NavKey) {
        val stack = stack()
        if (stack.lastOrNull() == key) return
        stack.add(key)
    }

    /** The call a screen was replaced with, e.g. the composer by the call it placed. */
    fun replaceTop(key: NavKey) {
        val stack = stack()
        stack[stack.lastIndex] = key
    }

    /** Runs [AppSignal]'s rule; see its KDoc for the table this implements. */
    fun open(signal: AppSignal) {
        when (signal) {
            is AppSignal.Pair -> openWizard(signal.link)
            AppSignal.Dashboard -> showDashboard()
            is AppSignal.LiveCall -> openLiveCall(signal.callId)
            AppSignal.Doctor -> push(DoctorKey)
            AppSignal.Setup -> openWizard(link = null)
        }
    }

    /** Setup finished: the Dashboard section starts over at its root. */
    fun finishSetup() {
        val stack = stacks.dashboard
        stack.clear()
        stack.add(DashboardKey)
        setActiveSection(Section.DASHBOARD)
    }

    private fun openWizard(link: String?) {
        setActiveSection(Section.DASHBOARD)
        val stack = stacks.dashboard
        val existing = stack.indexOfFirst { it is OnboardingKey }
        if (link != null) {
            // A second pairing link replaces the pending one instead of stacking another wizard;
            // a screen opened from the wizard stays, so an interrupted pairing keeps its context.
            if (existing >= 0) {
                stack[existing] = OnboardingKey(link)
            } else {
                stack.add(OnboardingKey(link))
            }
            return
        }
        if (existing < 0) stack.add(OnboardingKey())
    }

    private fun showDashboard() {
        setActiveSection(Section.DASHBOARD)
        val stack = stacks.dashboard
        popToRoot(stack)
        // The wizard can be the Dashboard root on a fresh install; a dashboard request lands on
        // the dashboard itself, not on the setup flow it replaces.
        if (stack.first() != DashboardKey) stack[0] = DashboardKey
    }

    private fun openLiveCall(callId: String) {
        val stack = stack()
        val key = LiveCallKey(callId)
        if (stack.lastOrNull() == key) return
        // One screen per call in this stack: a second tap brings the call to the top instead of
        // stacking a copy that would collect the same state twice.
        stack.remove(key)
        stack.add(key)
    }

    private fun popToRoot(stack: MutableList<NavKey>) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
