package com.phonxiety.bridge

import com.phonxiety.bridge.model.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's report dispatch, driven the way `StateReportPublisher.report` drives it: the plan
 * decides, and the harness applies it to the wire and to a real [EngineStateStore].
 */
class StateReportDispatchTest {
    private class ReportHarness(private val store: EngineStateStore) {
        var reportedState: String? = null
        var reportedCauseKey: String? = null
        val wire = mutableListOf<Pair<String, String?>>()

        fun report(state: String, causeKey: String? = null, connectTimeMillis: Long? = null) {
            val plan = StateReportDispatch.plan(reportedState, reportedCauseKey, state, causeKey) ?: return
            if (plan.recordState) reportedState = state
            if (plan.recordCauseKey) reportedCauseKey = causeKey
            if (plan.send) wire += state to causeKey
            if (plan.replay) {
                store.callPhaseReplayed(callPhaseOf(state), connectTimeMillis = connectTimeMillis)
            } else {
                store.callPhase(callPhaseOf(state), connectTimeMillis = connectTimeMillis)
            }
        }
    }

    private fun connectTime(store: EngineStateStore): Long? = store.state.value.call?.connectedAtEpochMs

    @Test
    fun aNewPhaseIsSentOnceAndMayStampTheAnswer() {
        val store = EngineStateStore()
        store.callStarted("c1", "+998******4567", 1_000)
        val harness = ReportHarness(store)

        harness.report("active", connectTimeMillis = 5_000)

        assertEquals(listOf("active" to null), harness.wire)
        assertEquals("active", harness.reportedState)
        assertEquals(5_000L, connectTime(store))
    }

    @Test
    fun anAdoptedActiveRepeatNeverMinesItsOwnAnswer() {
        val store = EngineStateStore()
        store.callStarted("c1", "+998******4567", 1_000)
        // Recovery restored ACTIVE from the ledger, with no answer recorded yet.
        store.callPhaseReplayed(CallPhase.ACTIVE)
        val harness = ReportHarness(store)
        harness.reportedState = "active"

        harness.report("active")

        assertTrue(harness.wire.isEmpty())
        assertNull(connectTime(store))

        // The same adoption later sees Telecom's own connect time; the store takes it.
        harness.report("active", connectTimeMillis = 7_000)

        assertTrue(harness.wire.isEmpty())
        assertEquals(7_000L, connectTime(store))
    }

    @Test
    fun anAdoptedActiveRepeatKeepsAnAnswerTheStoreAlreadyHas() {
        val store = EngineStateStore()
        store.callStarted("c1", "+998******4567", 1_000)
        store.callPhase(CallPhase.ACTIVE, connectTimeMillis = 4_000)
        val harness = ReportHarness(store)
        harness.reportedState = "active"

        harness.report("active", connectTimeMillis = 9_000)

        assertTrue(harness.wire.isEmpty())
        assertEquals(4_000L, connectTime(store))
    }

    @Test
    fun aRepeatedEndedCauseIsNotReportedAgain() {
        val store = EngineStateStore()
        store.callStarted("c1", "+998******4567", 1_000)
        val harness = ReportHarness(store)

        harness.report("ended", "16|Normal|")
        harness.report("ended", "16|Normal|")
        harness.report("ended", null)

        assertEquals(listOf("ended" to "16|Normal|"), harness.wire)
        assertEquals("16|Normal|", harness.reportedCauseKey)
    }

    @Test
    fun aDifferentEndedCauseIsReportedOnceMore() {
        val store = EngineStateStore()
        store.callStarted("c1", "+998******4567", 1_000)
        val harness = ReportHarness(store)

        harness.report("ended", "16|Normal|")
        harness.report("ended", "3|Busy|")
        harness.report("ended", "3|Busy|")

        assertEquals(listOf("ended" to "16|Normal|", "ended" to "3|Busy|"), harness.wire)
        assertEquals("3|Busy|", harness.reportedCauseKey)
        assertEquals(CallPhase.ENDED, store.state.value.call?.phase)
    }

    @Test
    fun aNewEndedCauseAfterAnEndedWithoutCauseIsReported() {
        val store = EngineStateStore()
        store.callStarted("c1", "+998******4567", 1_000)
        val harness = ReportHarness(store)

        harness.report("ended")
        harness.report("ended", "3|Busy|")

        assertEquals(listOf("ended" to null, "ended" to "3|Busy|"), harness.wire)
        assertEquals("3|Busy|", harness.reportedCauseKey)
    }
}
