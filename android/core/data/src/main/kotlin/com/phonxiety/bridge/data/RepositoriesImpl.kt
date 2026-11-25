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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class RuntimeBridgeStateRepository @Inject constructor() : BridgeStateRepository {
    override val state: StateFlow<BridgeState> = BridgeRuntime.state
}

/**
 * Pairing lives in device-protected storage ([BridgeConfigLoader]); a stored
 * config starts the service, which then owns the link and the state flow.
 */
@Singleton
class DevicePairingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : PairingRepository {
    private val pairedState = MutableStateFlow(BridgeConfigLoader.loadOrNull(context) != null)

    override val paired: StateFlow<Boolean> = pairedState.asStateFlow()

    override suspend fun pair(link: String): PairResult = withContext(Dispatchers.IO) {
        when (val parsed = PairingLink.parse(link)) {
            is PairingResult.Invalid -> PairResult.Invalid(parsed.error.code)

            is PairingResult.Valid -> {
                try {
                    val stored = BridgeConfigLoader.write(context, parsed.config)
                    BridgeRuntime.rememberPairing(stored.kind)
                    BridgeService.start(context)
                    pairedState.value = true
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
        pairedState.value = false
    }
}
