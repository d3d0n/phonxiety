package com.phonxiety.bridge

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Dashboard: the live link, call, audio and device state. */
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
 * `phonxiety://pair?...` intent, so the wizard can validate that exact payload.
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
