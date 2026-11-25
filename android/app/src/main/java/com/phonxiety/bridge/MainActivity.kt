package com.phonxiety.bridge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.feature.livecall.LiveCallFront
import com.phonxiety.bridge.model.CallPhase
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. It owns no screen state: intents become [AppSignal]s, the Navigation 3 host
 * renders the back stack, and every piece of screen state lives in a ViewModel or in the wizard's
 * on-disk journal, so process death and configuration changes do not lose a setup step.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var signal by mutableStateOf<AppSignal?>(null)
    private val handler = Handler(Looper.getMainLooper())

    /** Set between [onUserLeaveHint] and the next resume: the user walked away on purpose. */
    private var userLeft = false

    /** The call phase this activity has already come back for; each phase is returned to once. */
    private var returnedForPhase: String? = null

    /** How many more times the return may wait for the placement reply. */
    private var returnAttemptsLeft = 0

    private val returnTick = Runnable { attemptReturn() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            PhonxietyTheme {
                PhonxietyNavigationHost(
                    signal = signal,
                    onSignalConsumed = { signal = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // A stored pairing always implies a link service; after a force-stop or an update the
        // process can be gone while the pairing is still on disk, and the dashboard's state and the
        // wizard's first link test both need the engine running.
        if (BridgeConfigLoader.loadOrNull(this) != null && BridgeRuntime.engine == null) {
            BridgeService.start(this)
        }
        val placed = LiveCallFront.placedCallId
        if (placed != null && BridgeRuntime.state.value.call?.let { it.callId == placed && it.phase == CallPhase.ENDED } == true) {
            LiveCallFront.forget(placed)
            returnedForPhase = null
        }
    }

    override fun onResume() {
        super.onResume()
        userLeft = false
        handler.removeCallbacks(returnTick)
    }

    override fun onStop() {
        super.onStop()
        scheduleReturn()
    }

    override fun onDestroy() {
        handler.removeCallbacks(returnTick)
        super.onDestroy()
    }

    /** Home and Recents; the system in-call screen appearing is not a user leave. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeft = true
    }

    /**
     * Telecom's in-call screen is an activity drawn above every app, so a call placed from this
     * phone covers the live call. Starting an activity from the background is restricted, and the
     * one route that fits is the app-switch grace window: a start that follows this activity's own
     * stop counts as the caller still being in a foreground task, which is why the attempt happens
     * here rather than from a service. [onUserLeaveHint] tells the two covers apart: it fires for
     * Home and Recents, never when another app or the system takes the foreground, so the screen
     * comes back only when the in-call UI covered it. At most once per phase — dialing and
     * connected — so the user can walk away afterwards, and a call the computer placed is never
     * pulled forward.
     *
     * The delay covers the placement reply: the daemon answers the composer only after Telecom has
     * already drawn its screen, so the call is marked as this phone's a moment after this stop.
     */
    private fun scheduleReturn() {
        if (isFinishing || isChangingConfigurations) return
        returnAttemptsLeft = RETURN_ATTEMPTS
        handler.removeCallbacks(returnTick)
        handler.postDelayed(returnTick, RETURN_DELAY_MS)
    }

    private fun attemptReturn() {
        if (isFinishing || isDestroyed || userLeft) return
        val placed = LiveCallFront.placedCallId
        val call = BridgeRuntime.state.value.call
        if (placed == null) {
            // A computer-placed call never gets the marker; give the reply a few ticks to land.
            if (call != null && call.phase != CallPhase.ENDED) retryReturn()
            return
        }
        if (call == null || call.callId != placed) {
            retryReturn()
            return
        }
        if (call.phase == CallPhase.ENDED) {
            LiveCallFront.forget(placed)
            returnedForPhase = null
            return
        }
        // The dialer can take the foreground again when the call starts and when it connects.
        // Both are covers worth coming back from; ending, or a phase already returned for, is not.
        if (call.phase != CallPhase.DIALING && call.phase != CallPhase.ACTIVE) return
        val phaseKey = "${call.callId}:${call.phase.name}"
        if (returnedForPhase == phaseKey) return
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) return
        returnedForPhase = phaseKey
        bringToFront()
    }

    private fun retryReturn() {
        if (returnAttemptsLeft <= 0) return
        returnAttemptsLeft--
        handler.postDelayed(returnTick, RETURN_RETRY_MS)
    }

    private fun bringToFront() {
        if (isFinishing || isDestroyed) return
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: RuntimeException) {
            // A blocked background start is reported as an ActivityTaskManager log line, not as a
            // throwable; a security refusal is the only case that lands here.
            Log.w(TAG, "the live call could not return to the front: ${e.javaClass.simpleName}")
        }
    }

    /**
     * `phonxiety://pair?...` goes to the wizard (which validates it through the pairing repository);
     * `phonxiety.route=doctor` opens the Doctor entry. The intent data is cleared so a configuration
     * change cannot replay it.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        when {
            intent.action == Intent.ACTION_VIEW &&
                data?.scheme == PAIR_SCHEME &&
                data.host == PAIR_HOST -> {
                signal = AppSignal.Pair(data.toString())
                intent.data = null
            }

            intent.getStringExtra(EXTRA_ROUTE) == ROUTE_DOCTOR -> {
                signal = AppSignal.Doctor
                intent.removeExtra(EXTRA_ROUTE)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PAIR_SCHEME = "phonxiety"
        private const val PAIR_HOST = "pair"

        /** Long enough for the in-call screen to settle, short enough to still count as a cover. */
        private const val RETURN_DELAY_MS = 400L

        /** The placement reply can be a link round-trip behind the system screen. */
        private const val RETURN_RETRY_MS = 300L
        private const val RETURN_ATTEMPTS = 10
        const val EXTRA_ROUTE = "phonxiety.route"
        const val ROUTE_DOCTOR = "doctor"
    }
}
