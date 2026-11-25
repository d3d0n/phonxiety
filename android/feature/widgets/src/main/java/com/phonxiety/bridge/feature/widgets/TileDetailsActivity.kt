package com.phonxiety.bridge.feature.widgets

import android.app.Activity
import android.os.Bundle

/**
 * The long-press destination for both tiles. The system resolves this activity through the
 * `QS_TILE_PREFERENCES` filter; it opens the app and finishes without drawing anything.
 */
class TileDetailsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(openAppIntent()) }
        finish()
    }
}
