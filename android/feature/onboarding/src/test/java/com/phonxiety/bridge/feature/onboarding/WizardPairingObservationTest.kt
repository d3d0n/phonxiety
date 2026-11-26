package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.data.ApiResult
import com.phonxiety.bridge.data.PairResult
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.DoctorReason
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.VerifyReport
import com.phonxiety.bridge.root.WizardEvent
import com.phonxiety.bridge.root.WizardState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pairing that appears while the wizard shows "Unpaired" (`mise run phone:pair`, the service
 * rewriting its config, a link change the repository noticed) advances the wizard by itself, and a
 * first link test waits for a live link instead of recording a still-connecting one as a failure.
 * Observations share the coordinator lock with every transition, so a stale one is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WizardPairingObservationTest {
    private lateinit var backend: FakeBackend
    private lateinit var pairing: FakePairing
    private lateinit var appApi: FakeAppApi
    private lateinit var progress: FakeProgressStore
    private lateinit var permissions: FakePermissions
    private lateinit var preferences: SetupPreferences
    private lateinit var hosts: MutableList<SetupHostRequest>
    private lateinit var link: MutableStateFlow<LinkState>
    private var serviceStarts = 0
    private lateinit var coordinator: SetupCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = SetupPreferences(context).apply { reset() }
        backend = FakeBackend().apply {
            detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
            module = ModuleState.Enabled("0.3.0", 3)
            verify = PASSED
        }
        pairing = FakePairing()
        appApi = FakeAppApi().apply { linkTest = HEALTHY }
        progress = FakeProgressStore()
        permissions = FakePermissions()
        hosts = mutableListOf()
        link = readyLink()
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
            linkState = link
        )
    }

    @Test
    fun aStoredPairingAppearingWhileUnpairedReachesFirstLinkTestByItself() = runTest {
        coordinator.start()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)

        // The repository publishes the stored pairing; no resume event is sent.
        pairing.pairedState.value = true
        appApi.requestGate = CompletableDeferred()
        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()

        assertEquals(WizardState.FirstLinkTest(MAGISK), coordinator.wizard.value)
        assertEquals(1, linkTests())

        appApi.requestGate?.complete(Unit)
        observation.join()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
        assertEquals(1, serviceStarts)
        assertTrue(preferences.linkTestPassed)
        assertTrue(hosts.any { it == SetupHostRequest.Finished })
    }

    @Test
    fun anObservationAwayFromNeedsPairingDoesNotProbe() = runTest {
        pairing.pairedState.value = true
        preferences.linkTestPassed = true
        coordinator.start()
        assertEquals(WizardState.Ready, coordinator.wizard.value)
        val probes = backend.detects

        coordinator.onPairingObserved()

        assertEquals(probes, backend.detects)
        assertEquals(WizardState.Ready, coordinator.wizard.value)
    }

    @Test
    fun anObservationDuringAnInstallIsANoOp() = runTest {
        pairing.pairedState.value = true
        backend.module = ModuleState.Absent
        coordinator.start()
        coordinator.dispatch(WizardEvent.InstallRequested)
        assertTrue(coordinator.wizard.value !is WizardState.NeedsPairing)
        val probes = backend.detects

        coordinator.onPairingObserved()

        assertEquals(probes, backend.detects)
        assertTrue(coordinator.wizard.value is WizardState.RebootPending)
    }

    @Test
    fun anObservationDuringAnUnresolvedPairingAttemptWaitsForItsOutcome() = runTest {
        coordinator.start()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        val probes = backend.detects

        pairing.pairGate = CompletableDeferred()
        val attempt = launch { coordinator.pair("phonxiety://pair?token=abc") }
        runCurrent()
        pairing.pairedState.value = true
        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()

        // The scanner/paste attempt owns the wizard while it runs.
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        assertEquals(probes, backend.detects)

        pairing.pairGate?.complete(Unit)
        attempt.join()
        observation.join()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
        assertEquals(1, serviceStarts)
        assertTrue(hosts.any { it == SetupHostRequest.Finished })
    }

    @Test
    fun aLateObservationDoesNotRepeatAFailedLinkTest() = runTest {
        coordinator.start()
        appApi.linkTest = ApiResult.Failure(500, "link_lost", "the phone stopped answering")

        pairing.pairGate = CompletableDeferred()
        val attempt = launch { coordinator.pair("phonxiety://pair?token=abc") }
        runCurrent()
        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()
        pairing.pairGate?.complete(Unit)

        attempt.join()
        observation.join()

        assertTrue(coordinator.wizard.value is WizardState.Doctor)
        assertEquals(1, linkTests())
        assertTrue(hosts.none { it == SetupHostRequest.Finished })
    }

    @Test
    fun twoObservationsAndAProbeCoalesceIntoOneTest() = runTest {
        coordinator.start()
        pairing.pairedState.value = true
        backend.detectGate = CompletableDeferred()
        val probes = backend.detects

        val probe = launch { coordinator.dispatch(WizardEvent.RecheckRequested) }
        runCurrent()
        val first = launch { coordinator.onPairingObserved() }
        val second = launch { coordinator.onPairingObserved() }
        runCurrent()

        backend.detectGate?.complete(Unit)
        probe.join()
        first.join()
        second.join()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(probes + 1, backend.detects)
        assertEquals(1, linkTests())
        assertEquals(1, serviceStarts)
    }

    @Test
    fun aResumeQueuedAfterTheObservationIsAdmittedDoesNotTestOrFinishAgain() = runTest {
        coordinator.start()
        pairing.pairedState.value = true
        appApi.requestGate = CompletableDeferred()

        // The observation wins the lock and keeps it through the first link test.
        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()
        assertEquals(WizardState.FirstLinkTest(MAGISK), coordinator.wizard.value)

        // The resume was asked for while the wizard still showed "Unpaired"; it queues behind.
        val resume = launch { coordinator.probeOnResume(unpairedAtRequest = true) }
        runCurrent()

        appApi.requestGate?.complete(Unit)
        observation.join()
        resume.join()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
        assertEquals(1, serviceStarts)
        assertEquals(1, hosts.count { it == SetupHostRequest.Finished })
    }

    @Test
    fun aResumeAdmittedFirstLeavesAQueuedObservationNothingToDo() = runTest {
        coordinator.start()
        pairing.pairedState.value = true
        backend.detectGate = CompletableDeferred()
        val probes = backend.detects

        val resume = launch { coordinator.probeOnResume(unpairedAtRequest = true) }
        runCurrent()
        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()

        backend.detectGate?.complete(Unit)
        resume.join()
        observation.join()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(probes + 1, backend.detects)
        assertEquals(1, linkTests())
        assertEquals(1, hosts.count { it == SetupHostRequest.Finished })
    }

    @Test
    fun aResumeFromAnyOtherStateStillProbes() = runTest {
        backend.detection = RootDetection(SuStatus.DENIED, listOf(MAGISK), "Permission denied")
        coordinator.start()
        assertTrue(coordinator.wizard.value is WizardState.RootDenied)
        val probes = backend.detects

        // Root was granted in the manager while the app was away.
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(MAGISK))
        backend.module = ModuleState.Enabled("0.3.0", 3)
        coordinator.probeOnResume(unpairedAtRequest = false)

        assertEquals(probes + 1, backend.detects)
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
    }

    @Test
    fun aValidationFailureDoesNotBlockAPairingFromElsewhere() = runTest {
        coordinator.start()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        pairing.pairResult = PairResult.Invalid("the link is not a phonxiety pairing")

        // Typed into the paste field: invalid input writes nothing.
        coordinator.pair("https://example.invalid/not-a-pairing")

        assertTrue(hosts.any { it == SetupHostRequest.PairingRejected("the link is not a phonxiety pairing") })
        assertEquals(false, pairing.pairedState.value)
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)

        // The person then pairs by another path while staying on this screen.
        pairing.pairedState.value = true
        coordinator.onPairingObserved()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
    }

    @Test
    fun aClearedPairingEndsTheSuppressionOfARejectedAttemptsConfig() = runTest {
        coordinator.start()
        pairing.pairResult = PairResult.Invalid("the ticket expired")
        pairing.writeEvenWhenRejected = true

        // Rejected, but the store wrote a readable config: its own observation stays ignored.
        coordinator.pair("phonxiety://pair?token=old")
        coordinator.onPairingObserved()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)

        // The pairing goes away, then a new one arrives by another path.
        pairing.pairResult = PairResult.Paired
        pairing.pairedState.value = false
        coordinator.onPairingCleared()
        pairing.pairedState.value = true
        coordinator.onPairingObserved()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
        assertEquals(1, hosts.count { it == SetupHostRequest.Finished })
    }

    @Test
    fun aRejectedAttemptIsNotPromotedByTheConfigItWrote() = runTest {
        coordinator.start()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        pairing.pairResult = PairResult.Invalid("the ticket expired")
        pairing.writeEvenWhenRejected = true

        coordinator.pair("phonxiety://pair?token=old")

        assertTrue(hosts.any { it == SetupHostRequest.PairingRejected("the ticket expired") })
        assertTrue(pairing.pairedState.value)
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        val probes = backend.detects

        coordinator.onPairingObserved()

        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        assertEquals(probes, backend.detects)
        assertEquals(0, linkTests())

        // An explicit attempt still works and cleans the rejection up.
        pairing.pairResult = PairResult.Paired
        coordinator.pair("phonxiety://pair?token=new")

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
    }

    @Test
    fun anInvalidLinkWithoutAWriteStaysRejected() = runTest {
        coordinator.start()
        pairing.pairResult = PairResult.Invalid("the link is not a phonxiety pairing")

        coordinator.pair("https://example.invalid/not-a-pairing")

        assertTrue(hosts.any { it == SetupHostRequest.PairingRejected("the link is not a phonxiety pairing") })
        assertEquals(false, pairing.pairedState.value)
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        assertEquals(0, linkTests())
    }

    @Test
    fun theFirstTestWaitsForTheLinkInsteadOfFailingWhileConnecting() = runTest {
        link.value = LinkState.Connecting(LinkKind.IROH)
        coordinator.start()
        pairing.pairedState.value = true

        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()

        assertEquals(WizardState.FirstLinkTest(MAGISK), coordinator.wizard.value)
        assertEquals(0, linkTests())

        link.value = readyLink().value
        runCurrent()
        observation.join()

        assertEquals(WizardState.Ready, coordinator.wizard.value)
        assertEquals(1, linkTests())
    }

    @Test
    fun aLinkThatNeverComesUpHasABoundedExplainedOutcome() = runTest {
        link.value = LinkState.Connecting(LinkKind.IROH)
        coordinator.start()
        pairing.pairedState.value = true

        val observation = launch { coordinator.onPairingObserved() }
        runCurrent()
        assertEquals(WizardState.FirstLinkTest(MAGISK), coordinator.wizard.value)

        advanceTimeBy(31_000)
        runCurrent()
        observation.join()

        val doctor = coordinator.wizard.value as WizardState.Doctor
        assertEquals(DoctorReason.LINK_TEST_FAILED, doctor.reason)
        assertTrue(doctor.detail.orEmpty(), doctor.detail.orEmpty().contains("did not come up within 30 s"))
        assertEquals(0, linkTests())
    }

    @Test
    fun theAutomaticScanFiresOncePerPairingVisit() = runTest {
        permissions.missingState = mutableListOf(RuntimePermission.RECORD_AUDIO)
        coordinator.start()
        assertTrue(coordinator.wizard.value is WizardState.NeedsRuntime)

        permissions.missingState = mutableListOf()
        coordinator.onPermissionsChanged()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        assertEquals(1, startRequests())

        // A resume, and the scanner's Cancelled result, do not relaunch the automatic scan.
        coordinator.dispatch(WizardEvent.RecheckRequested)
        coordinator.onPairingObserved()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        assertEquals(1, startRequests())

        // A new visit earns a new automatic launch: the wizard re-probes into NeedsRuntime and comes
        // back to NeedsPairing once the permission is granted.
        permissions.missingState = mutableListOf(RuntimePermission.RECORD_AUDIO)
        coordinator.dispatch(WizardEvent.RecheckRequested)
        assertTrue(coordinator.wizard.value is WizardState.NeedsRuntime)
        permissions.missingState = mutableListOf()
        coordinator.onPermissionsChanged()
        assertEquals(WizardState.NeedsPairing(MAGISK), coordinator.wizard.value)
        assertEquals(2, startRequests())
    }

    private fun linkTests(): Int = appApi.requestedPaths.count { it == "/app/v1/link-test" }

    private fun startRequests(): Int = hosts.count { it == SetupHostRequest.StartPairing }

    private companion object {
        val MAGISK = DetectedProvider(
            id = RootId.MAGISK,
            version = "31.1",
            managerPackage = "com.topjohnwu.magisk",
            managerInstalled = true
        )

        val PASSED = VerifyReport(VerifyCheck.entries.associateWith { VerifyOutcome.PASS })

        val HEALTHY: ApiResult = ApiResult.Success(
            status = 200,
            result = buildJsonObject {
                put("healthy", true)
                put("verdict", "good")
                put("p50", 41)
                put("loss", 0.0)
            }
        )
    }
}
