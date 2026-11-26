package com.phonxiety.bridge.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.ProgressRecord
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootCapabilities
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.SuStatus
import com.phonxiety.bridge.root.WizardEvent
import com.phonxiety.bridge.root.WizardState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KernelSU's metamodule needs two boots: the metamodule first, then Phonxiety. These tests drive
 * the coordinator through that flow and through the resume after each boot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetamodulePrepFlowTest {
    private lateinit var backend: FakeBackend
    private lateinit var pairing: FakePairing
    private lateinit var progress: FakeProgressStore
    private lateinit var preferences: SetupPreferences

    @Before
    fun setUp() {
        preferences = SetupPreferences(ApplicationProvider.getApplicationContext<Context>()).apply { reset() }
        backend = FakeBackend()
        pairing = FakePairing().apply { pairedState.value = true }
        progress = FakeProgressStore()
        backend.detection = RootDetection(SuStatus.GRANTED, listOf(KERNELSU))
        backend.module = ModuleState.Absent
    }

    private fun coordinator(progressStore: FakeProgressStore = progress) = SetupCoordinator(
        backend = backend,
        pairing = pairing,
        appApi = FakeAppApi(),
        progressStore = progressStore,
        preferences = preferences,
        permissions = FakePermissions(),
        serviceStarter = {},
        host = {}
    )

    @Test
    fun aStagedMetamoduleLeadsToTheRebootStepWithoutInstalling() = runTest {
        backend.capabilities = capabilities(Overlay.METAMODULE_MISSING, pending = true)

        val wizard = coordinator()
        wizard.start()

        assertEquals(
            WizardState.RebootPending(KERNELSU, RebootReason.METAMODULE_INSTALL, ModuleState.Absent),
            wizard.wizard.value
        )
        assertTrue(backend.installs.isEmpty())
        assertEquals(InstallOperation.PREPARE_METAMODULE, progress.record?.operation)
    }

    @Test
    fun aSuccessfulPreparationKeepsTheProgressWhenTheRebootIsRequested() = runTest {
        backend.capabilities = capabilities(Overlay.METAMODULE_MISSING, pending = false)

        val wizard = coordinator()
        wizard.start()
        assertEquals(WizardState.ProviderPrep(KERNELSU), wizard.wizard.value)

        wizard.dispatch(WizardEvent.PrepareProviderRequested)
        assertEquals(listOf(InstallOperation.PREPARE_METAMODULE), backend.installs)
        assertEquals(
            WizardState.RebootPending(KERNELSU, RebootReason.METAMODULE_INSTALL, ModuleState.Absent),
            wizard.wizard.value
        )
        assertEquals(InstallOperation.PREPARE_METAMODULE, progress.record?.operation)

        wizard.dispatch(WizardEvent.RebootRequested)

        assertEquals(listOf(RebootReason.METAMODULE_INSTALL), backend.reboots)
        assertEquals(InstallOperation.PREPARE_METAMODULE, progress.record?.operation)
        assertTrue(wizard.wizard.value is WizardState.RebootPending)
    }

    @Test
    fun theWizardRebuiltFromThePendingStateLandsOnTheRebootStepAndNeverInstalls() = runTest {
        backend.capabilities = capabilities(Overlay.METAMODULE_MISSING, pending = true)
        val onDisk = FakeProgressStore().apply {
            record = ProgressRecord(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU, startedAtMillis = 1L)
        }

        val rebuilt = coordinator(progressStore = onDisk)
        rebuilt.start()

        val pending = rebuilt.wizard.value as WizardState.RebootPending
        assertEquals(RebootReason.METAMODULE_INSTALL, pending.reason)
        assertEquals(ModuleState.Absent, pending.module)
        assertTrue(backend.installs.isEmpty())
        assertEquals(InstallOperation.PREPARE_METAMODULE, onDisk.record?.operation)
    }

    @Test
    fun afterTheMetamoduleBootTheWizardIsReadyToInstallTheModule() = runTest {
        backend.capabilities = capabilities(Overlay.METAMODULE_PRESENT, pending = false)
        progress.record = ProgressRecord(InstallOperation.PREPARE_METAMODULE, RootId.KERNELSU, startedAtMillis = 1L)

        val wizard = coordinator()
        wizard.start()

        assertEquals(WizardState.ReadyToInstall(KERNELSU, ModuleState.Absent), wizard.wizard.value)
        assertTrue(backend.installs.isEmpty())
    }

    private fun capabilities(overlay: Overlay, pending: Boolean): RootCapabilities = RootCapabilities(
        systemOverlay = overlay,
        hotInstall = true,
        updateJson = true,
        webUi = true,
        actionButton = true,
        bootStages = emptySet(),
        managerPackage = "me.weishu.kernelsu",
        coexistenceWarning = null,
        metamodulePending = pending
    )

    private companion object {
        val KERNELSU = DetectedProvider(
            id = RootId.KERNELSU,
            version = "3.1.0",
            managerPackage = "me.weishu.kernelsu",
            managerInstalled = true
        )
    }
}
