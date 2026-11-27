package com.phonxiety.bridge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.feature.livecall.CallFrontReturn
import com.phonxiety.bridge.feature.onboarding.SetupPreferences
import com.phonxiety.bridge.os.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The single activity, and the app's only edge-to-edge owner: it calls [enableEdgeToEdge] once and
 * no screen touches the window's decor. It owns no screen state either: intents become
 * [AppSignal]s, the Navigation 3 host renders the per-section back stacks, and every piece of
 * screen state lives in a ViewModel or in the wizard's on-disk journal, so process death and
 * configuration changes do not lose a setup step.
 *
 * Startup is the splash theme's job: the wizard's journal is read off-composition behind a splash
 * that is held for at most [StartDestinationController.SPLASH_DEADLINE_MS], so the first frame
 * never waits on a disk read and a slow device still renders.
 *
 * The returns over Telecom's in-call screen after a call placed here are [CallFrontReturn]; the
 * lifecycle simply forwards to it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var signal by mutableStateOf<AppSignal?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val startDestination = StartDestinationController()

    /** The link strip above the navigation suite, alive across tab switches and rotations. */
    private val linkStatus: LinkStatusViewModel by viewModels()

    /** The screen request the last intent carried; the navigation host consumes it once. */
    internal val pendingSignal: AppSignal?
        get() = signal

    private val callFront = CallFrontReturn(
        handler = handler,
        call = { BridgeRuntime.state.value.call },
        screenInteractive = { getSystemService(PowerManager::class.java)?.isInteractive != false },
        anotherCall = { TelecomCalls.anotherCallVisible() },
        openFront = ::bringToFront
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash is installed before the window exists, so the switch from the starting theme
        // to the app theme happens without a frame of the wrong background.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { startDestination.keepsSplash() }
        enableEdgeToEdge()
        handleIntent(intent)
        resolveStart()
        setContent {
            PhonxietyTheme {
                val start by startDestination.start.collectAsStateWithLifecycle()
                val strip by linkStatus.state.collectAsStateWithLifecycle()
                // Until the journal answers there is nothing to draw: the splash covers the gap
                // and the deadline guarantees the answer arrives.
                start?.let { destination ->
                    PhonxietyNavigationHost(
                        signal = signal,
                        start = destination,
                        strip = strip,
                        onSignalConsumed = { signal = null }
                    )
                }
            }
        }
    }

    /**
     * Reads the wizard's journal once, outside composition, on the controller's worker. The
     * controller bounds it against a main-thread deadline, so a preference load that blocks its
     * thread still renders the dashboard by [StartDestinationController.SPLASH_DEADLINE_MS].
     */
    private fun resolveStart() {
        lifecycleScope.launch {
            startDestination.resolve { SetupPreferences(applicationContext).ready }
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
        if (BridgeConfigLoader.loadOrNull(this) != null && BridgeRuntime.appGateway() == null) {
            BridgeService.start(this)
        }
        callFront.onStart()
    }

    override fun onResume() {
        super.onResume()
        callFront.onResume()
    }

    override fun onPause() {
        super.onPause()
        // The cover pauses this activity before it stops it, and on the phone the placement cover
        // was followed by no stop at all: the pause is the signal the return must not miss.
        callFront.onPause(canReturn = !isFinishing && !isChangingConfigurations)
    }

    override fun onStop() {
        super.onStop()
        callFront.onStop(canReturn = !isFinishing && !isChangingConfigurations)
    }

    override fun onDestroy() {
        callFront.onDestroy(recreating = isChangingConfigurations)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        callFront.onUserLeaveHint()
    }

    private fun bringToFront() {
        if (isFinishing || isDestroyed) {
            BridgeLog.w(TAG, "the live screen could not return: the activity is going away")
            return
        }
        try {
            BridgeLog.i(TAG, "returning the live screen over the system call screen")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: RuntimeException) {
            // A blocked background start is reported as an ActivityTaskManager log line, not as a
            // throwable; a security refusal is the only case that lands here.
            BridgeLog.w(TAG, "the live screen could not return to the front: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Every entry point asks for a screen through [signalFor]: a pairing link for the wizard, and
     * the notification routes for the dashboard, the live call, the Doctor and the setup wizard.
     * The intent is cleared so a configuration change cannot replay it.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        signal = signalFor(intent) ?: return
        intent.data = null
        intent.removeExtra(Notifications.EXTRA_ROUTE)
        intent.removeExtra(Notifications.EXTRA_CALL_ID)
    }

    companion object {
        private const val TAG = "Bridge:callfront"
        private const val PAIR_SCHEME = "phonxiety"
        private const val PAIR_HOST = "pair"

        /**
         * What [intent] asks the app to do, for a cold start and for [onNewIntent] alike. A call
         * route without an ID cannot open a call, so it falls back to the dashboard.
         */
        fun signalFor(intent: Intent): AppSignal? {
            val data = intent.data
            if (intent.action == Intent.ACTION_VIEW && data?.scheme == PAIR_SCHEME && data.host == PAIR_HOST) {
                return AppSignal.Pair(data.toString())
            }
            return when (intent.getStringExtra(Notifications.EXTRA_ROUTE)) {
                Notifications.ROUTE_CALL ->
                    intent.getStringExtra(Notifications.EXTRA_CALL_ID)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { AppSignal.LiveCall(it) }
                        ?: AppSignal.Dashboard

                Notifications.ROUTE_SETUP -> AppSignal.Setup

                Notifications.ROUTE_DASHBOARD -> AppSignal.Dashboard

                Notifications.ROUTE_DOCTOR -> AppSignal.Doctor

                else -> null
            }
        }
    }
}
