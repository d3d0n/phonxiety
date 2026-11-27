package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.updates.AppInstallState
import com.phonxiety.bridge.data.updates.InstallFailure
import com.phonxiety.bridge.data.updates.ModuleInstallState
import com.phonxiety.bridge.data.updates.RollbackPendingReason
import com.phonxiety.bridge.data.updates.RollbackRecovery
import com.phonxiety.bridge.data.updates.UpdateCheck
import com.phonxiety.bridge.data.updates.UpdateTarget
import kotlinx.coroutines.Dispatchers
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
    fun `install asks first and uses the manifest from the last check`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { result = UpdatesSamples.updateOffered() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()

        assertEquals(UpdateTarget.APP, viewModel.state.value.updateConfirmation)
        assertEquals("nothing installs before the confirmation", null, service.installedManifest)

        viewModel.confirmUpdate()
        advanceUntilIdle()

        assertEquals(UpdatesSamples.manifest, service.installedManifest)
        assertEquals(null, viewModel.state.value.updateConfirmation)
    }

    @Test
    fun `dismissing the update confirmation starts nothing`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { result = UpdatesSamples.updateOffered() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()
        viewModel.dismissUpdateConfirmation()
        advanceUntilIdle()

        assertEquals(null, service.installedManifest)
        assertEquals(null, viewModel.state.value.updateConfirmation)
    }

    @Test
    fun `a call that starts before the confirmation refuses the install`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { result = UpdatesSamples.updateOffered() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()
        assertEquals(UpdateTarget.APP, viewModel.state.value.updateConfirmation)

        bridge.startCall()
        advanceUntilIdle()
        viewModel.confirmUpdate()
        advanceUntilIdle()

        assertEquals(null, service.installedManifest)
        assertEquals(null, viewModel.state.value.updateConfirmation)
    }

    @Test
    fun `a second confirm runs no second app install`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { result = UpdatesSamples.updateOffered() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()
        viewModel.confirmUpdate()
        viewModel.confirmUpdate()
        advanceUntilIdle()

        assertEquals(1, service.appInstalls)
        assertEquals(null, viewModel.state.value.updateConfirmation)
    }

    @Test
    fun `a second confirm runs no second module install`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            // The app already runs this release, so the module row is the one that offers.
            result = UpdatesSamples.check(UpdatesSamples.installed(moduleVersionCode = 3, moduleVersion = "0.3.0"))
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installModule()
        advanceUntilIdle()
        viewModel.confirmUpdate()
        viewModel.confirmUpdate()
        advanceUntilIdle()

        assertEquals(1, service.moduleInstalls)
        assertEquals(null, viewModel.state.value.updateConfirmation)
    }

    @Test
    fun `a second confirm runs no second rollback`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.moduleRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.rollback()
        advanceUntilIdle()
        assertEquals(UpdatesSamples.moduleRollback(), viewModel.state.value.rollbackConfirmation)

        viewModel.confirmRollback()
        viewModel.confirmRollback()
        advanceUntilIdle()

        assertEquals(1, service.rollbackCalls)
        assertEquals(null, viewModel.state.value.rollbackConfirmation)
    }

    @Test
    fun `a stale confirm with nothing pending starts no rollback`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        // Never asked: the dialog was never opened, so a stray confirm runs nothing.
        viewModel.confirmRollback()
        advanceUntilIdle()

        assertEquals(0, service.rollbackCalls)
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
        assertEquals(null, viewModel.state.value.updateConfirmation)
    }

    @Test
    fun `the module install waits for the app update that carries its payload`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            result = UpdatesSamples.check(
                UpdatesSamples.installed(
                    appVersionCode = 3,
                    appVersion = "0.3.0",
                    moduleVersionCode = 3,
                    moduleVersion = "0.3.0"
                )
            )
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.check?.verdict?.module?.needsAppFirst == true)

        viewModel.installModule()
        advanceUntilIdle()

        assertEquals("the module is never rebuilt from the old app", 0, service.moduleInstalls)
        assertEquals("and it never even asks", null, viewModel.state.value.updateConfirmation)

        // Once the app update has landed the same row installs, and its payload is the new app.
        service.result = UpdatesSamples.check(
            UpdatesSamples.installed(moduleVersionCode = 3, moduleVersion = "0.3.0")
        )
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.check?.verdict?.module?.needsAppFirst ?: true)

        viewModel.installModule()
        advanceUntilIdle()
        assertEquals(UpdateTarget.MODULE, viewModel.state.value.updateConfirmation)
        viewModel.confirmUpdate()
        advanceUntilIdle()

        assertEquals(1, service.moduleInstalls)
    }

    @Test
    fun `an app only rollback failure stays in the rollback section and retries as a rollback`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply { rollbackPlan.value = UpdatesSamples.appOnlyRollback() }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        // Online: the check carries a manifest, so an update retry would have something to install.
        assertNotNull(viewModel.state.value.check?.manifest)

        viewModel.rollback()
        advanceUntilIdle()
        viewModel.confirmRollback()
        advanceUntilIdle()
        val failure = AppInstallState.Failed(InstallFailure.ROOT_UNAVAILABLE, "su: denied")
        service.appInstall.value = failure
        advanceUntilIdle()

        assertEquals(failure, viewModel.state.value.rollbackAppInstall)
        // The app row shows nothing: its "Install again" would put the update over the kept APK.
        assertEquals(AppInstallState.Idle, viewModel.state.value.appInstall)

        // The rollback section's retry asks again and runs the rollback, never an update install.
        viewModel.rollback()
        advanceUntilIdle()
        viewModel.confirmRollback()
        advanceUntilIdle()
        assertEquals(2, service.rollbackCalls)
        assertEquals(null, service.installedManifest)
    }

    @Test
    fun `an update install never lends its state to the rollback section`() = runTest(testDispatcher.scheduler) {
        val service = FakeUpdatesService().apply {
            rollbackPlan.value = UpdatesSamples.appOnlyRollback()
            result = UpdatesSamples.updateOffered()
        }
        val viewModel = UpdatesViewModel(service, bridge)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()
        viewModel.confirmUpdate()
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
}
