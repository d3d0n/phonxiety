package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.RollbackState
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateManifest
import com.phonxiety.bridge.data.updates.UpdatesService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
        val viewModel = UpdatesViewModel(service)
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
        val viewModel = UpdatesViewModel(service)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()

        assertEquals(UpdatesSamples.manifest, service.installedManifest)
    }

    @Test
    fun `a finished install triggers another check`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService()
        val viewModel = UpdatesViewModel(service)
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
        val viewModel = UpdatesViewModel(service)
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
        val viewModel = UpdatesViewModel(service)
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
    fun `an app only rollback runs without a confirmation`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()

        assertEquals(1, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
    }

    private class FakeUpdatesService : UpdatesService {
        var result: UpdateCheck = UpdatesSamples.check()
        var checked = 0
        var installedManifest: UpdateManifest? = null

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

        override suspend fun installModule(manifest: UpdateManifest) = Unit

        override suspend fun rollbackApp() {
            rollbackCalls++
        }

        override fun dismiss() = Unit
    }
}
