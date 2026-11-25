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
        // The widget and the tiles render from the bridge state; this is the one collector, and it
        // is started here so a state change reaches the launcher even when the app has no activity.
        WidgetUpdates.start(this)
    }
}
