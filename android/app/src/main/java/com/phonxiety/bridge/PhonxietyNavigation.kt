package com.phonxiety.bridge

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

    data object Doctor : AppSignal
}

/** One top-level destination: what the navigation suite draws and where it leads. */
private data class TopLevel(
    val key: NavKey,
    val label: String,
    val icon: ImageVector
)

private val TOP_LEVEL = listOf(
    TopLevel(DashboardKey, "Home", Icons.Filled.Home),
    TopLevel(CallsKey, "Calls", Icons.Filled.Call),
    TopLevel(LabKey, "Lab", Icons.Filled.Build),
    TopLevel(SettingsKey, "Settings", Icons.Filled.Settings)
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
        when (val current = signal) {
            is AppSignal.Pair -> {
                val last = backStack.lastOrNull()
                if (last is OnboardingKey) {
                    backStack[backStack.lastIndex] = OnboardingKey(current.link)
                } else {
                    backStack.add(OnboardingKey(current.link))
                }
                onSignalConsumed()
            }

            AppSignal.Doctor -> {
                if (backStack.lastOrNull() !is DoctorKey) backStack.add(DoctorKey)
                onSignalConsumed()
            }

            null -> Unit
        }
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
                    label = { Text(destination.label) }
                )
            }
        },
        layoutType = if (currentTopLevel != null) navigationSuiteType() else NavigationSuiteType.None
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
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
                    LabRoute(onBack = { backStack.openSection(DashboardKey) })
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

private fun NavBackStack<NavKey>.showDashboard() {
    if (lastOrNull() is DashboardKey) return
    clear()
    add(DashboardKey)
}

private const val WIDE_DP = 600
