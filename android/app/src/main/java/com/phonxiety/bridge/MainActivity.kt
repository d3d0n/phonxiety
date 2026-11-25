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
import com.phonxiety.bridge.feature.livecall.CallFrontReturn
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. It owns no screen state: intents become [AppSignal]s, the Navigation 3 host
 * renders the back stack, and every piece of screen state lives in a ViewModel or in the wizard's
 * on-disk journal, so process death and configuration changes do not lose a setup step.
 *
 * The one-shot return over Telecom's in-call screen after a call placed here is
 * [CallFrontReturn]; the lifecycle simply forwards to it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var signal by mutableStateOf<AppSignal?>(null)
    private val handler = Handler(Looper.getMainLooper())

    private val callFront = CallFrontReturn(
        handler = handler,
        call = { BridgeRuntime.state.value.call },
        screenInteractive = { getSystemService(PowerManager::class.java)?.isInteractive != false },
        openFront = ::bringToFront
    )

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
        callFront.onStart()
    }

    override fun onResume() {
        super.onResume()
        callFront.onResume()
    }

    override fun onStop() {
        super.onStop()
        callFront.onStop(canReturn = !isFinishing && !isChangingConfigurations)
    }

    override fun onDestroy() {
        callFront.onDestroy()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        callFront.onUserLeaveHint()
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

        const val EXTRA_ROUTE = "phonxiety.route"
        const val ROUTE_DOCTOR = "doctor"
    }
}
