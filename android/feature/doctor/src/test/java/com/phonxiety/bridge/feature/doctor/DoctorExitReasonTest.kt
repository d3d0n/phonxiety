package com.phonxiety.bridge.feature.doctor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The system's `ApplicationExitInfo` reasons as words. The recorder writes the name it knows and
 * `reason_<n>` for a value newer than itself, so both forms have to read as a phrase — and a reason
 * nobody can explain must say so instead of leaving a number in the sentence.
 */
class DoctorExitReasonTest {
    @Test
    fun theReasonsTheRecorderNamesReadAsPhrases() {
        assertEquals(R.string.doctor_exit_memory, exitReasonLabel("low_memory"))
        assertEquals(R.string.doctor_exit_not_responding, exitReasonLabel("anr"))
        assertEquals(R.string.doctor_exit_crashed, exitReasonLabel("crash"))
        assertEquals(R.string.doctor_exit_crashed_native, exitReasonLabel("crash_native"))
        assertEquals(R.string.doctor_exit_signaled, exitReasonLabel("signaled"))
        assertEquals(R.string.doctor_exit_self, exitReasonLabel("exit_self"))
        assertEquals(R.string.doctor_exit_startup, exitReasonLabel("initialization_failure"))
        assertEquals(R.string.doctor_exit_permission, exitReasonLabel("permission_change"))
        assertEquals(R.string.doctor_exit_resources, exitReasonLabel("excessive_resource_usage"))
        assertEquals(R.string.doctor_exit_user, exitReasonLabel("user_requested"))
        assertEquals(R.string.doctor_exit_frozen, exitReasonLabel("freezer"))
    }

    @Test
    fun aNumericReasonTheRecorderDidNotNameStillReads() {
        // The device record that used to read "exit at 2026-11-26 09:03:27 (reason_16)": on the
        // SDK this build compiles against, 16 is REASON_PACKAGE_UPDATED.
        assertEquals(R.string.doctor_exit_updated, exitReasonLabel("reason_16"))
        assertEquals(R.string.doctor_exit_memory, exitReasonLabel("reason_3"))
        assertEquals(R.string.doctor_exit_not_responding, exitReasonLabel("reason_6"))
    }

    @Test
    fun aReasonNobodyCanExplainSaysSo() {
        assertEquals(R.string.doctor_exit_unavailable, exitReasonLabel("reason_99"))
        assertEquals(R.string.doctor_exit_unavailable, exitReasonLabel("other"))
        assertEquals(R.string.doctor_exit_unavailable, exitReasonLabel(null))
        assertEquals(R.string.doctor_exit_unavailable, exitReasonLabel(""))
    }
}
