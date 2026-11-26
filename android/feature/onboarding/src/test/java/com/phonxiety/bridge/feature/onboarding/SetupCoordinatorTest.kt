package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.AppApi
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.data.PairingRepository
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.ProgressRecord
import com.phonxiety.bridge.root.ProgressStore
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootCapabilities
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.VerifyReport
import com.phonxiety.bridge.root.WizardEvent
import com.phonxiety.bridge.root.WizardState
import java.io.OutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The coordinator against fake root providers, a fake pairing repository and a fake app API: every
 * effect and its order, without a device or a daemon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SetupCoordinatorTest {
    private lateinit var backend: FakeBackend
    private lateinit var pairing: FakePairing
    private lateinit var appApi: FakeAppApi
    private lateinit var progress: FakeProgressStore
    private lateinit var permissions: FakePermissions
    private lateinit var preferences: SetupPreferences
    private lateinit var hosts: MutableList<SetupHostRequest>
    private var serviceStarts = 0
    private lateinit var coordinator: SetupCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = SetupPreferences(context).apply { reset() }
        backend = FakeBackend()
        pairing = FakePairing()
        appApi = FakeAppApi()
        progress = FakeProgressStore()
        permissions = FakePermissions()
        hosts = mutableListOf()
        serviceStarts = 0
        coordinator = SetupCoordinator(
            backend = backend,
            pairing = pairing,
            appApi = appApi,
            progressStore = progress,
            preferences = preferences,
            permissions = permissions,
            serviceStarter = { serviceStarts++ },
            host = { hosts += it },
            linkState = readyLink()
        )
    }

    @Test
    fun installThenReboot() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Absent
        pairing.pairedState.value = true
        preferences.linkTestPassed = true

        coordinator.start()
        assertEquals(WizardState.ReadyToInstall(MAGISK), coordinator.wizard.value)

        coordinator.dispatch(WizardEvent.InstallRequested)
        assertEquals(listOf(InstallOperation.INSTALL), backend.installs)
        assertEquals(InstallOperation.INSTALL, progress.record?.operation)
        assertTrue(coordinator.wizard.value is WizardState.RebootPending)

        coordinator.dispatch(WizardEvent.RebootRequested)
        assertEquals(listOf(RebootReason.MODULE_INSTALL), backend.reboots)
    }

    @Test
    fun permissionsThenPairingThenLinkTest() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Enabled("0.3.0", 3)
        backend.verify = PASSED
        permissions.missingState = mutableListOf(RuntimePermission.RECORD_AUDIO, RuntimePermission.ACCESS_LOCAL_NETWORK)
        pairing.pairedState.value = false
        appApi.linkTest = ApiResult.Success(
            status = 200,
            result = buildJsonObject {
                put("healthy", true)
                put("verdict", "good")
                put("p50", 41)
                put("loss", 0.0)
            }
        )

        coordinator.start()
        val runtime = coordinator.wizard.value as WizardState.NeedsRuntime
        assertEquals(2, runtime.missing.size)
        assertTrue(hosts.any { it is SetupHostRequest.RequestRuntimePermissions })
        assertTrue(hosts.any { it == SetupHostRequest.RequestBatteryExemption })
        permissions.missingState = mutableListOf()
        coordinator.onPermissionsChanged()
        assertTrue(coordinator.wizard.value is WizardState.NeedsPairing)
        assertTrue(hosts.any { it == SetupHostRequest.StartPairing })

        pairing.pairResult = PairResult.Paired
        coordinator.pair("phonxiety://pair?token=abc")
        assertTrue(preferences.linkTestPassed)
        assertEquals(5, appApi.requestedBodies.single()["seconds"]!!.jsonPrimitive.int)
        assertEquals(1, serviceStarts)
        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertTrue(preferences.ready)
        assertTrue(hosts.any { it == SetupHostRequest.Finished })
    }

    @Test
    fun installFailureKeepsTheProviderOutput() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Absent
        backend.installResult = ProviderResult.Failure("ksud: bad module.prop", exitCode = 1)
        pairing.pairedState.value = true
        preferences.linkTestPassed = true

        coordinator.start()
        coordinator.dispatch(WizardEvent.InstallRequested)

        val failed = coordinator.wizard.value as WizardState.InstallFailed
        assertEquals(ProviderResult.Failure("ksud: bad module.prop", exitCode = 1), failed.result)
        assertEquals(listOf("ksud: bad module.prop"), failed.outputTail)

        coordinator.dispatch(WizardEvent.RetryRequested)
        assertEquals(WizardState.ReadyToInstall(MAGISK), coordinator.wizard.value)
    }

    @Test
    fun failedLinkTestStopsAtDoctor() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Enabled("0.3.0", 3)
        backend.verify = PASSED
        pairing.pairedState.value = true
        appApi.linkTest = ApiResult.Failure(500, "link_lost", "the phone stopped answering")

        coordinator.start()
        assertTrue(coordinator.wizard.value is WizardState.Doctor)
        assertTrue(!preferences.linkTestPassed)
    }

    @Test
    fun deniedRootIsNamedWithTheManager() = runTest {
        backend.detection = RootDetection(
            su = SuStatus.DENIED,
            providers = listOf(MAGISK),
            suOutput = "su: permission denied"
        )

        coordinator.start()
        val denied = coordinator.wizard.value as WizardState.RootDenied
        assertEquals(MAGISK, denied.provider)
        assertEquals("su: permission denied", denied.output)
    }

    @Test
    fun twoProvidersRefuseToInstall() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK, KERNELSU))
        backend.module = ModuleState.Absent

        coordinator.start()
        val conflict = coordinator.wizard.value as WizardState.Conflict
        assertEquals(listOf(MAGISK, KERNELSU), conflict.providers)
        assertTrue(backend.installs.isEmpty())
    }

    @Test
    fun kernelSuWithoutMetamoduleNeedsPreparation() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(KERNELSU))
        backend.module = ModuleState.Absent
        backend.capabilities = RootCapabilities(
            systemOverlay = Overlay.METAMODULE_MISSING,
            hotInstall = true,
            updateJson = true,
            webUi = true,
            actionButton = true,
            bootStages = emptySet(),
            managerPackage = "me.weishu.kernelsu",
            coexistenceWarning = null
        )

        coordinator.start()
        assertEquals(WizardState.ProviderPrep(KERNELSU), coordinator.wizard.value)
    }

    @Test
    fun rejectedPairingIsReportedNotSwallowed() = runTest {
        pairing.pairResult = PairResult.Invalid("the ticket expired")

        coordinator.pair("phonxiety://pair?token=old")
        assertTrue(hosts.any { it == SetupHostRequest.PairingRejected("the ticket expired") })
    }

    @Test
    fun theRebootCommandThatFailedIsShownNotSwallowed() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Absent
        backend.rebootResult = ProviderResult.Failure(
            "svc: Unable to set property \"sys.powerctl\": PROP_ERROR_PERMISSION_DENIED",
            exitCode = 1
        )
        coordinator.start()
        coordinator.dispatch(WizardEvent.InstallRequested)
        assertTrue(coordinator.wizard.value is WizardState.RebootPending)

        coordinator.requestReboot()

        val pending = coordinator.wizard.value as WizardState.RebootPending
        assertNotNull("the failure must be visible on the step", pending.failure)
        assertTrue(pending.failure!!.contains("PROP_ERROR_PERMISSION_DENIED"))
        assertEquals(1, backend.reboots.size)
        assertTrue(hosts.none { it is SetupHostRequest.Message })
    }

    @Test
    fun aRebootThatReportedSuccessButDidNotRestartIsShown() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Absent
        coordinator.start()
        coordinator.dispatch(WizardEvent.InstallRequested)

        // Still here after the window: the app never went down, so the phone did not restart.
        coordinator.requestReboot()

        val pending = coordinator.wizard.value as WizardState.RebootPending
        assertNotNull(pending.failure)
        assertTrue(pending.failure!!.contains("still running 20 s after the reboot request"))
        assertEquals(1, backend.reboots.size)
    }

    @Test
    fun aRebootOnItsWayIsNotCalledAFailure() = runTest {
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Absent
        coordinator.start()
        coordinator.dispatch(WizardEvent.InstallRequested)

        val request = launch { coordinator.requestReboot() }
        runCurrent()
        assertNull((coordinator.wizard.value as WizardState.RebootPending).failure)

        advanceTimeBy(19_000)
        runCurrent()
        assertNull((coordinator.wizard.value as WizardState.RebootPending).failure)

        advanceTimeBy(1_001)
        runCurrent()
        request.join()
        assertNotNull((coordinator.wizard.value as WizardState.RebootPending).failure)
    }

    private companion object {
        val MAGISK = DetectedProvider(
            id = RootId.MAGISK,
            version = "31.1",
            managerPackage = "com.topjohnwu.magisk",
            managerInstalled = true
        )

        val KERNELSU = DetectedProvider(
            id = RootId.KERNELSU,
            version = "3.1.0",
            managerPackage = "me.weishu.kernelsu",
            managerInstalled = true
        )

        val PASSED = VerifyReport(VerifyCheck.entries.associateWith { VerifyOutcome.PASS })
    }
}
