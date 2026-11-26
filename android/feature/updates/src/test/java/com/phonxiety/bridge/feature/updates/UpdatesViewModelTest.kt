package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.BridgeStateRepository
import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.InstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateManifest
import com.phonxiety.bridge.data.updates.UpdatesService
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.CallPhase
import com.phonxiety.bridge.model.CallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdatesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val bridge = FakeBridgeState()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the first check fills the three rows`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService()
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, service.checked)
        assertFalse(state.loading)
        assertNotNull(state.check?.verdict)
        assertEquals("0.4.0", state.check?.verdict?.app?.installedVersion)
    }

    @Test
    fun `install uses the manifest from the last check`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService()
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()

        assertEquals(UpdatesSamples.manifest, service.installedManifest)
    }

    @Test
    fun `a finished install triggers another check`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService()
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertEquals(1, service.checked)

        service.appInstall.value = AppInstallState.Installed("0.4.0")
        advanceUntilIdle()

        assertEquals(2, service.checked)
    }

    @Test
    fun `a failed check is rendered, not hidden`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            result = UpdateCheck(error = com.phonxiety.bridge.data.updates.UpdateCheckError.LINK_DOWN, detail = "link_down")
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        assertEquals(
            com.phonxiety.bridge.data.updates.UpdateCheckError.LINK_DOWN,
            viewModel.state.value.check?.error
        )
        assertTrue(viewModel.state.value.check?.verdict == null)
    }

    @Test
    fun `the combined rollback asks before it stages anything`() = runTest(testDispatcher.scheduler) {
        val plan = UpdatesSamples.moduleRollback()
        val service = FakeUpdatesService().apply { rollbackPlan.value = plan }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()

        assertEquals(0, service.rollbackCalls)
        assertEquals(plan, viewModel.state.value.rollbackConfirmation)

        viewModel.confirmRollback()
        advanceUntilIdle()

        assertEquals(1, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
    }

    @Test
    fun `an app only rollback asks before the root downgrade`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()

        assertEquals(0, service.rollbackCalls)
        assertEquals(UpdatesSamples.appOnlyRollback(), viewModel.state.value.rollbackConfirmation)

        viewModel.confirmRollback()
        advanceUntilIdle()

        assertEquals(1, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
    }

    @Test
    fun `cancelling the confirmation starts nothing`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()
        viewModel.dismissRollbackConfirmation()
        advanceUntilIdle()

        assertEquals(0, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
        assertEquals(AppInstallState.Idle, viewModel.state.value.rollbackAppInstall)
    }

    @Test
    fun `a call blocks installs and rollbacks`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            rollbackPlan.value = UpdatesSamples.moduleRollback()
            rollbackRecovery.value = RollbackRecovery.Pending(RollbackPendingReason.REMOVAL_FAILED, null)
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        bridge.startCall()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.callActive)

        viewModel.installApp()
        viewModel.installModule()
        viewModel.rollback()
        viewModel.confirmRollback()
        advanceUntilIdle()

        assertEquals(null, service.installedManifest)
        assertEquals(0, service.moduleInstalls)
        assertEquals(0, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
    }

    @Test
    fun `an app only rollback failure stays in the rollback section`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()
        viewModel.confirmRollback()
        advanceUntilIdle()
        val failure = AppInstallState.Failed(InstallFailure.ROOT_UNAVAILABLE, "su: denied")
        service.appInstall.value = failure
        advanceUntilIdle()

        assertEquals(failure, viewModel.state.value.rollbackAppInstall)
    }

    @Test
    fun `an update install never lends its state to the rollback section`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()
        service.appInstall.value = AppInstallState.Failed(InstallFailure.INSTALL_FAILED, "the installer refused")
        advanceUntilIdle()

        assertEquals(AppInstallState.Idle, viewModel.state.value.rollbackAppInstall)
        assertTrue(viewModel.state.value.appInstall is AppInstallState.Failed)
    }

    @Test
    fun `a pending rollback retries without asking again`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            rollbackPlan.value = UpdatesSamples.moduleRollback()
            rollbackRecovery.value = RollbackRecovery.Pending(RollbackPendingReason.REMOVAL_FAILED, "Failure [X]")
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()

        assertEquals(1, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
    }

    @Test
    fun `the unfinished rollback block can be dismissed until the next check`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            rollbackRecovery.value = RollbackRecovery.Pending(RollbackPendingReason.REMOVAL_NOT_STARTED, null)
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertFalse(viewModel.state.value.rollbackRecoveryDismissed)

        viewModel.dismissRollbackRecovery()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.rollbackRecoveryDismissed)

        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.rollbackRecoveryDismissed)
        assertTrue(viewModel.state.value.rollbackRecovery is RollbackRecovery.Pending)
    }

    private class FakeUpdatesService : UpdatesService {
        var result: UpdateCheck = UpdatesSamples.check()
        var checked = 0
        var installedManifest: UpdateManifest? = null
        var moduleInstalls = 0

        override val appInstall = MutableStateFlow<AppInstallState>(AppInstallState.Idle)
        override val moduleInstall = MutableStateFlow<ModuleInstallState>(ModuleInstallState.Idle)
        override val rollbackPlan = MutableStateFlow<RollbackPlan?>(null)
        override val rollbackRecovery = MutableStateFlow<RollbackRecovery?>(null)
        override val moduleRollback = MutableStateFlow<RollbackState>(RollbackState.Idle)
        var rollbackCalls = 0

        override suspend fun check(channel: String?): UpdateCheck {
            checked++
            return result
        }

        override suspend fun installApp(manifest: UpdateManifest) {
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
    private class FakeBridgeState : BridgeStateRepository {
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
}
