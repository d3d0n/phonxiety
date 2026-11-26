package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision tree as a table: every check, its fix, and who may run it. */
class DoctorPolicyTest {
    @Test
    fun everyKindHasItsFixAndClass() {
        val expected = mapOf(
            DoctorItemKind.ROOT_PROVIDER to null,
            DoctorItemKind.MODULE_PRESENT to (DoctorFixId.REINSTALL_MODULE to FixClass.CONFIRM),
            DoctorItemKind.MODULE_ENABLED to (DoctorFixId.ENABLE_MODULE to FixClass.CONFIRM),
            DoctorItemKind.OVERLAY to (DoctorFixId.REINSTALL_MODULE to FixClass.CONFIRM),
            DoctorItemKind.PRIVILEGED_PERMISSION to (DoctorFixId.REINSTALL_MODULE to FixClass.CONFIRM),
            DoctorItemKind.RUNTIME_PERMISSION to (DoctorFixId.REGRANT_RUNTIME_PERMISSION to FixClass.CONFIRM),
            DoctorItemKind.NOTIFICATIONS to (DoctorFixId.REGRANT_NOTIFICATION to FixClass.CONFIRM),
            DoctorItemKind.BATTERY_EXEMPTION to (DoctorFixId.REQUEST_BATTERY_EXEMPTION to FixClass.CONFIRM),
            DoctorItemKind.LAST_CRASH to null,
            DoctorItemKind.PAIRING to (DoctorFixId.REPAIR_PAIRING to FixClass.CONFIRM),
            DoctorItemKind.LINK to (DoctorFixId.RESTART_SERVICE to FixClass.AUTOMATIC),
            DoctorItemKind.DAEMON to (DoctorFixId.RESTART_SERVICE to FixClass.AUTOMATIC),
            DoctorItemKind.RELAY to (DoctorFixId.DAEMON_FIX to FixClass.CONFIRM),
            DoctorItemKind.DAEMON_CHECK to null
        )
        // Every kind in the enum is in the table, so a new check cannot ship without a decision.
        assertEquals(DoctorItemKind.entries.toSet(), expected.keys)

        expected.forEach { (kind, expectation) ->
            val plan = DoctorPolicy.planFor(kind)
            if (expectation == null) {
                assertNull("$kind must explain instead of acting", plan)
            } else {
                assertNotNull("$kind has no fix", plan)
                assertEquals(kind.name, expectation.first, plan?.fix)
                assertEquals(kind.name, expectation.second, plan?.risk)
            }
        }
    }

    @Test
    fun rebootingFixesSaySoAndAskFirst() {
        val rebooting = listOf(
            DoctorItemKind.MODULE_PRESENT,
            DoctorItemKind.MODULE_ENABLED,
            DoctorItemKind.OVERLAY,
            DoctorItemKind.PRIVILEGED_PERMISSION
        )
        rebooting.forEach { kind ->
            val plan = requireNotNull(DoctorPolicy.planFor(kind))
            assertTrue("$kind must announce the reboot", plan.needsReboot)
            assertEquals(FixClass.CONFIRM, plan.risk)
        }
        assertTrue(requireNotNull(DoctorPolicy.planFor(DoctorItemKind.LINK)).effect.isNotBlank())
    }

    @Test
    fun theOverlayFixNamesAMetamoduleOnlyForProvidersThatNeedOne() {
        val effect = requireNotNull(DoctorPolicy.planFor(DoctorItemKind.OVERLAY)).effect
        assertTrue(effect.contains("A provider that mounts system/ through a metamodule"))
    }

    @Test
    fun everyPlanExplainsEffectAndRollback() {
        DoctorItemKind.entries.mapNotNull(DoctorPolicy::planFor).forEach { plan ->
            assertTrue("${plan.fix} has no effect text", plan.effect.isNotBlank())
            assertTrue("${plan.fix} has no rollback text", plan.rollback.isNotBlank())
        }
        val daemonPlan = DoctorPolicy.daemonPlan("relay_config", "re-read the relay list", restartRequired = true)
        assertTrue(daemonPlan.effect.isNotBlank())
        assertTrue(daemonPlan.rollback.isNotBlank())
    }

    @Test
    fun onlyTheSafeFixesAreAutomatic() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(
                runtimeMissing = setOf(RuntimePermission.RECORD_AUDIO),
                battery = false
            ),
            bridge = TestData.bridge(link = LinkState.Offline),
            daemon = DaemonProbe.Unreachable("link_down", "the bridge service is not running")
        )
        val automatic = DoctorPolicy.automaticFixes(rows, callActive = false)
        assertEquals(listOf(DoctorFixId.RESTART_SERVICE), automatic.map { requireNotNull(it.plan).fix })
        assertTrue(automatic.none { it.id == DoctorEvaluator.ID_RUNTIME })
    }

    @Test
    fun aLiveCallTurnsEveryCallPathFixIntoNever() {
        val rows = TestData.rows(
            snapshot = TestData.snapshot(module = ModuleState.Disabled("0.3.0", 3)),
            bridge = TestData.bridge(link = LinkState.Offline, call = TestData.call()),
            daemon = DaemonProbe.Unreachable("link_down", "down"),
            callActive = true
        )
        listOf(DoctorEvaluator.ID_LINK, DoctorEvaluator.ID_DAEMON, DoctorEvaluator.ID_MODULE_ENABLED)
            .forEach { id ->
                val plan = requireNotNull(TestData.row(rows, id).plan)
                assertEquals("$id must wait for the call to end", FixClass.NEVER, plan.risk)
            }
        val permission = TestData.row(
            TestData.rows(
                snapshot = TestData.snapshot(runtimeMissing = setOf(RuntimePermission.RECORD_AUDIO)),
                bridge = TestData.bridge(call = TestData.call()),
                callActive = true
            ),
            "${DoctorEvaluator.ID_RUNTIME}:${RuntimePermission.RECORD_AUDIO.androidName}"
        )
        assertEquals(FixClass.CONFIRM, permission.plan?.risk)
        assertTrue(DoctorPolicy.automaticFixes(rows, callActive = true).isEmpty())
    }

    @Test
    fun effectiveRiskFollowsTheDaemonRestartToo() {
        val relay = DoctorPolicy.daemonPlan("relay_config", null, restartRequired = true)
        val directories = DoctorPolicy.daemonPlan("state_dirs", null, restartRequired = false)
        assertEquals(FixClass.NEVER, DoctorPolicy.effectiveRisk(relay, callActive = true))
        assertEquals(FixClass.CONFIRM, DoctorPolicy.effectiveRisk(relay, callActive = false))
        assertEquals(FixClass.AUTOMATIC, DoctorPolicy.effectiveRisk(directories, callActive = true))
    }

    @Test
    fun everyKindPointsAtARecoveryPageOrSaysItHasNone() {
        val expected = mapOf(
            DoctorItemKind.ROOT_PROVIDER to RecoveryId.ROOT_LOST_OTA,
            DoctorItemKind.MODULE_PRESENT to null,
            DoctorItemKind.MODULE_ENABLED to RecoveryId.MODULE_DISABLED,
            DoctorItemKind.OVERLAY to RecoveryId.PRIVILEGED_GRANT_LOST,
            DoctorItemKind.PRIVILEGED_PERMISSION to RecoveryId.PRIVILEGED_GRANT_LOST,
            DoctorItemKind.RUNTIME_PERMISSION to null,
            DoctorItemKind.NOTIFICATIONS to null,
            DoctorItemKind.BATTERY_EXEMPTION to null,
            DoctorItemKind.LAST_CRASH to null,
            DoctorItemKind.PAIRING to RecoveryId.PAIRING_REJECTED,
            DoctorItemKind.LINK to RecoveryId.DAEMON_UNREACHABLE,
            DoctorItemKind.DAEMON to RecoveryId.DAEMON_UNREACHABLE,
            DoctorItemKind.RELAY to RecoveryId.DAEMON_UNREACHABLE,
            DoctorItemKind.DAEMON_CHECK to null
        )
        assertEquals(DoctorItemKind.entries.toSet(), expected.keys)
        expected.forEach { (kind, page) ->
            assertEquals(kind.name, page, DoctorPolicy.recoveryFor(kind))
        }
    }

    @Test
    fun theRecoveryBookCoversEveryPageForEveryProvider() {
        val providers = listOf(null) + RootId.entries
        providers.forEach { provider ->
            val pages = RecoveryBook.pages(provider)
            assertEquals(RecoveryId.entries.toList(), pages.map { it.id })
            pages.forEach { page ->
                assertTrue("${page.id} has no happened text", page.happened.isNotBlank())
                assertTrue("${page.id} has no phone steps", page.onPhone.isNotEmpty())
                assertTrue("${page.id} has no computer steps", page.fromComputer.isNotEmpty())
            }
        }
    }
}
