package com.phonxiety.bridge.device.di

import android.content.Context
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.PhonePreferences
import com.phonxiety.bridge.PhoneSettingsStore
import com.phonxiety.bridge.model.AppGatewaySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Device-layer bindings: the service-owned app gateway, looked up at call time. */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides
    @Singleton
    fun appGatewaySource(): AppGatewaySource = AppGatewaySource { BridgeRuntime.appGateway() }

    /** The phone-owned preferences: DataStore for the UI, the same instance for the engine. */
    @Provides
    @Singleton
    fun phonePreferences(@ApplicationContext context: Context): PhonePreferences = PhoneSettingsStore.of(context)
}
