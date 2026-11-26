package com.phonxiety.bridge.feature.lab

import com.phonxiety.bridge.net.LanPathProbe
import com.phonxiety.bridge.net.LanPathProber
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The phone's own probe of the daemon's LAN addresses.
 *
 * The daemon can only say that its listener answers on the host it runs on;
 * whether this phone's network reaches it is a question only the phone can
 * answer, so the lab asks it here with link selection's pinned handshake.
 */
fun interface LanPathCheck {
    fun probe(urls: List<String>, certSha256: String?, token: String): List<LanPathProbe>
}

@Module
@InstallIn(SingletonComponent::class)
object LabBindings {
    @Provides
    fun lanPathCheck(): LanPathCheck = LanPathCheck { urls, certSha256, token ->
        LanPathProber.probe(urls, certSha256, token)
    }
}
