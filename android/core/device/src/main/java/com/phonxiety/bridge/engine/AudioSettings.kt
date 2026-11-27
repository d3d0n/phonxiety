package com.phonxiety.bridge.engine

import com.phonxiety.bridge.PhoneCodec
import com.phonxiety.bridge.PhoneSettings

/**
 * The codec and bitrate the daemon asks for on the next call.  Written by the
 * stored preferences and by `set_prefs`, read by `start_audio` and the hello.
 */
internal class AudioSettings {
    @Volatile var preferredCodec: String = PhoneCodec.AUTO

    @Volatile var maxBitrate: Int = PhoneSettings.DEFAULT_MAX_BITRATE
}
