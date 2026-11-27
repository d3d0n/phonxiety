package com.phonxiety.bridge

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's navigation rules without a screen: each section keeps its own depth, a tap switches
 * or acts on the active section, back walks out section by section, and every [AppSignal] changes
 * exactly the stack its contract names.
 */
class SectionNavigatorTest {
    private val dashboard = NavBackStack<NavKey>(DashboardKey)
    private val calls = NavBackStack<NavKey>(CallsKey)
    private val lab = NavBackStack<NavKey>(LabKey)
    private val settings = NavBackStack<NavKey>(SettingsKey)
    private var active = Section.DASHBOARD
    private val scrollToTop = ScrollToTopRegistry()

    private val navigator = SectionNavigator(
        stacks = SectionStacks(dashboard, calls, lab, settings),
        activeSection = { active },
        setActiveSection = { active = it },
        scrollToTop = scrollToTop
    )

    @Test
    fun aTabTapActivatesTheOtherSectionAndKeepsItsDepth() {
        navigator.openSection(Section.CALLS)
        navigator.push(CallDetailKey("call-1"))

        navigator.openSection(Section.LAB)
        assertEquals(Section.LAB, active)
        assertEquals(listOf<NavKey>(CallsKey, CallDetailKey("call-1")), calls.toList())

        navigator.openSection(Section.CALLS)
        assertEquals(listOf<NavKey>(CallsKey, CallDetailKey("call-1")), navigator.stack().toList())
    }

    @Test
    fun reTappingTheActiveSectionPopsItToTheRoot() {
        navigator.openSection(Section.CALLS)
        navigator.push(CallDetailKey("call-1"))
        navigator.push(DoctorKey)

        navigator.openSection(Section.CALLS)

        assertEquals(listOf<NavKey>(CallsKey), calls.toList())
        assertEquals(Section.CALLS, active)
    }

    @Test
    fun reTappingTheActiveSectionAtItsRootDispatchesThatSectionsScrollToTop() {
        var dashboardScrolls = 0
        var callScrolls = 0
        scrollToTop.register(Section.DASHBOARD.scrollToTopKey) { dashboardScrolls++ }
        scrollToTop.register(Section.CALLS.scrollToTopKey) { callScrolls++ }

        navigator.openSection(Section.CALLS)
        navigator.openSection(Section.CALLS)

        assertEquals(1, callScrolls)
        assertEquals(0, dashboardScrolls)
        assertEquals(listOf<NavKey>(CallsKey), calls.toList())
    }

    @Test
    fun reTappingASectionWithNoRegistrationIsANoOp() {
        navigator.openSection(Section.LAB)
        navigator.openSection(Section.LAB)

        assertEquals(listOf<NavKey>(LabKey), lab.toList())
        assertEquals(Section.LAB, active)
    }

    @Test
    fun backPopsWithinTheActiveSection() {
        navigator.push(DoctorKey)

        navigator.back { }

        assertEquals(listOf<NavKey>(DashboardKey), dashboard.toList())
    }

    @Test
    fun backAtANonDashboardRootShowsTheDashboard() {
        navigator.openSection(Section.SETTINGS)

        navigator.back { }

        assertEquals(Section.DASHBOARD, active)
        assertEquals(listOf<NavKey>(DashboardKey), dashboard.toList())
    }

    @Test
    fun backAtTheDashboardRootLeavesTheApp() {
        var finished = 0

        navigator.back { finished++ }

        assertEquals(1, finished)
    }

    @Test
    fun theSuiteStaysOnANestedDestinationAndHidesOnAFullScreenFlow() {
        assertTrue(showsNavigationSuite(listOf(DashboardKey)))
        assertTrue(showsNavigationSuite(listOf(CallsKey, CallDetailKey("call-1"))))
        assertTrue(showsNavigationSuite(listOf(LabKey, DoctorKey)))
        assertFalse(showsNavigationSuite(listOf(DashboardKey, LiveCallKey("call-1"))))
        assertFalse(showsNavigationSuite(listOf(CallsKey, ComposerKey())))
        assertFalse(showsNavigationSuite(listOf(DashboardKey, OnboardingKey())))
        assertFalse(showsNavigationSuite(listOf(OnboardingKey(), DoctorKey)))
    }

    @Test
    fun aPairingLinkBringsTheDashboardForwardAndReplacesThePendingLink() {
        navigator.openSection(Section.LAB)

        navigator.open(AppSignal.Pair("phonxiety://pair?one"))
        assertEquals(Section.DASHBOARD, active)
        assertEquals(listOf<NavKey>(DashboardKey, OnboardingKey("phonxiety://pair?one")), dashboard.toList())

        navigator.open(AppSignal.Pair("phonxiety://pair?two"))
        assertEquals(listOf<NavKey>(DashboardKey, OnboardingKey("phonxiety://pair?two")), dashboard.toList())
    }

    @Test
    fun aPairingLinkKeepsTheScreenOpenedFromTheWizard() {
        navigator.open(AppSignal.Pair("phonxiety://pair?one"))
        navigator.push(DoctorKey)

        navigator.open(AppSignal.Pair("phonxiety://pair?two"))

        assertEquals(
            listOf<NavKey>(DashboardKey, OnboardingKey("phonxiety://pair?two"), DoctorKey),
            dashboard.toList()
        )
    }

    @Test
    fun theDashboardSignalResetsOnlyTheDashboardStack() {
        navigator.openSection(Section.CALLS)
        navigator.push(CallDetailKey("call-1"))
        navigator.openSection(Section.DASHBOARD)
        navigator.push(DoctorKey)

        navigator.open(AppSignal.Dashboard)

        assertEquals(listOf<NavKey>(DashboardKey), dashboard.toList())
        assertEquals(listOf<NavKey>(CallsKey, CallDetailKey("call-1")), calls.toList())
        assertEquals(Section.DASHBOARD, active)
    }

    @Test
    fun theDashboardSignalAlsoReplacesTheWizardAtTheDashboardRoot() {
        val wizard = SectionStacks(NavBackStack<NavKey>(OnboardingKey()), calls, lab, settings)
        val fresh = SectionNavigator(wizard, { active }, { active = it }, scrollToTop)

        fresh.open(AppSignal.Dashboard)

        assertEquals(listOf<NavKey>(DashboardKey), wizard.dashboard.toList())
    }

    @Test
    fun aLiveCallOpensOncePerStackOverTheActiveSection() {
        navigator.openSection(Section.CALLS)

        navigator.open(AppSignal.LiveCall("call-7"))
        assertEquals(listOf<NavKey>(CallsKey, LiveCallKey("call-7")), calls.toList())

        navigator.open(AppSignal.LiveCall("call-7"))
        assertEquals(listOf<NavKey>(CallsKey, LiveCallKey("call-7")), calls.toList())

        navigator.open(AppSignal.Doctor)
        navigator.open(AppSignal.LiveCall("call-7"))
        assertEquals(listOf<NavKey>(CallsKey, DoctorKey, LiveCallKey("call-7")), calls.toList())
        assertTrue(dashboard.toList() == listOf<NavKey>(DashboardKey))
    }

    @Test
    fun theSetupReminderAddsTheWizardOnceOverTheDashboard() {
        navigator.openSection(Section.CALLS)

        navigator.open(AppSignal.Setup)
        navigator.open(AppSignal.Setup)

        assertEquals(Section.DASHBOARD, active)
        assertEquals(listOf<NavKey>(DashboardKey, OnboardingKey()), dashboard.toList())
    }

    @Test
    fun finishingSetupStartsOverAtTheDashboardRoot() {
        navigator.open(AppSignal.Pair("phonxiety://pair?one"))
        navigator.push(DoctorKey)

        navigator.finishSetup()

        assertEquals(listOf<NavKey>(DashboardKey), dashboard.toList())
        assertEquals(Section.DASHBOARD, active)
    }

    @Test
    fun theDoctorSignalOpensOverTheActiveSectionAndBackReturnsToIt() {
        navigator.openSection(Section.LAB)

        navigator.open(AppSignal.Doctor)

        assertEquals(listOf<NavKey>(LabKey, DoctorKey), lab.toList())
        navigator.back { }
        assertEquals(listOf<NavKey>(LabKey), lab.toList())
    }
}
