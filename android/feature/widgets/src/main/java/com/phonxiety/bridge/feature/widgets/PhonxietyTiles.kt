package com.phonxiety.bridge.feature.widgets

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Phonxiety link": the dot carries the link state and the subtitle the path and RTT. A tap opens
 * the dashboard; a long press opens the app through [TileDetailsActivity]. Nothing is dialled or
 * probed here — the tile reads the same state the dashboard does.
 */
class LinkTileService : TileService() {
    override fun onStartListening() {
        updateTileState()
    }

    override fun onClick() {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val paired = runCatching { BridgeConfigLoader.loadOrNull(this) != null }.getOrDefault(false)
        val link = BridgeRuntime.state.value.link
        tile.label = getString(R.string.tile_link_name)
        tile.subtitle = when {
            !paired -> getString(R.string.tile_link_unpaired)

            link is LinkState.Up -> buildString {
                append(if (link.path == PathKind.DIRECT) "Direct" else "Relay")
                link.rttMs?.let { append(" · $it ms") }
            }

            link is LinkState.Connecting -> getString(R.string.tile_link_connecting)

            else -> getString(R.string.tile_link_offline)
        }
        tile.state = when {
            !paired -> Tile.STATE_UNAVAILABLE
            link is LinkState.Up -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.contentDescription = "${getString(R.string.tile_link_name)}: ${tile.subtitle}"
        tile.updateTile()
    }
}

/**
 * "Hang up": active only while the bridge owns a call. A tap ends it through the daemon's `hangup`
 * and falls back to the Telecom call the engine supervises.
 */
class HangupTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        updateTileState()
    }

    override fun onClick() {
        if (BridgeRuntime.state.value.call == null) {
            updateTileState()
            return
        }
        val appContext = applicationContext
        scope.launch { CallEnd.hangUp(appContext) }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val call = BridgeRuntime.state.value.call?.takeIf { it.phase != CallPhase.ENDED }
        tile.label = getString(R.string.tile_hangup_name)
        tile.state = if (call != null) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = if (call != null) call.destinationMasked else getString(R.string.tile_hangup_idle)
        tile.contentDescription = "${getString(R.string.tile_hangup_name)}: ${tile.subtitle}"
        tile.updateTile()
    }
}
