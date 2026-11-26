package com.phonxiety.bridge.data

import android.content.Context
import com.phonxiety.bridge.BridgeConfigException
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.BridgeService
import com.phonxiety.bridge.PairingLink
import com.phonxiety.bridge.PairingResult
import com.phonxiety.bridge.model.BridgeState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class RuntimeBridgeStateRepository @Inject constructor() : BridgeStateRepository {
    override val state: StateFlow<BridgeState> = BridgeRuntime.state
}

/**
 * Pairing lives in device-protected storage ([BridgeConfigLoader]); a stored
 * config starts the service, which then owns the link and the state flow.
 *
 * The file is not written only by [pair]: `mise run phone:pair` drops it in through a root shell and
 * the service rewrites it on every start, so a flag captured in the constructor kept the wizard on
 * "Unpaired" until the process was killed. The flag is re-derived from storage whenever it is read,
 * when a collector subscribes, and when the bridge service publishes a new link state (the moment it
 * has reloaded the config). Nothing polls.
 */
@Singleton
class DevicePairingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : PairingRepository {
    private val state = MutableStateFlow(stored())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val paired: StateFlow<Boolean> = StoredPairing(state) { stored() }

    init {
        scope.launch {
            // A service start reloads config.json and then attaches its engine, which is the only
            // signal the app process gets about a config written by the host task.
            BridgeRuntime.state
                .map { it.link }
                .distinctUntilChanged()
                .collect { state.value = stored() }
        }
    }

    override suspend fun pair(link: String): PairResult = withContext(Dispatchers.IO) {
        when (val parsed = PairingLink.parse(link)) {
            is PairingResult.Invalid -> PairResult.Invalid(parsed.error.code)

            is PairingResult.Valid -> {
                try {
                    val stored = BridgeConfigLoader.write(context, parsed.config)
                    BridgeRuntime.rememberPairing(stored.kind)
                    BridgeService.start(context)
                    state.value = true
                    PairResult.Paired
                } catch (e: BridgeConfigException) {
                    PairResult.Invalid(e.code)
                } catch (e: SecurityException) {
                    PairResult.Invalid("pairing_store_failed")
                }
            }
        }
    }

    override suspend fun unpair() = withContext(Dispatchers.IO) {
        BridgeConfigLoader.delete(context)
        BridgeRuntime.forgetPairing()
        BridgeService.stop(context)
        state.value = false
    }

    private fun stored(): Boolean = BridgeConfigLoader.loadOrNull(context) != null
}

/**
 * The stored pairing as a [StateFlow] whose answer is read from disk at the moment it is asked for,
 * while [state] carries the emissions. Reads happen on the caller's dispatcher, so every reader is
 * an IO coroutine (the wizard probe, `Doctor`); collection refreshes once off the main thread.
 */
private class StoredPairing(
    private val state: MutableStateFlow<Boolean>,
    private val read: () -> Boolean
) : StateFlow<Boolean> {
    override val value: Boolean get() = refresh()

    override val replayCache: List<Boolean> get() = listOf(refresh())

    override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
        withContext(Dispatchers.IO) { refresh() }
        return state.collect(collector)
    }

    private fun refresh(): Boolean = read().also { state.value = it }
}
