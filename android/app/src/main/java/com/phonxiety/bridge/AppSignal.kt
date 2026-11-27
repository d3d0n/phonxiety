package com.phonxiety.bridge

/**
 * What an intent asked the app to do; the activity turns intents into these and nothing else.
 *
 * Every signal names the section it lands in and the exact scope of what it may change. Only the
 * named section's stack is touched; a signal never resets another section, so a detail screen
 * pushed in one tab survives a notification that belongs to another.
 *
 * | Signal      | Section            | Deduplicated within                       | Resets                          | Back goes to                                     |
 * |-------------|--------------------|-------------------------------------------|---------------------------------|--------------------------------------------------|
 * | [Pair]      | Dashboard          | the wizard entry                          | the wizard entry's link         | the entry under the wizard; the Dashboard root ends the activity |
 * | [Dashboard] | Dashboard          | —                                         | the Dashboard stack, to its root | the activity ends (Dashboard is the root)        |
 * | [LiveCall]  | the active section | that section's stack, one screen per call | —                               | the entry under the call                         |
 * | [Doctor]    | the active section | that section's stack                      | —                               | the entry under the Doctor                       |
 * | [Setup]     | Dashboard          | the wizard entry                          | —                               | the entry under the wizard                       |
 *
 * The wizard is the Dashboard section's setup flow: pairing and the setup reminder bring the
 * Dashboard tab forward so the wizard is never stacked over a section's own work. Doctor and the
 * live call are opened over whatever the owner was doing, and back returns there.
 */
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
