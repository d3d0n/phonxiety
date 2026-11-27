package com.phonxiety.bridge.data.di

import android.content.Context
import androidx.room.Room
import com.phonxiety.bridge.data.calls.CallCacheDatabase
import com.phonxiety.bridge.data.calls.CallsRepository
import com.phonxiety.bridge.data.calls.DefaultCallsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The call cache: one database for the process, its two tables, one repository. */
@Module
@InstallIn(SingletonComponent::class)
object CallCacheModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): CallCacheDatabase = Room.databaseBuilder(context, CallCacheDatabase::class.java, "calls.db")
        // The cache is a copy of the daemon's records, so any version mismatch — an upgrade or a
        // downgrade — drops every table and refetches; losing it costs a refresh, never data.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CallsModule {
    @Binds
    @Singleton
    abstract fun callsRepository(impl: DefaultCallsRepository): CallsRepository
}
