package com.phonxiety.bridge.feature.doctor

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatcher Doctor's device reads run on; tests swap it for the test scheduler. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DoctorDispatcher

/** The device-backed seams Doctor injects; tests replace them, the app gets these. */
@Module
@InstallIn(SingletonComponent::class)
object DoctorBindings {
    @Provides
    @Singleton
    fun doctorSource(@ApplicationContext context: Context): DoctorSource = AndroidDoctorSource(context)

    @Provides
    @Singleton
    fun doctorServiceControl(@ApplicationContext context: Context): DoctorServiceControl = AndroidDoctorServiceControl(context)

    @Provides
    @Singleton
    fun doctorChangeLog(@ApplicationContext context: Context): DoctorChangeLog = FileDoctorChangeLog(File(context.filesDir, "doctor-changes.log"))

    @Provides
    @Singleton
    @DoctorDispatcher
    fun doctorDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
