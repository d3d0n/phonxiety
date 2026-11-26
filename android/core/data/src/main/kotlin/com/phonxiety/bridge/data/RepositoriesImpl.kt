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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
 * "Unpaired" until the process was killed. [paired] is therefore an ordinary cached flow — reading
 * it never touches storage — and the cache is re-read from storage on the IO dispatcher at the
 * points where the file may have moved: on every [refresh] a caller asks for (the wizard's probe on
 * start and after returning to the app) and when the bridge service publishes a new link state, the
 * moment it has reloaded the config. Nothing polls.
 */
@Singleton
class DevicePairingRepository internal constructor(
    private val context: Context,
    private val read: () -> Boolean
) : PairingRepository {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        { BridgeConfigLoader.loadOrNull(context) != null }
    )

    private val state = MutableStateFlow(read())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val paired: StateFlow<Boolean> = state.asStateFlow()

    init {
        scope.launch {
            // A service start reloads config.json and then attaches its engine, which is the only
            // signal the app process gets about a config written by the host task. The first
            // emission is the state the constructor has already read.
            BridgeRuntime.state
                .map { it.link }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
    }

    /** Re-reads the stored config on the IO dispatcher; the cached value moves only when it changed. */
    suspend fun refresh() = withContext(Dispatchers.IO) { state.value = read() }

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
}
