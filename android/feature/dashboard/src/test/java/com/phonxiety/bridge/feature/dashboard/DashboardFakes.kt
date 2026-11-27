package com.phonxiety.bridge.feature.dashboard

import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.BridgeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The bridge state a test pins; the dashboard only reads it. */
internal class FakeBridgeStateRepository(state: BridgeState) : BridgeStateRepository {
    private val mutable = MutableStateFlow(state)
    override val state: StateFlow<BridgeState> = mutable
}

/** Pairing as the dashboard sees it, with a count so a test sees the unpair happen. */
internal class FakePairingRepository(pairedNow: Boolean) : PairingRepository {
    private val mutable = MutableStateFlow(pairedNow)
    override val paired: StateFlow<Boolean> = mutable
    var unpairCalls = 0

    override suspend fun pair(link: String): PairResult {
        mutable.value = true
        return PairResult.Paired
    }

    override suspend fun unpair() {
        unpairCalls++
        mutable.value = false
    }
}
