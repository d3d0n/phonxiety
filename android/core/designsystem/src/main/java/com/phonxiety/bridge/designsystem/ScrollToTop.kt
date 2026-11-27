package com.phonxiety.bridge.designsystem

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * A handle to one registration. Two registrations of the same destination with the same callback
 * are still distinct, so disposing one never removes the other.
 */
@Stable
class ScrollToTopRegistration internal constructor(internal val destination: Any, internal val id: Long)

/**
 * The one place a list's "scroll back to the top" action is held. A screen registers the action for
 * its own destination while it is composed; the navigation chrome dispatches a re-tap on the
 * current destination. The registry is a plain map on purpose: registration is not state to
 * recompose, only a callback to call.
 *
 * There is no global default: the navigation host creates one with [rememberScrollToTopRegistry]
 * and provides it. A dispatch with nothing registered does nothing.
 */
@Stable
class ScrollToTopRegistry {
    private class Entry(val id: Long, val action: () -> Unit)

    private val actions = mutableMapOf<Any, Entry>()
    private var nextId = 0L

    /**
     * Holds [scrollToTop] for [destination]; a second registration for the same destination
     * replaces the previous one. Returns the handle that removes exactly this registration.
     */
    fun register(destination: Any, scrollToTop: () -> Unit): ScrollToTopRegistration {
        val handle = ScrollToTopRegistration(destination, nextId++)
        actions[destination] = Entry(handle.id, scrollToTop)
        return handle
    }

    /**
     * Drops [handle]'s action unless a newer registration has since replaced it, so a screen that
     * disposes after another screen claimed the destination cannot remove the newer action.
     */
    fun unregister(handle: ScrollToTopRegistration) {
        if (actions[handle.destination]?.id == handle.id) actions.remove(handle.destination)
    }

    /** Runs [destination]'s action; a destination with no registration is a no-op. */
    fun dispatch(destination: Any) {
        actions[destination]?.action?.invoke()
    }

    internal fun isEmpty(): Boolean = actions.isEmpty()
}

/** One registry per navigation host, remembered across recompositions. */
@Composable
fun rememberScrollToTopRegistry(): ScrollToTopRegistry = remember { ScrollToTopRegistry() }

/** The registry of the current navigation host; `null` until the host provides one. */
val LocalScrollToTop = staticCompositionLocalOf<ScrollToTopRegistry?> { null }

/**
 * Registers [scrollToTop] for [destination] for as long as the call is composed, and removes it on
 * disposal. The effect is keyed on the registry and the destination only: a screen whose callback
 * changes keeps its registration, and an older still-composed screen cannot reclaim the destination
 * by changing its callback. The newest callback is read through a stable wrapper.
 *
 * A host that forgot to provide [LocalScrollToTop] fails loudly in a debuggable build; in release
 * the call is a no-op so a missing host degrades instead of crashing.
 */
@Composable
fun RegisterScrollToTop(destination: Any, scrollToTop: () -> Unit) {
    val registry = LocalScrollToTop.current
    if (registry == null) {
        if (LocalContext.current.isDebuggable()) {
            error("RegisterScrollToTop needs LocalScrollToTop provided by the navigation host")
        }
        return
    }
    val latest = rememberUpdatedState(scrollToTop)
    DisposableEffect(registry, destination) {
        val handle = registry.register(destination) { latest.value() }
        onDispose { registry.unregister(handle) }
    }
}

private fun android.content.Context.isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
