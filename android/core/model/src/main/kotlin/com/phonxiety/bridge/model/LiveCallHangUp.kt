package com.phonxiety.bridge.model

/**
 * The always-available way to end the call on screen, independent of what the daemon's `/info`
 * answers: a bounded daemon attempt, then the Telecom call this device supervises. The live call
 * screen, the notification and the quick-settings tile all end the call through this one path.
 */
fun interface LiveCallHangUp {
    suspend fun hangUp()
}
