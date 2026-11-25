package com.phonxiety.bridge

import android.app.Application
import com.phonxiety.bridge.feature.widgets.WidgetUpdates
import dagger.hilt.android.HiltAndroidApp

/**
 * Process entry point. Hilt builds the application component here; every surface — the activity,
 * the bridge service, the widget and the tiles — injects from it.
 */
@HiltAndroidApp
class PhonxietyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything else can die: keep the uncaught-exception trace and the system's exit
        // reasons, so the Doctor and the diagnostics bundle can explain a crash or an ANR.
        CrashReports.install(this)
        // The widget and the tiles render from the bridge state; this is the one collector, and it
        // is started here so a state change reaches the launcher even when the app has no activity.
        WidgetUpdates.start(this)
    }
}
