package com.phonxiety.bridge

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Process entry point.  Hilt builds the application component here; the bridge
 * services and, later, view models and workers inject from it.
 */
@HiltAndroidApp
class PhonxietyApp : Application()
