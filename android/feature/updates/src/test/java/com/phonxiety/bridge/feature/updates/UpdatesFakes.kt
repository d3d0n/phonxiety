package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateManifest
import com.phonxiety.bridge.data.updates.UpdatesService
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A scripted update service: every call is counted and nothing leaves the process. */
internal class FakeUpdatesService : UpdatesService {
    var result: UpdateCheck = UpdatesSamples.check()
    var checked = 0
    var installedManifest: UpdateManifest? = null
    var appInstalls = 0
    var moduleInstalls = 0
    var rollbackCalls = 0

    override val appInstall = MutableStateFlow<AppInstallState>(AppInstallState.Idle)
    override val moduleInstall = MutableStateFlow<ModuleInstallState>(ModuleInstallState.Idle)
    override val rollbackPlan = MutableStateFlow<RollbackPlan?>(null)
    override val rollbackRecovery = MutableStateFlow<RollbackRecovery?>(null)
    override val moduleRollback = MutableStateFlow<RollbackState>(RollbackState.Idle)

    override suspend fun check(channel: String?): UpdateCheck {
        checked++
        return result
    }

    override suspend fun installApp(manifest: UpdateManifest) {
        appInstalls++
        installedManifest = manifest
    }

    override suspend fun installModule(manifest: UpdateManifest) {
        moduleInstalls++
    }

    override suspend fun rollbackApp() {
        rollbackCalls++
    }

    override fun dismiss() = Unit
}

/** A bridge whose call phase the tests move: a running call blocks every disruptive action. */
internal class FakeBridgeState : BridgeStateRepository {
    private val flow = MutableStateFlow(BridgeState())

    override val state: StateFlow<BridgeState> = flow

    fun startCall() {
        flow.value = BridgeState(
            call = CallState(
                callId = "call-1",
                destinationMasked = "+998 90 123 45 67",
                phase = CallPhase.ACTIVE,
                startedAtEpochMs = 0L
            )
        )
    }
}
