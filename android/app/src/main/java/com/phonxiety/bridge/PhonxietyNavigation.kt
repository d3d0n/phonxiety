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
import androidx.navigation3.runtime.EntryProviderScope
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

    fun providerFor(section: Section): (NavKey) -> NavEntry<NavKey> = entryProvider {
        entryIn<OnboardingKey>(
            section = section,
            metadata = NavDisplay.predictivePopTransitionSpec { wizardPredictivePop() }
        ) { key ->
            OnboardingRoute(
                initialPairLink = key.pairLink,
                onFinished = { navigator.finishSetup() },
                onOpenDoctor = { navigator.push(DoctorKey) }
            )
        }
        entryIn<DashboardKey>(section) {
            DashboardRoute(
                onOpenSetup = { navigator.push(OnboardingKey()) },
                onOpenDoctor = { navigator.push(DoctorKey) },
                onNewCall = { navigator.push(ComposerKey()) },
                onOpenLiveCall = { callId -> navigator.push(LiveCallKey(callId)) },
                onRunLinkTest = { navigator.openSection(Section.LAB) },
                onOpenUpdates = { navigator.push(UpdatesKey) }
            )
        }
        entryIn<CallsKey>(section) {
            CallsRoute(
                onOpenCall = { callId -> navigator.push(CallDetailKey(callId)) },
                onNewCall = { navigator.push(ComposerKey()) },
                onOpenDoctor = { navigator.push(DoctorKey) }
            )
        }
        entryIn<LabKey>(section) {
            LabRoute(
                onBack = onBack,
                onOpenCall = { callId -> navigator.push(CallDetailKey(callId)) },
                onOpenDoctor = { navigator.push(DoctorKey) }
            )
        }
        entryIn<SettingsKey>(section) {
            SettingsRoute(onBack = onBack, onOpenDoctor = { navigator.push(DoctorKey) })
        }
        entryIn<UpdatesKey>(section) {
            UpdatesRoute(onBack = onBack, onOpenDoctor = { navigator.push(DoctorKey) })
        }
        entryIn<DoctorKey>(section) {
            DoctorRoute(onBack = onBack)
        }
        entryIn<LiveCallKey>(section) { key ->
            LiveCallRoute(
                callId = key.callId,
                onBack = onBack,
                onOpenDetail = { callId -> navigator.push(CallDetailKey(callId)) },
                onOpenDoctor = { navigator.push(DoctorKey) }
            )
        }
        entryIn<CallDetailKey>(section) { key ->
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
                onOpenLive = { callId -> navigator.push(LiveCallKey(callId)) },
                onOpenDoctor = { navigator.push(DoctorKey) }
            )
        }
        entryIn<ComposerKey>(section) { key ->
            ComposerRoute(
                onBack = onBack,
                onPlaced = { callId ->
                    // The composer is replaced by the call it placed: back returns to the list.
                    navigator.replaceTop(LiveCallKey(callId))
                },
                onOpenDoctor = { navigator.push(DoctorKey) },
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
    val dashboardEntries = sectionEntries(dashboardStack, providerFor(Section.DASHBOARD))
    val callsEntries = sectionEntries(callsStack, providerFor(Section.CALLS))
    val labEntries = sectionEntries(labStack, providerFor(Section.LAB))
    val settingsEntries = sectionEntries(settingsStack, providerFor(Section.SETTINGS))
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
        // so the host answers back itself where it has somewhere to go: a destination above a
        // root pops, and a non-Dashboard root goes home. At the Dashboard root and at a
        // fresh-install wizard root there is no in-app destination left, so the handler is off and
        // the system draws its predictive exit instead.
        BackHandler(enabled = hostHandlesBack(currentStack, active)) { onBack() }
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
 * host-level check that the provision happened: without it a screen's RegisterScrollToTop would
 * register nothing, and a re-tap would go quiet with no sign of the host that forgot the registry.
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

/**
 * One stack's entries, with the state holder and the ViewModel store scoped to that stack.
 *
 * The entries' content identities must already be namespaced by the section (see [entryIn]):
 * two sections can hold the same destination — the Doctor opened from Dashboard and from Calls,
 * one call's record opened from Calls and from the Lab — and the display keys composition and
 * saveable state by that identity.
 */
@Composable
internal fun sectionEntries(
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

/** The stable content identity of [key] inside [section]; see [entryIn]. */
internal fun contentKeyOf(section: Section, key: NavKey): String = "${section.name}:$key"

/**
 * Registers one destination whose content identity includes [section]. The typed entry is the
 * only place the key is available before the entry is built, so the qualifier is supplied here.
 */
internal inline fun <reified K : NavKey> EntryProviderScope<NavKey>.entryIn(
    section: Section,
    metadata: Map<String, Any> = emptyMap(),
    noinline content: @Composable (K) -> Unit
) {
    entry<K>(clazzContentKey = { key -> contentKeyOf(section, key) }, metadata = metadata, content = content)
}

/**
 * Whether the host answers back for [stack] in [active].
 *
 * A destination above a section's root pops within that section, and a non-Dashboard section root
 * goes to Dashboard. The Dashboard root and a fresh-install wizard root leave the app, so the
 * handler stays disabled there and the system's predictive exit is not swallowed.
 */
internal fun hostHandlesBack(stack: List<NavKey>, active: Section): Boolean = when {
    stack.size > 1 -> true
    stack.firstOrNull() is OnboardingKey -> false
    active == Section.DASHBOARD -> false
    else -> true
}

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
