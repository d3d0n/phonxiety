package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.model.DoctorFixResult
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DoctorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aDownLinkIsRepairedWithoutAskingAndRecorded() = runTest(dispatcher) {
        val source = FakeDoctorSource(TestData.snapshot())
        val daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        val service = FakeServiceControl()
        val log = FakeChangeLog()
        val viewModel = viewModel(
            source = source,
            bridge = FakeBridgeState(TestData.bridge(link = LinkState.Offline)),
            daemon = daemon,
            service = service,
            log = log
        )

        advanceUntilIdle()

        assertEquals(1, service.restarts)
        assertTrue(log.recorded.any { it.text == "Restarted the bridge service to reopen the link" })
        assertFalse(viewModel.state.value.checking)
        assertTrue(viewModel.state.value.rows.isNotEmpty())
        assertEquals(RootId.MAGISK, viewModel.state.value.provider)
    }

    @Test
    fun aLiveCallStopsTheAutomaticFixesAndSaysWhy() = runTest(dispatcher) {
        val service = FakeServiceControl()
        val viewModel = viewModel(
            source = FakeDoctorSource(TestData.snapshot()),
            bridge = FakeBridgeState(TestData.bridge(link = LinkState.Offline, call = TestData.call())),
            daemon = FakeDaemonApi(),
            service = service
        )

        advanceUntilIdle()

        assertEquals(0, service.restarts)
        assertTrue(viewModel.state.value.callActive)
        assertEquals(FixClass.NEVER, viewModel.state.value.row(DoctorEvaluator.ID_LINK)?.plan?.risk)

        viewModel.requestFix(DoctorEvaluator.ID_LINK)
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.message)
        assertNull(viewModel.state.value.pending)
    }

    @Test
    fun aDaemonThatDoesNotAnswerGetsItsOwnFailingRow() = runTest(dispatcher) {
        val viewModel = viewModel(
            source = FakeDoctorSource(TestData.snapshot()),
            daemon = FakeDaemonApi().apply {
                doctorOutcome = ApiOutcome.Failure(503, "link_down", "the bridge service is not running")
            }
        )

        advanceUntilIdle()

        val state = viewModel.state.value
        val daemonRow = state.row(DoctorEvaluator.ID_DAEMON)
        assertEquals(DoctorSeverity.FAIL, daemonRow?.severity)
        assertTrue(requireNotNull(daemonRow).evidence.contains("link_down"))
        assertEquals(DoctorSeverity.UNKNOWN, state.row(DoctorEvaluator.ID_RELAY)?.severity)
        assertFalse(state.rows.any { it.kind == DoctorItemKind.DAEMON_CHECK })
        assertTrue(state.daemonFixes.isEmpty())
    }

    @Test
    fun aRuntimePermissionWaitsForTheDialogConfirmation() = runTest(dispatcher) {
        val effects = mutableListOf<DoctorEffect>()
        val source = FakeDoctorSource(
            TestData.snapshot(runtimeMissing = setOf(RuntimePermission.RECORD_AUDIO))
        )
        val daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        val viewModel = viewModel(source = source, daemon = daemon)
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        advanceUntilIdle()

        val rowId = "${DoctorEvaluator.ID_RUNTIME}:${RuntimePermission.RECORD_AUDIO.androidName}"
        assertEquals(FixClass.CONFIRM, viewModel.state.value.row(rowId)?.plan?.risk)
        viewModel.requestFix(rowId)
        advanceUntilIdle()
        assertNotNull("the effect must wait for the dialog", viewModel.state.value.pending)
        assertTrue(effects.isEmpty())

        viewModel.confirmFix()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        val request = effects.single() as DoctorEffect.RequestRuntimePermissions
        assertEquals(listOf(RuntimePermission.RECORD_AUDIO.androidName), request.permissions)
        collector.cancel()
    }

    @Test
    fun notificationsAskForThePermissionOrForTheSettingsScreen() = runTest(dispatcher) {
        val permissionEffects = mutableListOf<DoctorEffect>()
        val missing = viewModel(
            source = FakeDoctorSource(
                TestData.snapshot(runtimeMissing = setOf(RuntimePermission.POST_NOTIFICATIONS))
            ),
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        )
        val first = launch(UnconfinedTestDispatcher(testScheduler)) {
            missing.effects.collect { permissionEffects += it }
        }
        advanceUntilIdle()
        missing.requestFix(DoctorEvaluator.ID_NOTIFICATIONS)
        advanceUntilIdle()
        missing.confirmFix()
        advanceUntilIdle()
        assertEquals(
            listOf(RuntimePermission.POST_NOTIFICATIONS.androidName),
            (permissionEffects.single() as DoctorEffect.RequestRuntimePermissions).permissions
        )
        first.cancel()

        val settingsEffects = mutableListOf<DoctorEffect>()
        val switchedOff = viewModel(
            source = FakeDoctorSource(TestData.snapshot(notifications = false)),
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        )
        val second = launch(UnconfinedTestDispatcher(testScheduler)) {
            switchedOff.effects.collect { settingsEffects += it }
        }
        advanceUntilIdle()
        switchedOff.requestFix(DoctorEvaluator.ID_NOTIFICATIONS)
        advanceUntilIdle()
        switchedOff.confirmFix()
        advanceUntilIdle()
        assertEquals(DoctorEffect.OpenNotificationSettings, settingsEffects.single())
        second.cancel()
    }

    @Test
    fun reEnablingTheModuleRunsTheProviderAndThenReboots() = runTest(dispatcher) {
        val source = FakeDoctorSource(TestData.snapshot(module = ModuleState.Disabled("0.3.0", 3)))
        val log = FakeChangeLog()
        val viewModel = viewModel(
            source = source,
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) },
            log = log
        )
        advanceUntilIdle()

        viewModel.requestFix(DoctorEvaluator.ID_MODULE_ENABLED)
        advanceUntilIdle()
        assertEquals(FixClass.CONFIRM, viewModel.state.value.pending?.plan?.risk)
        assertEquals("no provider command before the confirmation", 0, source.enables)

        viewModel.confirmFix()
        advanceUntilIdle()

        assertEquals(1, source.enables)
        assertEquals(1, source.reboots)
        assertTrue(log.recorded.any { it.text.startsWith("Re-enabling the module") })
        assertTrue(log.recorded.any { it.text.contains("Requested the reboot") })
    }

    @Test
    fun aFailedProviderCommandIsReportedAndDoesNotReboot() = runTest(dispatcher) {
        val source = FakeDoctorSource(
            TestData.snapshot(module = ModuleState.Absent),
            reinstallResult = ProviderResult.Failure("magisk --install-module: exit 1", 1)
        )
        val viewModel = viewModel(
            source = source,
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        )
        advanceUntilIdle()

        viewModel.requestFix(DoctorEvaluator.ID_MODULE_PRESENT)
        advanceUntilIdle()
        viewModel.confirmFix()
        advanceUntilIdle()

        assertEquals(1, source.reinstalls)
        assertEquals(0, source.reboots)
        assertNotNull(viewModel.state.value.message)
        assertTrue(
            viewModel.state.value.changeLog.any { it.text.contains("Reinstalling the module failed") }
        )
    }

    @Test
    fun theAutomaticDaemonFixRunsAndTheRestAskFirst() = runTest(dispatcher) {
        val daemon = FakeDaemonApi().apply {
            doctorOutcome = ApiOutcome.Ok(200, TestData.report())
            fixOutcome = ApiOutcome.Ok(200, DoctorFixResult())
        }
        val log = FakeChangeLog()
        val viewModel = viewModel(
            source = FakeDoctorSource(TestData.snapshot()),
            daemon = daemon,
            log = log
        )
        advanceUntilIdle()

        assertEquals(listOf("relay_config", "state_dirs"), viewModel.state.value.daemonFixes.map { it.fix })
        assertEquals(
            FixClass.AUTOMATIC,
            viewModel.state.value.daemonFixes.first { it.fix == "state_dirs" }.plan.risk
        )
        assertEquals(
            FixClass.CONFIRM,
            viewModel.state.value.daemonFixes.first { it.fix == "relay_config" }.plan.risk
        )

        viewModel.requestDaemonFix("state_dirs")
        advanceUntilIdle()
        assertEquals(listOf("state_dirs"), daemon.appliedFixes)

        viewModel.requestDaemonFix("relay_config")
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.pending)
        viewModel.confirmFix()
        advanceUntilIdle()
        assertEquals(listOf("state_dirs", "relay_config"), daemon.appliedFixes)
        assertTrue(log.recorded.any { it.text.contains("Daemon applied relay_config") })
    }

    @Test
    fun aRejectedDaemonFixBecomesAMessage() = runTest(dispatcher) {
        val daemon = FakeDaemonApi().apply {
            doctorOutcome = ApiOutcome.Ok(200, TestData.report())
            fixOutcome = ApiOutcome.Failure(400, "unknown_fix", "fix must be one of: relay_config, state_dirs")
        }
        val viewModel = viewModel(source = FakeDoctorSource(TestData.snapshot()), daemon = daemon)
        advanceUntilIdle()

        viewModel.requestDaemonFix("state_dirs")
        advanceUntilIdle()

        assertTrue(requireNotNull(viewModel.state.value.message).contains("unknown_fix"))
    }

    @Test
    fun theBatteryExemptionGoesThroughTheSystemDialog() = runTest(dispatcher) {
        val effects = mutableListOf<DoctorEffect>()
        val viewModel = viewModel(
            source = FakeDoctorSource(TestData.snapshot(battery = false)),
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        )
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        advanceUntilIdle()

        viewModel.requestFix(DoctorEvaluator.ID_BATTERY)
        advanceUntilIdle()
        viewModel.confirmFix()
        advanceUntilIdle()

        assertEquals(DoctorEffect.RequestBatteryExemption, effects.single())
        collector.cancel()
    }

    @Test
    fun aDeviceThatCannotBeReadBecomesAReadError() = runTest(dispatcher) {
        val source = FakeDoctorSource(TestData.snapshot())
        source.readFailure = IllegalStateException("su did not answer")
        val viewModel = viewModel(source = source)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.checking)
        assertEquals("su did not answer", state.readError)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun pairingOpensTheRecoveryPage() = runTest(dispatcher) {
        val viewModel = viewModel(
            source = FakeDoctorSource(TestData.snapshot()),
            pairing = FakePairing(paired = false),
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        )
        advanceUntilIdle()
        viewModel.requestFix(DoctorEvaluator.ID_PAIRING)
        advanceUntilIdle()
        viewModel.confirmFix()
        advanceUntilIdle()

        assertEquals(RecoveryId.PAIRING_REJECTED, viewModel.state.value.page?.id)
    }

    @Test
    fun aLinkThatComesBackUpdatesTheRowWithoutAnotherProbe() = runTest(dispatcher) {
        val bridge = FakeBridgeState(TestData.bridge(link = LinkState.Offline))
        val viewModel = viewModel(
            source = FakeDoctorSource(TestData.snapshot()),
            bridge = bridge,
            daemon = FakeDaemonApi().apply { doctorOutcome = ApiOutcome.Ok(200, TestData.report()) }
        )
        advanceUntilIdle()
        assertEquals(DoctorSeverity.FAIL, viewModel.state.value.row(DoctorEvaluator.ID_LINK)?.severity)

        bridge.flow.value = TestData.bridge()
        advanceUntilIdle()

        assertEquals(DoctorSeverity.OK, viewModel.state.value.row(DoctorEvaluator.ID_LINK)?.severity)
    }

    private fun TestScope.viewModel(
        source: FakeDoctorSource,
        bridge: FakeBridgeState = FakeBridgeState(TestData.bridge()),
        pairing: FakePairing = FakePairing(),
        daemon: FakeDaemonApi = FakeDaemonApi(),
        service: FakeServiceControl = FakeServiceControl(),
        log: FakeChangeLog = FakeChangeLog()
    ): DoctorViewModel = DoctorViewModel(
        source = source,
        bridgeState = bridge,
        pairing = pairing,
        daemonApi = daemon,
        changeLog = log,
        serviceControl = service,
        io = dispatcher
    )
}
