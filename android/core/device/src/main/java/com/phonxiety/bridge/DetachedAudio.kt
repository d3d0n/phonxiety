package com.phonxiety.bridge

import com.phonxiety.bridge.audio.PhoneAudio

/**
 * Closes a detached stream's audio and clears its numbers, after the engine lock is released.
 * The stream is gone for good: a later cleanup of the same call finds nothing to reset, and a
 * publish still in flight for it is refused by the store.
 */
internal fun releaseDetachedAudio(
    store: EngineStateStore,
    detached: AttachedAudio?,
    close: (PhoneAudio?) -> Unit
) {
    close(detached?.audio)
    detached?.let { store.audioReset(it.stream) }
}
