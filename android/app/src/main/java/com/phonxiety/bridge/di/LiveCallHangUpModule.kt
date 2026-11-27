package com.phonxiety.bridge.di

import android.content.Context
import com.phonxiety.bridge.feature.widgets.CallEnd
import com.phonxiety.bridge.model.LiveCallHangUp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the one end-of-call path for every surface that can hang up: the notification, the tile
 * and the live call screen. It never depends on the daemon's `/info`, so a live call is always
 * endable.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveCallHangUpModule {
    @Provides
    @Singleton
    fun liveCallHangUp(@ApplicationContext context: Context): LiveCallHangUp = LiveCallHangUp {
        CallEnd.hangUp(context)
    }
}
