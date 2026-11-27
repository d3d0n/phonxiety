package com.phonxiety.bridge

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The navigation invariant every key here obeys:
 *
 * - the four top-level keys — [DashboardKey], [CallsKey], [LabKey], [SettingsKey] — are the roots
 *   of the section stacks ([Section]); each stack starts at its own root and never holds another
 *   section's root;
 * - every other key is pushed onto the stack of the section it was opened from, so back returns
 *   to that stack and a tab switch keeps it;
 * - [OnboardingKey], [LiveCallKey] and [ComposerKey] are full-screen flows and hide the
 *   navigation suite; everything else keeps it visible;
 * - a key restored by [androidx.navigation3.runtime.rememberNavBackStack] must be registered in
 *   the host's saved-state configuration and keep its class name in the R8 rules.
 *
 * Dashboard: the live link, call, audio and device state.
 */
@Serializable
data object DashboardKey : NavKey

/** Calls: history with its filters, search and the cache behind it. */
@Serializable
data object CallsKey : NavKey

/** The test lab: link test, loopback, tone, codec trials and the test call. */
@Serializable
data object LabKey : NavKey

/** Settings and auto-tune. */
@Serializable
data object SettingsKey : NavKey

/**
 * The setup wizard. It carries the pairing link when the route was opened from a
 * `phonxiety://pair?...` intent, so the wizard can validate that exact payload. It is the
 * Dashboard section's setup flow: pairing and the setup reminder bring that tab forward.
 */
@Serializable
data class OnboardingKey(val pairLink: String? = null) : NavKey

/** Doctor: the self-check and guided recovery. */
@Serializable
data object DoctorKey : NavKey

/** Updates: app, module and daemon versions with their own actions. */
@Serializable
data object UpdatesKey : NavKey

/** The live call for one id; works for calls placed from the computer as well. */
@Serializable
data class LiveCallKey(val callId: String) : NavKey

/** One call's record, transcript, recording and actions. */
@Serializable
data class CallDetailKey(val callId: String) : NavKey

/**
 * A call placed from the phone. The prefill is what "call again" carries over; every field is
 * still editable and revalidated before anything is dialled.
 */
@Serializable
data class ComposerKey(
    val destination: String? = null,
    val goal: String? = null,
    val context: String? = null,
    val constraints: String? = null
) : NavKey
