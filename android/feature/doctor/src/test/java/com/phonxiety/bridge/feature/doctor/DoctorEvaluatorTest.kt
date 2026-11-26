package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.DoctorCheck
import com.phonxiety.bridge.model.LinkError
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.PrivilegedPermissions
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows a snapshot produces: each check's state, its evidence and the repair attached to it. */
class DoctorEvaluatorTest {
    @Test
    fun anAllGreenInstallHasNoFailingRow() {
        val rows = TestData.rows()
        assertEquals(24, rows.size)
        assertTrue(rows.none { it.needsFix })
        assertEquals(DoctorSeverity.OK, DoctorUiState(rows = rows).overall)
    }

    @Test
    fun aDeniedSuExplainsInsteadOfOfferingAFix() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(root = TestData.root(su = SuStatus.DENIED, providers = emptyList()))
        )
        val root = TestData.row(rows, DoctorEvaluator.ID_ROOT)
        assertEquals(DoctorSeverity.FAIL, root.severity)
        assertNull("re-rooting is not an executable fix", root.plan)
        assertTrue(root.evidence.contains("refused"))
    }

    @Test
    fun twoProvidersAreAWarningNotAnInstall() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(
                root = TestData.root(
                    providers = listOf(
                        TestData.magisk,
                        DetectedProvider(RootId.KERNELSU, "v1.0", "me.weishu.kernelsu", true)
                    )
                )
            )
        )
        val root = TestData.row(rows, DoctorEvaluator.ID_ROOT)
        assertEquals(DoctorSeverity.WARN, root.severity)
        assertTrue(root.evidence.contains("installing would be a guess"))
    }

    @Test
    fun theProviderThatAnsweredIsChosenAndTheRestAreListedAsLeftovers() {
        val kernelSu = DetectedProvider(RootId.KERNELSU, "3.3.1", "me.weishu.kernelsu", true)
        val rows = TestData.rows(
            snapshot = TestData.snapshot(
                root = TestData.root(providers = listOf(TestData.magisk, kernelSu), active = RootId.KERNELSU)
            )
        )

        val root = TestData.row(rows, DoctorEvaluator.ID_ROOT)
        assertEquals(DoctorSeverity.OK, root.severity)
        assertTrue(root.evidence.startsWith("KernelSU 3.3.1"))
        assertTrue(root.evidence.contains("inactive leftovers: Magisk"))
    }

    @Test
    fun aHidingManagerExplainsThePendingGrant() {
        val kernelSu = DetectedProvider(RootId.KERNELSU, "3.3.1", "me.weishu.kernelsu", true)
        val rows = TestData.rows(
            snapshot = TestData.snapshot(root = TestData.root(su = SuStatus.NOT_ROOTED, providers = listOf(kernelSu)))
        )

        val root = TestData.row(rows, DoctorEvaluator.ID_ROOT)
        assertEquals(DoctorSeverity.FAIL, root.severity)
        assertTrue(root.evidence.contains("KernelSU manager is installed"))
        assertTrue(root.evidence.contains("hidden from this app"))
    }

    @Test
    fun anInstalledMagiskAppWithNoSuMeansMagiskIsNotActive() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(root = TestData.root(su = SuStatus.NOT_ROOTED, providers = listOf(TestData.magisk)))
        )

        val root = TestData.row(rows, DoctorEvaluator.ID_ROOT)
        assertEquals(DoctorSeverity.FAIL, root.severity)
        assertTrue(root.evidence.contains("Magisk app is installed"))
        assertTrue(root.evidence.contains("not active"))
    }

    @Test
    fun aMissingModuleFailsBothModuleRows() {
        val rows = TestData.rows(snapshot = TestData.snapshot(module = ModuleState.Absent))
        assertEquals(DoctorSeverity.FAIL, TestData.row(rows, DoctorEvaluator.ID_MODULE_PRESENT).severity)
        assertEquals(DoctorSeverity.FAIL, TestData.row(rows, DoctorEvaluator.ID_MODULE_ENABLED).severity)
        assertEquals(DoctorFixId.REINSTALL_MODULE, TestData.row(rows, DoctorEvaluator.ID_MODULE_PRESENT).plan?.fix)
    }

    @Test
    fun aStagedModuleWaitsForItsReboot() {
        val rows = TestData.rows(snapshot = TestData.snapshot(module = ModuleState.Staged("0.3.0", 3)))
        assertEquals(DoctorSeverity.WARN, TestData.row(rows, DoctorEvaluator.ID_MODULE_PRESENT).severity)
        assertTrue(TestData.row(rows, DoctorEvaluator.ID_MODULE_ENABLED).evidence.contains("next boot"))
    }

    @Test
    fun anOverlayThatIsNotMountedNamesTheMetamoduleWhenItIsTheCause() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(
                overlay = Overlay.METAMODULE_MISSING,
                facts = TestData.facts(
                    apkPath = "/data/app/~~8Kd0/base.apk",
                    system = false
                )
            )
        )
        val overlay = TestData.row(rows, DoctorEvaluator.ID_OVERLAY)
        assertEquals(DoctorSeverity.FAIL, overlay.severity)
        assertTrue(overlay.evidence.contains("the provider needs a metamodule"))
    }

    @Test
    fun anOverlayThatIsNotMountedDoesNotBlameAMetamoduleOnABuiltInProvider() {
        // Magisk and an older apd overlay system/ themselves: a metamodule is not their fix.
        val rows = TestData.rows(
            snapshot = TestData.snapshot(
                overlay = Overlay.BUILT_IN,
                facts = TestData.facts(
                    apkPath = "/data/app/~~8Kd0/base.apk",
                    system = false
                )
            )
        )
        val overlay = TestData.row(rows, DoctorEvaluator.ID_OVERLAY)
        assertEquals(DoctorSeverity.FAIL, overlay.severity)
        assertFalse(overlay.evidence.contains("metamodule"))
    }

    @Test
    fun aPrivappPathOrAnUpdatedSystemAppCountsAsApplied() {
        listOf(
            TestData.facts(apkPath = "/system/priv-app/phonxiety-bridge/base.apk"),
            TestData.facts(apkPath = "/data/app/~~x/base.apk", system = false, updated = true)
        ).forEach { facts ->
            val rows = TestData.rows(snapshot = TestData.snapshot(facts = facts))
            assertEquals(DoctorSeverity.OK, TestData.row(rows, DoctorEvaluator.ID_OVERLAY).severity)
        }
    }

    @Test
    fun everyPrivilegedPermissionGetsItsOwnRow() {
        val rows = TestData.rows()
        PrivilegedPermissions.ALL.forEach { permission ->
            val row = TestData.row(rows, "${DoctorEvaluator.ID_PRIVILEGED}:$permission")
            assertEquals(DoctorSeverity.OK, row.severity)
            assertEquals(permission, row.subject)
        }
        val missing = PrivilegedPermissions.CAPTURE_AUDIO_OUTPUT
        val failing = TestData.rows(
            snapshot = TestData.snapshot(facts = TestData.facts(granted = PrivilegedPermissions.ALL - missing))
        )
        val row = TestData.row(failing, "${DoctorEvaluator.ID_PRIVILEGED}:$missing")
        assertEquals(DoctorSeverity.FAIL, row.severity)
        assertEquals(DoctorFixId.REINSTALL_MODULE, row.plan?.fix)
    }

    @Test
    fun theLocalNetworkGrantIsExplainedAsATimeout() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(runtimeMissing = setOf(RuntimePermission.ACCESS_LOCAL_NETWORK))
        )
        val row = TestData.row(rows, "${DoctorEvaluator.ID_RUNTIME}:${RuntimePermission.ACCESS_LOCAL_NETWORK.androidName}")
        assertEquals(DoctorSeverity.FAIL, row.severity)
        assertTrue(row.evidence.contains("timeout"))
        assertEquals(DoctorFixId.REGRANT_RUNTIME_PERMISSION, row.plan?.fix)
    }

    @Test
    fun notificationsCarryThePermissionOrTheSettingsSubject() {
        val missing = TestData.rows(
            snapshot = TestData.snapshot(runtimeMissing = setOf(RuntimePermission.POST_NOTIFICATIONS))
        )
        assertEquals(
            RuntimePermission.POST_NOTIFICATIONS.androidName,
            TestData.row(missing, DoctorEvaluator.ID_NOTIFICATIONS).subject
        )
        val switchedOff = TestData.rows(snapshot = TestData.snapshot(notifications = false))
        assertEquals(
            DoctorEvaluator.SUBJECT_NOTIFICATION_SETTINGS,
            TestData.row(switchedOff, DoctorEvaluator.ID_NOTIFICATIONS).subject
        )
    }

    @Test
    fun batteryAndPairingFailuresAreClassified() {
        val rows = TestData.rows(snapshot = TestData.snapshot(battery = false), paired = false)
        val battery = TestData.row(rows, DoctorEvaluator.ID_BATTERY)
        assertEquals(DoctorSeverity.WARN, battery.severity)
        assertEquals(DoctorFixId.REQUEST_BATTERY_EXEMPTION, battery.plan?.fix)
        assertEquals(DoctorSeverity.FAIL, TestData.row(rows, DoctorEvaluator.ID_PAIRING).severity)
    }

    @Test
    fun theLinkRowCarriesThePathAndTheLastError() {
        val relay = TestData.row(
            TestData.rows(
                bridge = TestData.bridge(
                    link = LinkState.Up(LinkKind.IROH, PathKind.RELAY, "relay euw1", 180, 0, 4)
                )
            ),
            DoctorEvaluator.ID_LINK
        )
        assertEquals(DoctorSeverity.WARN, relay.severity)
        assertTrue(relay.evidence.contains("relay"))
        assertTrue(relay.evidence.contains("4 reconnects"))

        val offline = TestData.row(
            TestData.rows(
                bridge = BridgeState(
                    link = LinkState.Offline,
                    lastError = LinkError("auth_failed", "the daemon rejected the app token", 0)
                )
            ),
            DoctorEvaluator.ID_LINK
        )
        assertEquals(DoctorSeverity.FAIL, offline.severity)
        assertTrue(offline.evidence.contains("auth_failed"))
    }

    @Test
    fun anUnreachableDaemonFailsTheDaemonRowAndLeavesRelayUnknown() {
        val rows = TestData.rows(daemon = DaemonProbe.Unreachable("link_down", "no gateway"))
        val daemon = TestData.row(rows, DoctorEvaluator.ID_DAEMON)
        assertEquals(DoctorSeverity.FAIL, daemon.severity)
        assertTrue(daemon.evidence.contains("link_down"))
        assertEquals(DoctorFixId.RESTART_SERVICE, daemon.plan?.fix)
        assertEquals(DoctorSeverity.UNKNOWN, TestData.row(rows, DoctorEvaluator.ID_RELAY).severity)
        assertFalse(rows.any { it.kind == DoctorItemKind.DAEMON_CHECK })
    }

    @Test
    fun theDaemonsRelayChecksBecomeOneRowAndTheRestBecomeTheirOwn() {
        val report = TestData.report(
            status = "warn",
            checks = listOf(
                DoctorCheck("daemon", "ok", "pid 42"),
                DoctorCheck("phone", "fail", "no phone is connected"),
                DoctorCheck("iroh_relay", "warn", "pair by address instead"),
                DoctorCheck("relays", "ok", "2 custom relays")
            )
        )
        val rows = TestData.rows(daemon = DaemonProbe.Reached(report))
        val relay = TestData.row(rows, DoctorEvaluator.ID_RELAY)
        assertEquals(DoctorSeverity.WARN, relay.severity)
        assertTrue(relay.evidence.contains("pair by address instead"))
        assertTrue(relay.evidence.contains("2 custom relays"))
        assertEquals(DoctorFixId.DAEMON_FIX, relay.plan?.fix)
        assertEquals(DoctorPolicy.RELAY_FIX, relay.plan?.daemonFix)

        val phone = TestData.row(rows, "${DoctorEvaluator.ID_DAEMON_CHECK}:phone")
        assertEquals("Phone connection", phone.title)
        assertEquals(DoctorSeverity.FAIL, phone.severity)
        assertNull("the daemon names its own fix in its fixes list", phone.plan)
    }

    @Test
    fun aDaemonSaysItsOwnStatus() {
        val rows = TestData.rows(daemon = DaemonProbe.Reached(TestData.report(status = "fail")))
        assertEquals(DoctorSeverity.FAIL, TestData.row(rows, DoctorEvaluator.ID_DAEMON).severity)
    }

    @Test
    fun aReadErrorLeavesUnknownsInsteadOfInventingFailures() {
        val rows = TestData.rows(snapshot = TestData.snapshot(module = ModuleState.Unknown, facts = null))
        assertEquals(DoctorSeverity.UNKNOWN, TestData.row(rows, DoctorEvaluator.ID_MODULE_PRESENT).severity)
        assertEquals(DoctorSeverity.UNKNOWN, TestData.row(rows, DoctorEvaluator.ID_OVERLAY).severity)
        assertEquals(
            DoctorSeverity.UNKNOWN,
            TestData.row(rows, "${DoctorEvaluator.ID_PRIVILEGED}:${PrivilegedPermissions.REBOOT}").severity
        )
    }
}
