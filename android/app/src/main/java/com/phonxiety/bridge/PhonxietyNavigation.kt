package com.phonxiety.bridge

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.phonxiety.bridge.designsystem.LocalScrollToTop
import com.phonxiety.bridge.designsystem.Motion
import com.phonxiety.bridge.designsystem.ScrollToTopRegistry
import com.phonxiety.bridge.designsystem.rememberScrollToTopRegistry
import com.phonxiety.bridge.feature.calls.CallDetailRoute
import com.phonxiety.bridge.feature.calls.CallsRoute
import com.phonxiety.bridge.feature.dashboard.DashboardRoute
import com.phonxiety.bridge.feature.doctor.DoctorRoute
import com.phonxiety.bridge.feature.lab.LabRoute
import com.phonxiety.bridge.feature.livecall.ComposerIntent
import com.phonxiety.bridge.feature.livecall.ComposerRoute
import com.phonxiety.bridge.feature.livecall.LiveCallRoute
import com.phonxiety.bridge.feature.onboarding.OnboardingRoute
import com.phonxiety.bridge.feature.settings.SettingsRoute
import com.phonxiety.bridge.feature.updates.UpdatesRoute
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The Navigation 3 host: the wizard until setup is Ready, then the four sections of [Section],
 * each on its own back stack — a bar on a phone, a rail on a large or landscape screen.
 *
 * A section keeps whatever was pushed on it, so a call's record open in Calls is still there after
 * a look at the Lab; the suite stays visible on those nested destinations and hides on the
 * full-screen flows ([showsNavigationSuite]). The host is also the one owner of the design
 * system's scroll-to-top registry: a section's root screen registers its action under the
 * section's key, and a re-tap of the active tab dispatches it (see [SectionNavigator]).
 *
 * [start] is resolved off-composition by the activity while the splash covers the gap; [strip] is
 * the host-scoped link summary, drawn above the suite while it says something.
 */
@Composable
internal fun PhonxietyNavigationHost(
    signal: AppSignal?,
    start: NavKey,
    strip: LinkStripState,
    onSignalConsumed: () -> Unit,
    scrollToTop: ScrollToTopRegistry = rememberScrollToTopRegistry()
) {
    val configuration = remember { navigationConfiguration() }
    val dashboardStack = rememberNavBackStack(configuration, start)
    val callsStack = rememberNavBackStack(configuration, CallsKey)
    val labStack = rememberNavBackStack(configuration, LabKey)
    val settingsStack = rememberNavBackStack(configuration, SettingsKey)
    val stacks = remember(dashboardStack, callsStack, labStack, settingsStack) {
        SectionStacks(dashboardStack, callsStack, labStack, settingsStack)
    }
    var active by rememberSaveable { mutableStateOf(Section.DASHBOARD) }
    val navigator = remember(stacks, scrollToTop) {
        SectionNavigator(stacks, { active }, { active = it }, scrollToTop)
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val onBack: () -> Unit = { navigator.back { activity?.finish() } }

    LaunchedEffect(signal) {
        val current = signal ?: return@LaunchedEffect
        navigator.open(current)
        onSignalConsumed()
    }

    val provider = entryProvider<NavKey> {
        entry<OnboardingKey>(
            metadata = NavDisplay.predictivePopTransitionSpec { wizardPredictivePop() }
        ) { key ->
            OnboardingRoute(
                initialPairLink = key.pairLink,
                onFinished = { navigator.finishSetup() },
                onOpenDoctor = { navigator.push(DoctorKey) }
            )
        }
        entry<DashboardKey> {
            DashboardRoute(
                onOpenSetup = { navigator.push(OnboardingKey()) },
                onOpenDoctor = { navigator.push(DoctorKey) },
                onNewCall = { navigator.push(ComposerKey()) },
                onOpenLiveCall = { callId -> navigator.push(LiveCallKey(callId)) },
                onRunLinkTest = { navigator.openSection(Section.LAB) },
                onOpenUpdates = { navigator.push(UpdatesKey) }
            )
        }
        entry<CallsKey> {
            CallsRoute(
                onOpenCall = { callId -> navigator.push(CallDetailKey(callId)) },
                onNewCall = { navigator.push(ComposerKey()) }
            )
        }
        entry<LabKey> {
            LabRoute(
                onBack = onBack,
                onOpenCall = { callId -> navigator.push(CallDetailKey(callId)) }
            )
        }
        entry<SettingsKey> {
            SettingsRoute(onBack = onBack)
        }
        entry<UpdatesKey> {
            UpdatesRoute(onBack = onBack)
        }
        entry<DoctorKey> {
            DoctorRoute(onBack = onBack)
        }
        entry<LiveCallKey> { key ->
            LiveCallRoute(
                callId = key.callId,
                onBack = onBack,
                onOpenDetail = { callId -> navigator.push(CallDetailKey(callId)) }
            )
        }
        entry<CallDetailKey> { key ->
            CallDetailRoute(
                callId = key.callId,
                onBack = onBack,
                onCallAgain = { brief ->
                    navigator.push(
                        ComposerKey(
                            destination = brief.destination,
                            goal = brief.goal,
                            context = brief.context,
                            constraints = brief.constraints
                        )
                    )
                },
                onOpenLive = { callId -> navigator.push(LiveCallKey(callId)) }
            )
        }
        entry<ComposerKey> { key ->
            ComposerRoute(
                onBack = onBack,
                onPlaced = { callId ->
                    // The composer is replaced by the call it placed: back returns to the list.
                    navigator.replaceTop(LiveCallKey(callId))
                },
                intent = ComposerIntent(
                    destination = key.destination,
                    goal = key.goal,
                    context = key.context,
                    constraints = key.constraints
                )
            )
        }
    }

    // One decorated entry list per stack, so a tab that is not on screen keeps its ViewModels and
    // its saveable state instead of losing them to the next stack's pop.
    val dashboardEntries = sectionEntries(dashboardStack, provider)
    val callsEntries = sectionEntries(callsStack, provider)
    val labEntries = sectionEntries(labStack, provider)
    val settingsEntries = sectionEntries(settingsStack, provider)
    val entries = when (active) {
        Section.DASHBOARD -> dashboardEntries
        Section.CALLS -> callsEntries
        Section.LAB -> labEntries
        Section.SETTINGS -> settingsEntries
    }

    val currentStack = navigator.stack()
    val top = currentStack.lastOrNull()
    val suiteVisible = showsNavigationSuite(currentStack)
    val transitions = navTransitions(reduceMotion = !Motion.animationsEnabled())
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    HostScrollRegistry(scrollToTop) {
        // The display's own back callback is disabled at a section root (there is nothing to pop),
        // so the host answers back itself there: a non-Dashboard root goes home, the Dashboard
        // root leaves the app. When the display can pop it takes the press and the host hears
        // nothing, which keeps one pop per press.
        BackHandler { onBack() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // While the strip occupies the status bar, the screens below must not pay it
                    // again; the strip pads its own content by the height measured above.
                    if (suiteVisible && strip is LinkStripState.Visible) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                )
        ) {
            if (suiteVisible && strip is LinkStripState.Visible) {
                LinkStatusStrip(state = strip, topInset = statusBarHeight)
            }
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    Section.entries.forEach { section ->
                        item(
                            selected = section == active,
                            onClick = { navigator.openSection(section) },
                            icon = {
                                Icon(imageVector = section.icon(), contentDescription = null)
                            },
                            label = { Text(stringResource(section.label)) }
                        )
                    }
                },
                layoutType = if (suiteVisible) navigationSuiteType() else NavigationSuiteType.None
            ) {
                NavDisplay(
                    entries = entries,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(destinationTag(top)),
                    onBack = onBack,
                    transitionSpec = transitions.enterSpec,
                    popTransitionSpec = transitions.popSpec,
                    predictivePopTransitionSpec = transitions.predictivePopSpec
                )
            }
        }
    }
}

/**
 * Installs the host's scroll-to-top registry for everything drawn below it. The assertion is the
 * host-level check that the provision happened: a screen's RegisterScrollToTop would otherwise
 * fail at that screen, far from the host that forgot the registry.
 */
@Composable
internal fun HostScrollRegistry(registry: ScrollToTopRegistry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScrollToTop provides registry) {
        check(LocalScrollToTop.current === registry) {
            "the navigation host did not install its scroll-to-top registry"
        }
        content()
    }
}

/** The saved-state description of every destination the host can restore. */
private fun navigationConfiguration(): SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(DashboardKey::class, DashboardKey.serializer())
            subclass(CallsKey::class, CallsKey.serializer())
            subclass(LabKey::class, LabKey.serializer())
            subclass(SettingsKey::class, SettingsKey.serializer())
            subclass(UpdatesKey::class, UpdatesKey.serializer())
            subclass(OnboardingKey::class, OnboardingKey.serializer())
            subclass(DoctorKey::class, DoctorKey.serializer())
            subclass(LiveCallKey::class, LiveCallKey.serializer())
            subclass(CallDetailKey::class, CallDetailKey.serializer())
            subclass(ComposerKey::class, ComposerKey.serializer())
        }
    }
}

/** One stack's entries, with the state holder and the ViewModel store scoped to that stack. */
@Composable
private fun sectionEntries(
    stack: NavBackStack<NavKey>,
    provider: (NavKey) -> NavEntry<NavKey>
): List<NavEntry<NavKey>> = rememberDecoratedNavEntries(
    backStack = stack,
    entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator()
    ),
    entryProvider = provider
)

/** Bar on a phone, rail once the window is wide or the phone is turned on its side. */
@Composable
private fun navigationSuiteType(): NavigationSuiteType {
    val density = LocalDensity.current
    val container = LocalWindowInfo.current.containerSize
    val width = with(density) { container.width.toDp() }
    val height = with(density) { container.height.toDp() }
    // Width, not orientation flags: the window is the truth on a foldable and in split screen.
    val wide = width.value >= WIDE_DP || width >= height
    return if (wide) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar
}

private fun Section.icon(): ImageVector = when (this) {
    Section.DASHBOARD -> Icons.Filled.Home
    Section.CALLS -> Icons.Filled.Call
    Section.LAB -> Icons.Filled.Build
    Section.SETTINGS -> Icons.Filled.Settings
}

/**
 * The destination the host is drawing. The entry-point tests assert this tag on the rendered host
 * instead of rebuilding a back stack of their own.
 */
internal fun destinationTag(key: NavKey?): String = "phonxiety:screen:${key ?: "none"}"

private const val WIDE_DP = 600
