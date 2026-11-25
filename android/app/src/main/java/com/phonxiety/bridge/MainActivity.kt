package com.phonxiety.bridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. It owns no screen state: intents become [AppSignal]s, the Navigation 3 host
 * renders the back stack, and every piece of screen state lives in a ViewModel or in the wizard's
 * on-disk journal, so process death and configuration changes do not lose a setup step.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var signal by mutableStateOf<AppSignal?>(null)

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
        private const val PAIR_SCHEME = "phonxiety"
        private const val PAIR_HOST = "pair"
        const val EXTRA_ROUTE = "phonxiety.route"
        const val ROUTE_DOCTOR = "doctor"
    }
}
