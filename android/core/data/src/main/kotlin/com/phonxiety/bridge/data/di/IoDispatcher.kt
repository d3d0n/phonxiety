package com.phonxiety.bridge.data.di

import javax.inject.Qualifier

/**
 * The dispatcher for storage, file and other blocking work the data layer owns. Injected rather
 * than referenced directly, so a test can drive the work on its own scheduler and read the result
 * without a real thread hop.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
