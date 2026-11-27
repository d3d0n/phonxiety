package com.phonxiety.bridge.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The one place a list's "scroll back to the top" action is held. A screen registers the action for
 * its own destination while it is composed; the navigation chrome dispatches a re-tap on the
 * current destination. The registry is a plain map on purpose: registration is not state to
 * recompose, only a callback to call.
 *
 * A key identifies a destination, so re-registering it replaces the previous action and a dispatch
 * with nothing registered does nothing. Nothing here is global: [LocalScrollToTop] hands out one
 * registry per provider, and the default is a fresh empty one.
 */
@Stable
class ScrollToTopRegistry {
    private val actions = mutableMapOf<Any, () -> Unit>()

    /** Holds [scrollToTop] for [destination]; a second registration for the same key replaces it. */
    fun register(destination: Any, scrollToTop: () -> Unit) {
        actions[destination] = scrollToTop
    }

    /**
     * Drops [destination]'s action, unless [scrollToTop] has since been replaced by a newer one.
     * Comparing the function keeps two screens that register the same key in sequence safe: the
     * one disposing cannot remove the other's action.
     */
    fun unregister(destination: Any, scrollToTop: () -> Unit) {
        if (actions[destination] === scrollToTop) actions.remove(destination)
    }

    /** Runs [destination]'s action; a destination with no registration is a no-op. */
    fun dispatch(destination: Any) {
        actions[destination]?.invoke()
    }

    internal fun isEmpty(): Boolean = actions.isEmpty()
}

/** The registry of the current navigation host; [ScrollToTopRegistry] with an empty default. */
val LocalScrollToTop = staticCompositionLocalOf { ScrollToTopRegistry() }

/**
 * Registers [scrollToTop] for [destination] for as long as the call is composed, and removes it on
 * disposal. A list screen calls this once with its own key; the navigation chrome only dispatches.
 */
@Composable
fun RegisterScrollToTop(destination: Any, scrollToTop: () -> Unit) {
    val registry = LocalScrollToTop.current
    DisposableEffect(registry, destination, scrollToTop) {
        registry.register(destination, scrollToTop)
        onDispose { registry.unregister(destination, scrollToTop) }
    }
}
