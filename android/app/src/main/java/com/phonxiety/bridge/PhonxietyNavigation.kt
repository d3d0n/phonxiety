package com.phonxiety.bridge

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.phonxiety.bridge.feature.calls.CallDetailRoute
import com.phonxiety.bridge.feature.calls.CallsRoute
import com.phonxiety.bridge.feature.dashboard.DashboardRoute
import com.phonxiety.bridge.feature.doctor.DoctorRoute
import com.phonxiety.bridge.feature.lab.LabRoute
import com.phonxiety.bridge.feature.livecall.ComposerIntent
import com.phonxiety.bridge.feature.livecall.ComposerRoute
import com.phonxiety.bridge.feature.livecall.LiveCallRoute
import com.phonxiety.bridge.feature.onboarding.OnboardingRoute
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import com.phonxiety.bridge.feature.settings.SettingsRoute
import com.phonxiety.bridge.feature.updates.UpdatesRoute
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** What an intent asked the app to do; the activity turns intents into these and nothing else. */
sealed interface AppSignal {
    data class Pair(val link: String) : AppSignal

    /** A notification tap that lands on the dashboard. */
    data object Dashboard : AppSignal

    /** The live call card's tap, for one call. */
    data class LiveCall(val callId: String) : AppSignal

    data object Doctor : AppSignal

    /** The setup reminder's tap. */
    data object Setup : AppSignal
}

/** One top-level destination: what the navigation suite draws and where it leads. */
private data class TopLevel(
    val key: NavKey,
    @StringRes val label: Int,
    val icon: ImageVector
)

private val TOP_LEVEL = listOf(
    TopLevel(DashboardKey, R.string.nav_home, Icons.Filled.Home),
    TopLevel(CallsKey, R.string.nav_calls, Icons.Filled.Call),
    TopLevel(LabKey, R.string.nav_lab, Icons.Filled.Build),
    TopLevel(SettingsKey, R.string.nav_settings, Icons.Filled.Settings)
)

/**
 * The Navigation 3 host: the wizard until setup is Ready, then the four top-level destinations in
 * a navigation suite — a bar on a phone, a rail on a large or landscape screen — with the live
 * call, a call's record, the composer, Doctor and Updates pushed on top of whichever section they
 * were opened from.
 */
@Composable
fun PhonxietyNavigationHost(
    signal: AppSignal?,
    onSignalConsumed: () -> Unit
) {
    val context = LocalContext.current
    val start = remember {
        if (SetupPreferences(context).ready) DashboardKey else OnboardingKey()
    }
    val configuration = remember {
        SavedStateConfiguration {
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
    }
    val backStack = rememberNavBackStack(configuration, start)

    LaunchedEffect(signal) {
        val current = signal ?: return@LaunchedEffect
        backStack.openSignal(current)
        onSignalConsumed()
    }

    val activity = context as? android.app.Activity
    val onBack: () -> Unit = {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        } else {
            activity?.finish()
        }
    }
    val current = backStack.lastOrNull()
    val currentTopLevel = TOP_LEVEL.firstOrNull { it.key == current }
    val lastSection = TOP_LEVEL.lastOrNull { top ->
        backStack.any { it == top.key }
    } ?: TOP_LEVEL.first()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TOP_LEVEL.forEach { destination ->
                item(
                    selected = destination.key == (currentTopLevel ?: lastSection).key,
                    onClick = { backStack.openSection(destination.key) },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(destination.label)) }
                )
            }
        },
        layoutType = if (currentTopLevel != null) navigationSuiteType() else NavigationSuiteType.None
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier
                .fillMaxSize()
                .testTag(destinationTag(backStack.lastOrNull())),
            onBack = onBack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<OnboardingKey> { key ->
                    OnboardingRoute(
                        initialPairLink = key.pairLink,
                        onFinished = { backStack.showDashboard() },
                        onOpenDoctor = { backStack.push(DoctorKey) }
                    )
                }
                entry<DashboardKey> {
                    DashboardRoute(
                        onOpenSetup = { backStack.push(OnboardingKey()) },
                        onOpenDoctor = { backStack.push(DoctorKey) },
                        onNewCall = { backStack.push(ComposerKey()) },
                        onOpenLiveCall = { callId -> backStack.push(LiveCallKey(callId)) },
                        onRunLinkTest = { backStack.openSection(LabKey) },
                        onOpenUpdates = { backStack.push(UpdatesKey) }
                    )
                }
                entry<CallsKey> {
                    CallsRoute(
                        onOpenCall = { callId -> backStack.push(CallDetailKey(callId)) },
                        onNewCall = { backStack.push(ComposerKey()) }
                    )
                }
                entry<LabKey> {
                    // A top-level section's own back goes Home, not out of the app.
                    LabRoute(
                        onBack = { backStack.openSection(DashboardKey) },
                        onOpenCall = { callId -> backStack.push(CallDetailKey(callId)) }
                    )
                }
                entry<SettingsKey> {
                    SettingsRoute(onBack = { backStack.openSection(DashboardKey) })
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
                        onOpenDetail = { callId -> backStack.push(CallDetailKey(callId)) }
                    )
                }
                entry<CallDetailKey> { key ->
                    CallDetailRoute(
                        callId = key.callId,
                        onBack = onBack,
                        onCallAgain = { brief ->
                            backStack.push(
                                ComposerKey(
                                    destination = brief.destination,
                                    goal = brief.goal,
                                    context = brief.context,
                                    constraints = brief.constraints
                                )
                            )
                        },
                        onOpenLive = { callId -> backStack.push(LiveCallKey(callId)) }
                    )
                }
                entry<ComposerKey> { key ->
                    ComposerRoute(
                        onBack = onBack,
                        onPlaced = { callId ->
                            // The composer is replaced by the call it placed: back returns to the list.
                            backStack[backStack.lastIndex] = LiveCallKey(callId)
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
        )
    }
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

/**
 * The destination the host is drawing. The entry-point tests assert this tag on the rendered host
 * instead of rebuilding a back stack of their own.
 */
internal fun destinationTag(key: NavKey?): String = "phonxiety:screen:${key ?: "none"}"

/** The one place an [AppSignal] becomes a screen, shared by a cold start and `onNewIntent`. */
internal fun MutableList<NavKey>.openSignal(signal: AppSignal) {
    when (signal) {
        is AppSignal.Pair -> {
            if (lastOrNull() is OnboardingKey) {
                this[lastIndex] = OnboardingKey(signal.link)
            } else {
                add(OnboardingKey(signal.link))
            }
        }

        AppSignal.Dashboard -> showDashboard()

        is AppSignal.LiveCall -> {
            val key = LiveCallKey(signal.callId)
            if (lastOrNull() != key) {
                // A tap brings that call's screen to the top instead of stacking a second copy.
                indexOf(key).takeIf { it >= 0 }?.let { removeAt(it) }
                add(key)
            }
        }

        AppSignal.Doctor -> if (lastOrNull() !is DoctorKey) add(DoctorKey)

        AppSignal.Setup -> if (lastOrNull() !is OnboardingKey) add(OnboardingKey())
    }
}

private fun NavBackStack<NavKey>.push(key: NavKey) {
    if (lastOrNull() == key) return
    add(key)
}

/** A section tap brings that section's root to the top instead of stacking duplicates. */
private fun NavBackStack<NavKey>.openSection(key: NavKey) {
    val existing = indexOf(key)
    if (existing >= 0) {
        while (size > existing + 1) removeAt(lastIndex)
    } else {
        clear()
        add(key)
    }
}

private fun MutableList<NavKey>.showDashboard() {
    if (lastOrNull() is DashboardKey) return
    clear()
    add(DashboardKey)
}

private const val WIDE_DP = 600
